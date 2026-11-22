package com.subscriptionplatform.graphql;

import static com.subscriptionplatform.support.TestClockConfiguration.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.support.MutableClock;
import com.subscriptionplatform.support.TestClockConfiguration;
import com.subscriptionplatform.support.TestData;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;

/**
 * The GraphQL API over HTTP. The queue consumer is off, so orders stay PENDING and their OrderCreated
 * messages wait in the (in-memory) queue, which keeps every assertion deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.messaging.consumer.enabled=false")
@AutoConfigureHttpGraphQlTester
@ActiveProfiles("test")
@Import({TestData.class, TestClockConfiguration.class})
class GraphQlApiIT {

    private static final String CREATE = """
            mutation Create($input: CreateSubscriptionInput!) {
              createSubscription(input: $input) {
                id status quantity frequency nextRunDate anchorDate recurringAmount cancelledAt
                customer { name email }
                plan { code }
              }
            }""";

    @Autowired
    private HttpGraphQlTester graphQl;
    @Autowired
    private TestData data;
    @Autowired
    private MutableClock clock;

    private Plan coffee;
    private Customer ava;

    @BeforeEach
    void setUp() {
        clock.set(TestClockConfiguration.START);
        data.reset();
        coffee = data.plan("COFFEE", "16.50");
        data.plan("TEA", "22.00");
        ava = data.customer("Ava Patel");
    }

    @Test
    void listsPlansByName() {
        graphQl.document("{ plans { code unitPrice currency active } }")
                .execute()
                .path("plans[*].code").entityList(String.class).containsExactly("COFFEE", "TEA")
                .path("plans[0].unitPrice").entity(String.class).isEqualTo("16.50")
                .path("plans[0].active").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void createsASubscriptionForAnExistingCustomerStartingToday() {
        GraphQlTester.Response response = graphQl.document(CREATE)
                .variable("input", Map.of("customerId", id(ava), "planId", id(coffee), "quantity", 2,
                        "frequency", "WEEKLY"))
                .execute();

        response.path("createSubscription.status").entity(String.class).isEqualTo("ACTIVE")
                .path("createSubscription.frequency").entity(String.class).isEqualTo("WEEKLY")
                .path("createSubscription.nextRunDate").entity(String.class).isEqualTo(TODAY.toString())
                .path("createSubscription.anchorDate").entity(String.class).isEqualTo(TODAY.toString())
                .path("createSubscription.recurringAmount").entity(String.class).isEqualTo("33.00")
                .path("createSubscription.customer.name").entity(String.class).isEqualTo("Ava Patel")
                .path("createSubscription.plan.code").entity(String.class).isEqualTo("COFFEE")
                .path("createSubscription.cancelledAt").valueIsNull();
    }

    @Test
    void createsTheCustomerInlineAndHonoursTheStartDate() {
        graphQl.document(CREATE)
                .variable("input", Map.of(
                        "newCustomer", Map.of("name", "Zoe Kim", "email", "Zoe.Kim@Example.com"),
                        "planId", id(coffee), "frequency", "MONTHLY", "startDate", TODAY.plusDays(5).toString()))
                .execute()
                .path("createSubscription.customer.email").entity(String.class).isEqualTo("zoe.kim@example.com")
                .path("createSubscription.quantity").entity(Integer.class).isEqualTo(1)
                .path("createSubscription.nextRunDate").entity(String.class).isEqualTo(TODAY.plusDays(5).toString());

        graphQl.document("{ customers { email } }")
                .execute()
                .path("customers[*].email").entityList(String.class).contains("zoe.kim@example.com");
    }

    @Test
    void invalidInputIsABadRequestListingEveryViolation() {
        graphQl.document(CREATE)
                .variable("input", Map.of(
                        "newCustomer", Map.of("name", "", "email", "not-an-email"),
                        "planId", id(coffee), "quantity", 0, "frequency", "WEEKLY"))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).singleElement().satisfies(error -> {
                    assertThat(classification(error)).isEqualTo("BAD_REQUEST");
                    assertThat(error.getMessage()).contains("input.quantity", "input.newCustomer.email",
                            "input.newCustomer.name");
                }))
                .path("createSubscription").pathDoesNotExist();
    }

    @Test
    void domainRuleViolationsAndUnknownReferencesAreReportedAsSuch() {
        assertSingleError(graphQl.document(CREATE)
                        .variable("input", Map.of("customerId", id(ava), "planId", "999", "frequency", "WEEKLY"))
                        .execute(),
                "NOT_FOUND", "Plan 999 not found");
        assertSingleError(graphQl.document(CREATE)
                        .variable("input", Map.of("customerId", id(ava), "planId", id(coffee), "frequency", "WEEKLY",
                                "startDate", TODAY.minusDays(1).toString()))
                        .execute(),
                "BAD_REQUEST", "startDate cannot be in the past");
        assertSingleError(graphQl.document(CREATE)
                        .variable("input", Map.of("planId", id(coffee), "frequency", "WEEKLY"))
                        .execute(),
                "BAD_REQUEST", "exactly one of customerId or newCustomer");
    }

    @Test
    void pauseResumeAndCancelFollowTheSubscriptionLifecycle() {
        Subscription subscription = data.subscription(ava, coffee, 1, Frequency.WEEKLY, TODAY);
        String id = id(subscription);

        transition("pauseSubscription", id).path("pauseSubscription.status").entity(String.class).isEqualTo("PAUSED");
        transition("resumeSubscription", id).path("resumeSubscription.status").entity(String.class).isEqualTo("ACTIVE");
        transition("cancelSubscription", id)
                .path("cancelSubscription.status").entity(String.class).isEqualTo("CANCELLED")
                .path("cancelSubscription.cancelledAt").hasValue();

        assertSingleError(transition("resumeSubscription", id), "BAD_REQUEST", "cancelled and cannot be resumed");
        assertSingleError(transition("pauseSubscription", "12345"), "NOT_FOUND", "Subscription 12345 not found");
    }

    @Test
    void subscriptionsCanBeFilteredByStatusAndLoadedById() {
        Subscription active = data.subscription(ava, coffee, 1, Frequency.WEEKLY, TODAY);
        Subscription paused = data.subscription(ava, coffee, 3, Frequency.BIWEEKLY, TODAY);
        transition("pauseSubscription", id(paused));

        graphQl.document("{ subscriptions { id } }").execute()
                .path("subscriptions[*].id").entityList(String.class).containsExactly(id(paused), id(active));
        graphQl.document("{ subscriptions(status: PAUSED) { id quantity } }").execute()
                .path("subscriptions[*].id").entityList(String.class).containsExactly(id(paused));
        graphQl.document("query($id: ID!) { subscription(id: $id) { id frequency orders { id } } }")
                .variable("id", id(active))
                .execute()
                .path("subscription.frequency").entity(String.class).isEqualTo("WEEKLY")
                .path("subscription.orders").entityList(Object.class).hasSize(0);
        graphQl.document("{ subscription(id: 98765) { id } }").execute()
                .path("subscription").valueIsNull();
    }

    @Test
    void runBillingCycleIsIdempotentPerSubscriptionAndRunDate() {
        Subscription subscription = data.subscription(ava, coffee, 2, Frequency.WEEKLY, TODAY);
        String run = """
                mutation { runBillingCycle {
                  asOf dueSubscriptions ordersCreated
                  orders { status runDate amount attempts subscription { id nextRunDate } }
                } }""";

        graphQl.document(run).execute()
                .path("runBillingCycle.asOf").entity(String.class).isEqualTo(TODAY.toString())
                .path("runBillingCycle.ordersCreated").entity(Integer.class).isEqualTo(1)
                .path("runBillingCycle.orders[0].status").entity(String.class).isEqualTo("PENDING")
                .path("runBillingCycle.orders[0].runDate").entity(String.class).isEqualTo(TODAY.toString())
                .path("runBillingCycle.orders[0].amount").entity(String.class).isEqualTo("33.00")
                .path("runBillingCycle.orders[0].attempts").entity(Integer.class).isEqualTo(0)
                .path("runBillingCycle.orders[0].subscription.nextRunDate").entity(String.class)
                .isEqualTo(TODAY.plusWeeks(1).toString());

        graphQl.document(run).execute()
                .path("runBillingCycle.dueSubscriptions").entity(Integer.class).isEqualTo(0)
                .path("runBillingCycle.ordersCreated").entity(Integer.class).isEqualTo(0);

        graphQl.document("query($id: ID) { orders(subscriptionId: $id) { runDate status } queueStats { visible } }")
                .variable("id", id(subscription))
                .execute()
                .path("orders[*].runDate").entityList(String.class).containsExactly(TODAY.toString())
                .path("queueStats.visible").entity(Integer.class).isEqualTo(1);
    }

    @Test
    void runBillingCycleForALaterDateBillsEveryCycleUpToIt() {
        data.subscription(ava, coffee, 1, Frequency.WEEKLY, TODAY);

        graphQl.document("mutation($asOf: Date) { runBillingCycle(asOf: $asOf) { ordersCreated orders { runDate } } }")
                .variable("asOf", TODAY.plusWeeks(2).toString())
                .execute()
                .path("runBillingCycle.ordersCreated").entity(Integer.class).isEqualTo(3)
                .path("runBillingCycle.orders[*].runDate").entityList(String.class).containsExactly(
                        TODAY.toString(), TODAY.plusWeeks(1).toString(), TODAY.plusWeeks(2).toString());
    }

    @Test
    void ordersCanBeFilteredAndLoadedById() {
        Subscription coffeeSubscription = data.subscription(ava, coffee, 1, Frequency.WEEKLY, TODAY);
        data.subscription(data.customer("Liam Chen"), coffee, 4, Frequency.MONTHLY, TODAY);
        graphQl.document("mutation { runBillingCycle { ordersCreated } }").execute()
                .path("runBillingCycle.ordersCreated").entity(Integer.class).isEqualTo(2);

        graphQl.document("{ orders(status: PENDING) { id } fulfilled: orders(status: FULFILLED) { id } }").execute()
                .path("orders").entityList(Object.class).hasSize(2)
                .path("fulfilled").entityList(Object.class).hasSize(0);
        String orderId = graphQl.document("query($id: ID) { orders(subscriptionId: $id) { id } }")
                .variable("id", id(coffeeSubscription))
                .execute()
                .path("orders[0].id").entity(String.class).get();

        graphQl.document("""
                        query($id: ID!) { order(id: $id) {
                          id status amount currency quantity unitPrice attempts lastError
                          subscription { id customer { name } plan { code } }
                        } }""")
                .variable("id", orderId)
                .execute()
                .path("order.status").entity(String.class).isEqualTo("PENDING")
                .path("order.amount").entity(String.class).isEqualTo("16.50")
                .path("order.currency").entity(String.class).isEqualTo("USD")
                .path("order.subscription.id").entity(String.class).isEqualTo(id(coffeeSubscription))
                .path("order.subscription.customer.name").entity(String.class).isEqualTo("Ava Patel")
                .path("order.lastError").valueIsNull();
        graphQl.document("{ order(id: 424242) { id } }").execute().path("order").valueIsNull();
    }

    @Test
    void exposesTheDeadLetterQueueAndQueueStatistics() {
        graphQl.document("""
                        { deadLetters { messageId }
                          queueStats { provider visible inFlight deadLetters maxReceiveCount } }""")
                .execute()
                .path("deadLetters").entityList(Object.class).hasSize(0)
                .path("queueStats.provider").entity(String.class).isEqualTo("in-memory")
                .path("queueStats.maxReceiveCount").entity(Integer.class).isEqualTo(3)
                .path("queueStats.deadLetters").entity(Integer.class).isEqualTo(0);

        graphQl.document("mutation { replayDeadLetters { replayed messageIds } }").execute()
                .path("replayDeadLetters.replayed").entity(Integer.class).isEqualTo(0)
                .path("replayDeadLetters.messageIds").entityList(String.class).hasSize(0);
    }

    @Test
    void failureInjectionCanBeChangedAtRuntime() {
        graphQl.document("mutation { setFailureInjection(mode: PERMANENT, planCode: \"TEA\") { mode planCode } }")
                .execute()
                .path("setFailureInjection.mode").entity(String.class).isEqualTo("PERMANENT")
                .path("setFailureInjection.planCode").entity(String.class).isEqualTo("TEA");
        graphQl.document("{ failureInjection { mode planCode } }").execute()
                .path("failureInjection.mode").entity(String.class).isEqualTo("PERMANENT");

        assertSingleError(graphQl.document("mutation { setFailureInjection(mode: NONE, planCode: \"NOPE\") { mode } }")
                .execute(), "NOT_FOUND", "Plan NOPE not found");
    }

    private GraphQlTester.Response transition(String mutation, String id) {
        return graphQl.document("mutation($id: ID!) { " + mutation + "(id: $id) { id status cancelledAt } }")
                .variable("id", id)
                .execute();
    }

    private static void assertSingleError(GraphQlTester.Response response, String classification, String message) {
        response.errors().satisfy(errors -> assertThat(errors).singleElement().satisfies(error -> {
            assertThat(classification(error)).isEqualTo(classification);
            assertThat(error.getMessage()).contains(message);
        }));
    }

    private static Object classification(ResponseError error) {
        return error.getExtensions().get("classification");
    }

    private static String id(Plan plan) {
        return String.valueOf(plan.getId());
    }

    private static String id(Customer customer) {
        return String.valueOf(customer.getId());
    }

    private static String id(Subscription subscription) {
        return String.valueOf(subscription.getId());
    }
}
