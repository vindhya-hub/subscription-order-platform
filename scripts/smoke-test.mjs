#!/usr/bin/env node
// End-to-end check of a running stack through its GraphQL API (Node 18+, no dependencies):
//   1. a new subscription is billed and its order is processed asynchronously to FULFILLED;
//   2. with permanent failures injected for one plan, an order fails maxReceiveCount times and lands in the DLQ;
//   3. after switching failures off, replaying that dead letter fulfils the order.
//
// Usage: node scripts/smoke-test.mjs [graphql-url]      (default http://localhost:3000/graphql, i.e. via nginx)
// SMOKE_TIMEOUT_MS bounds each wait (default 120000).

const url = process.argv[2] ?? process.env.SMOKE_GRAPHQL_URL ?? 'http://localhost:3000/graphql';
const timeoutMs = Number(process.env.SMOKE_TIMEOUT_MS ?? 120_000);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (message) => console.log(`[smoke] ${message}`);

async function gql(query, variables = {}) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, variables }),
  });
  const body = await response.json().catch(() => ({}));
  if (body.errors?.length) {
    throw new Error(body.errors.map((error) => error.message).join('; '));
  }
  if (!response.ok || !body.data) {
    throw new Error(`HTTP ${response.status}`);
  }
  return body.data;
}

async function eventually(description, check, interval = 1000) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const result = await check();
      if (result) {
        return result;
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(interval);
  }
  throw new Error(`Timed out waiting for ${description}${lastError ? ` (${lastError.message})` : ''}`);
}

const order = async (id) =>
  (await gql('query($id: ID!) { order(id: $id) { id status attempts lastError } }', { id })).order;

async function subscribeAndBill(planId, label) {
  const email = `smoke-${label}-${Date.now()}@example.com`;
  const { createSubscription } = await gql(
    `mutation($input: CreateSubscriptionInput!) { createSubscription(input: $input) { id plan { code } } }`,
    { input: { newCustomer: { name: `Smoke ${label}`, email }, planId, quantity: 1, frequency: 'WEEKLY' } },
  );
  const { runBillingCycle } = await gql('mutation { runBillingCycle { asOf ordersCreated } }');
  const { orders } = await gql('query($id: ID) { orders(subscriptionId: $id) { id status } }', {
    id: createSubscription.id,
  });
  if (orders.length !== 1) {
    throw new Error(`Expected one order for subscription ${createSubscription.id}, got ${orders.length}`);
  }
  log(`subscription ${createSubscription.id} (${createSubscription.plan.code}) billed as of ${runBillingCycle.asOf}: order ${orders[0].id}`);
  return orders[0].id;
}

async function main() {
  log(`GraphQL endpoint ${url}`);
  const { queueStats } = await eventually('the API', () => gql('{ queueStats { provider maxReceiveCount } }'), 2000);
  const maxReceives = queueStats.maxReceiveCount;
  log(`broker: ${queueStats.provider}, maxReceiveCount ${maxReceives}`);

  const { plans } = await gql('{ plans { id code active } }');
  const active = plans.filter((plan) => plan.active);
  if (active.length < 2) {
    throw new Error('Need at least two active plans (the demo seed creates five)');
  }
  const [happyPlan, failingPlan] = active;

  // 1. Happy path.
  await gql('mutation { setFailureInjection(mode: NONE) { mode } }');
  const happyOrder = await subscribeAndBill(happyPlan.id, 'ok');
  const fulfilled = await eventually(`order ${happyOrder} to be FULFILLED`, async () => {
    const current = await order(happyOrder);
    return current.status === 'FULFILLED' && current;
  });
  log(`order ${happyOrder} FULFILLED after ${fulfilled.attempts} attempt(s)`);

  // 2. Permanent failure -> dead-letter queue after maxReceiveCount receives.
  await gql('mutation($plan: String) { setFailureInjection(mode: PERMANENT, planCode: $plan) { mode } }', {
    plan: failingPlan.code,
  });
  const failingOrder = await subscribeAndBill(failingPlan.id, 'dlq');
  const failed = await eventually(`order ${failingOrder} to be FAILED`, async () => {
    const current = await order(failingOrder);
    return current.status === 'FAILED' && current;
  });
  if (failed.attempts !== maxReceives) {
    throw new Error(`Expected ${maxReceives} attempts before dead-lettering, got ${failed.attempts}`);
  }
  const deadLetter = await eventually(`order ${failingOrder} in the dead-letter queue`, async () => {
    const { deadLetters } = await gql('{ deadLetters { messageId orderId } }');
    return deadLetters.find((letter) => letter.orderId === failingOrder);
  });
  log(`order ${failingOrder} FAILED after ${failed.attempts} attempts; dead letter ${deadLetter.messageId}`);

  // 3. Replay with failures switched off.
  await gql('mutation { setFailureInjection(mode: NONE) { mode } }');
  const { replayDeadLetters } = await gql(
    'mutation($ids: [ID!]) { replayDeadLetters(messageIds: $ids) { replayed } }',
    { ids: [deadLetter.messageId] },
  );
  if (replayDeadLetters.replayed !== 1) {
    throw new Error(`Expected to replay 1 dead letter, replayed ${replayDeadLetters.replayed}`);
  }
  const replayed = await eventually(`replayed order ${failingOrder} to be FULFILLED`, async () => {
    const current = await order(failingOrder);
    return current.status === 'FULFILLED' && current;
  });
  log(`replayed order ${failingOrder} FULFILLED (attempts ${replayed.attempts})`);
  log('PASSED');
}

main().catch(async (error) => {
  console.error(`[smoke] FAILED: ${error.message}`);
  await gql('mutation { setFailureInjection(mode: NONE) { mode } }').catch(() => {});
  process.exit(1);
});
