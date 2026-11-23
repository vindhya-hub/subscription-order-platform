import { graphql } from './graphql';
import type {
  BillingRunResult,
  CreateSubscriptionInput,
  Customer,
  DeadLetter,
  FailureInjection,
  FailureMode,
  Order,
  OrderStatus,
  PipelineStatus,
  Plan,
  ReplayResult,
  Subscription,
  SubscriptionAction,
  SubscriptionStatus,
} from './types';

const SUBSCRIPTION_FIELDS = `
  id status quantity frequency nextRunDate recurringAmount createdAt cancelledAt
  customer { id name email }
  plan { id code name currency }`;

const ORDER_FIELDS = `
  id runDate amount currency status attempts lastError createdAt updatedAt fulfilledAt
  subscription { id customer { name } plan { code name } }`;

const MUTATIONS: Record<SubscriptionAction, string> = {
  pause: 'pauseSubscription',
  resume: 'resumeSubscription',
  cancel: 'cancelSubscription',
};

/** Typed wrappers around every GraphQL operation the UI uses. */
export const api = {
  plans: (signal?: AbortSignal) =>
    graphql<{ plans: Plan[] }>(
      'query Plans { plans { id code name description unitPrice currency active } }',
      undefined,
      signal,
    ).then((data) => data.plans),

  customers: (signal?: AbortSignal) =>
    graphql<{ customers: Customer[] }>('query Customers { customers { id name email } }', undefined, signal).then(
      (data) => data.customers,
    ),

  subscriptions: (status: SubscriptionStatus | undefined, signal?: AbortSignal) =>
    graphql<{ subscriptions: Subscription[] }>(
      `query Subscriptions($status: SubscriptionStatus) {
        subscriptions(status: $status) { ${SUBSCRIPTION_FIELDS} }
      }`,
      { status },
      signal,
    ).then((data) => data.subscriptions),

  orders: (status: OrderStatus | undefined, limit: number, signal?: AbortSignal) =>
    graphql<{ orders: Order[] }>(
      `query Orders($status: OrderStatus, $limit: Int) {
        orders(status: $status, limit: $limit) { ${ORDER_FIELDS} }
      }`,
      { status, limit },
      signal,
    ).then((data) => data.orders),

  deadLetters: (signal?: AbortSignal) =>
    graphql<{ deadLetters: DeadLetter[] }>(
      `query DeadLetters { deadLetters { messageId orderId sentAt payload order { ${ORDER_FIELDS} } } }`,
      undefined,
      signal,
    ).then((data) => data.deadLetters),

  pipeline: (signal?: AbortSignal) =>
    graphql<PipelineStatus>(
      `query Pipeline {
        queueStats { provider visible inFlight deadLetters maxReceiveCount }
        failureInjection { mode planCode }
      }`,
      undefined,
      signal,
    ),

  createSubscription: (input: CreateSubscriptionInput) =>
    graphql<{ createSubscription: Subscription }>(
      `mutation CreateSubscription($input: CreateSubscriptionInput!) {
        createSubscription(input: $input) { ${SUBSCRIPTION_FIELDS} }
      }`,
      { input },
    ).then((data) => data.createSubscription),

  changeSubscription: (action: SubscriptionAction, id: string) =>
    graphql<Record<string, Subscription>>(
      `mutation ChangeSubscription($id: ID!) { ${MUTATIONS[action]}(id: $id) { ${SUBSCRIPTION_FIELDS} } }`,
      { id },
    ).then((data) => data[MUTATIONS[action]]),

  runBillingCycle: (asOf?: string) =>
    graphql<{ runBillingCycle: BillingRunResult }>(
      `mutation RunBillingCycle($asOf: Date) {
        runBillingCycle(asOf: $asOf) { asOf dueSubscriptions ordersCreated }
      }`,
      { asOf: asOf || undefined },
    ).then((data) => data.runBillingCycle),

  replayDeadLetters: (messageIds?: string[]) =>
    graphql<{ replayDeadLetters: ReplayResult }>(
      `mutation ReplayDeadLetters($messageIds: [ID!]) {
        replayDeadLetters(messageIds: $messageIds) { replayed messageIds }
      }`,
      { messageIds },
    ).then((data) => data.replayDeadLetters),

  setFailureInjection: (mode: FailureMode, planCode: string | null) =>
    graphql<{ setFailureInjection: FailureInjection }>(
      `mutation SetFailureInjection($mode: FailureMode!, $planCode: String) {
        setFailureInjection(mode: $mode, planCode: $planCode) { mode planCode }
      }`,
      { mode, planCode },
    ).then((data) => data.setFailureInjection),
};
