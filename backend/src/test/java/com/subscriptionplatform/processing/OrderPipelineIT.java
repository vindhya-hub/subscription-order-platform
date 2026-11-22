package com.subscriptionplatform.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.support.TestData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;

/**
 * End to end through the GraphQL API with the background consumer running: billing publishes OrderCreated
 * to the (in-memory) topic, the subscribed queue delivers it and the consumer pays and fulfils the order.
 * Retries use a 50 ms back-off in the test profile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@ActiveProfiles("test")
@Import(TestData.class)
class OrderPipelineIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private HttpGraphQlTester graphQl;
    @Autowired
    private TestData data;

    private Customer customer;
    private Plan coffee;
    private Plan tea;

    @BeforeEach
    void setUp() {
        data.reset();
        customer = data.customer("Ava Patel");
        coffee = data.plan("COFFEE", "16.50");
        tea = data.plan("TEA", "22.00");
    }

    @Test
    void billedOrdersAreProcessedAsynchronouslyAndFulfilled() {
        String subscriptionId = subscribe(coffee);

        String orderId = billAndGetOrderId(subscriptionId);

        OrderView order = awaitOrder(orderId, "FULFILLED");
        assertThat(order.attempts()).isEqualTo(1);
        assertThat(order.fulfilledAt()).isNotNull();
        assertQueuesEmpty();
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds() {
        setFailureInjection("TRANSIENT", null);
        String orderId = billAndGetOrderId(subscribe(coffee));

        OrderView order = awaitOrder(orderId, "FULFILLED");

        assertThat(order.attempts()).isEqualTo(2);
        assertThat(order.lastError()).isNull();
        assertQueuesEmpty();
    }

    @Test
    void aPermanentFailureLandsInTheDeadLetterQueueAfterThreeAttemptsAndReplayFulfilsIt() {
        setFailureInjection("PERMANENT", null);
        String orderId = billAndGetOrderId(subscribe(coffee));

        OrderView failed = awaitOrder(orderId, "FAILED");
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(failed.lastError()).contains("delivery 3");
        String messageId = await().atMost(TIMEOUT).until(() -> deadLetterFor(orderId), id -> id != null);
        graphQl.document("{ queueStats { visible inFlight deadLetters } }").execute()
                .path("queueStats.deadLetters").entity(Integer.class).isEqualTo(1);

        setFailureInjection("NONE", null);
        graphQl.document("mutation($ids: [ID!]) { replayDeadLetters(messageIds: $ids) { replayed messageIds } }")
                .variable("ids", List.of(messageId))
                .execute()
                .path("replayDeadLetters.replayed").entity(Integer.class).isEqualTo(1)
                .path("replayDeadLetters.messageIds").entityList(String.class).containsExactly(messageId);

        OrderView replayed = awaitOrder(orderId, "FULFILLED");
        assertThat(replayed.attempts()).isEqualTo(4);
        assertQueuesEmpty();
    }

    @Test
    void failureInjectionCanTargetASinglePlan() {
        setFailureInjection("PERMANENT", "TEA");
        String coffeeOrder = billAndGetOrderId(subscribe(coffee));
        String teaOrder = orderIdFor(subscribe(tea), "mutation { runBillingCycle { ordersCreated } }");

        assertThat(awaitOrder(coffeeOrder, "FULFILLED").attempts()).isEqualTo(1);
        assertThat(awaitOrder(teaOrder, "FAILED").attempts()).isEqualTo(3);
        await().atMost(TIMEOUT).until(() -> deadLetterFor(teaOrder), id -> id != null);

        // Replaying while the outage continues sends the order round the retry loop and back to the DLQ.
        graphQl.document("mutation { replayDeadLetters { replayed } }").execute()
                .path("replayDeadLetters.replayed").entity(Integer.class).isEqualTo(1);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            OrderView order = order(teaOrder);
            assertThat(order.status()).isEqualTo("FAILED");
            assertThat(order.attempts()).isEqualTo(6);
            assertThat(deadLetterFor(teaOrder)).isNotNull();
        });
    }

    private String subscribe(Plan plan) {
        return graphQl.document("""
                        mutation($input: CreateSubscriptionInput!) { createSubscription(input: $input) { id } }""")
                .variable("input", Map.of("customerId", String.valueOf(customer.getId()),
                        "planId", String.valueOf(plan.getId()), "frequency", "WEEKLY"))
                .execute()
                .path("createSubscription.id").entity(String.class).get();
    }

    private String billAndGetOrderId(String subscriptionId) {
        return orderIdFor(subscriptionId, "mutation { runBillingCycle { ordersCreated } }");
    }

    private String orderIdFor(String subscriptionId, String billingMutation) {
        graphQl.document(billingMutation).executeAndVerify();
        return graphQl.document("query($id: ID) { orders(subscriptionId: $id) { id } }")
                .variable("id", subscriptionId)
                .execute()
                .path("orders[0].id").entity(String.class).get();
    }

    private void setFailureInjection(String mode, String planCode) {
        graphQl.document("mutation($mode: FailureMode!, $plan: String) { setFailureInjection(mode: $mode, planCode: $plan) { mode } }")
                .variable("mode", mode)
                .variable("plan", planCode)
                .executeAndVerify();
    }

    private OrderView awaitOrder(String orderId, String status) {
        return await().atMost(TIMEOUT).until(() -> order(orderId), order -> order.status().equals(status));
    }

    private OrderView order(String orderId) {
        return graphQl.document("query($id: ID!) { order(id: $id) { status attempts lastError fulfilledAt } }")
                .variable("id", orderId)
                .execute()
                .path("order").entity(OrderView.class).get();
    }

    /** Message id of the dead letter for this order, or null if it is not (yet) in the DLQ. */
    private String deadLetterFor(String orderId) {
        List<DeadLetterView> deadLetters = graphQl.document("{ deadLetters { messageId orderId } }")
                .execute()
                .path("deadLetters").entityList(DeadLetterView.class).get();
        return deadLetters.stream().filter(deadLetter -> orderId.equals(deadLetter.orderId()))
                .map(DeadLetterView::messageId).findFirst().orElse(null);
    }

    private void assertQueuesEmpty() {
        await().atMost(TIMEOUT).untilAsserted(() -> graphQl.document("{ queueStats { visible inFlight deadLetters } }")
                .execute()
                .path("queueStats.visible").entity(Integer.class).isEqualTo(0)
                .path("queueStats.inFlight").entity(Integer.class).isEqualTo(0)
                .path("queueStats.deadLetters").entity(Integer.class).isEqualTo(0));
    }

    record OrderView(String status, int attempts, String lastError, String fulfilledAt) {
    }

    record DeadLetterView(String messageId, String orderId) {
    }
}
