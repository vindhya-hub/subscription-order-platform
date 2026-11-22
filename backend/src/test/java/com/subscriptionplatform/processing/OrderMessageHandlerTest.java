package com.subscriptionplatform.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.subscriptionplatform.common.NotFoundException;
import com.subscriptionplatform.events.OrderCreatedEvent;
import com.subscriptionplatform.events.OrderEventCodec;
import com.subscriptionplatform.messaging.ReceivedMessage;
import com.subscriptionplatform.order.OrderService;
import com.subscriptionplatform.order.OrderSnapshot;
import com.subscriptionplatform.processing.OrderMessageHandler.Disposition;
import com.subscriptionplatform.support.Json;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** What happens to an OrderCreated message on each delivery, given the outcome of processing. */
@ExtendWith(MockitoExtension.class)
class OrderMessageHandlerTest {

    private static final int MAX_RECEIVES = 3;
    private static final long ORDER_ID = 42;

    @Mock
    private OrderService orders;

    private final OrderEventCodec codec = Json.codec();
    private final FailureInjector failureInjector = new FailureInjector(
            new ProcessingProperties(Duration.ZERO, new ProcessingProperties.FailureInjection(FailureMode.NONE, null)));
    private OrderMessageHandler handler;

    @BeforeEach
    void setUp() {
        FulfillmentSimulator simulator = new FulfillmentSimulator(failureInjector,
                new ProcessingProperties(Duration.ZERO, new ProcessingProperties.FailureInjection(FailureMode.NONE, null)));
        handler = new OrderMessageHandler(codec, orders, simulator);
    }

    @Test
    void successfulProcessingFulfilsTheOrderAndAcknowledgesTheMessage() {
        startsAttempt();

        assertThat(handler.handle(delivery(1), MAX_RECEIVES)).isEqualTo(Disposition.ACKNOWLEDGE);

        verify(orders).markFulfilled(ORDER_ID);
    }

    @Test
    void aFailureWithReceivesLeftIsRetried() {
        startsAttempt();
        failureInjector.update(FailureMode.PERMANENT, null);

        assertThat(handler.handle(delivery(2), MAX_RECEIVES)).isEqualTo(Disposition.RETRY);

        verify(orders).markRetryPending(anyLong(), contains("delivery 2"));
        verify(orders, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void aFailureOnTheLastAllowedReceiveFailsTheOrderAndDeadLettersTheMessage() {
        startsAttempt();
        failureInjector.update(FailureMode.PERMANENT, null);

        assertThat(handler.handle(delivery(3), MAX_RECEIVES)).isEqualTo(Disposition.DEAD_LETTER);

        verify(orders).markFailed(anyLong(), contains("delivery 3"));
        verify(orders, never()).markFulfilled(anyLong());
    }

    @Test
    void transientFailuresSucceedOnRedelivery() {
        startsAttempt();
        failureInjector.update(FailureMode.TRANSIENT, null);

        assertThat(handler.handle(delivery(1), MAX_RECEIVES)).isEqualTo(Disposition.RETRY);
        assertThat(handler.handle(delivery(2), MAX_RECEIVES)).isEqualTo(Disposition.ACKNOWLEDGE);

        verify(orders).markFulfilled(ORDER_ID);
    }

    @Test
    void aDuplicateDeliveryOfAFulfilledOrderIsAcknowledgedWithoutReprocessing() {
        when(orders.startAttempt(ORDER_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(delivery(2), MAX_RECEIVES)).isEqualTo(Disposition.ACKNOWLEDGE);

        verify(orders, never()).markFulfilled(anyLong());
    }

    @Test
    void unreadableMessagesAreRetriedThenDeadLettered() {
        ReceivedMessage garbage = message("not json", 1);
        ReceivedMessage garbageLastTime = message("not json", 3);

        assertThat(handler.handle(garbage, MAX_RECEIVES)).isEqualTo(Disposition.RETRY);
        assertThat(handler.handle(garbageLastTime, MAX_RECEIVES)).isEqualTo(Disposition.DEAD_LETTER);
    }

    @Test
    void messagesForUnknownOrdersAreRetriedThenDeadLettered() {
        when(orders.startAttempt(ORDER_ID)).thenThrow(new NotFoundException("Order", ORDER_ID));

        assertThat(handler.handle(delivery(1), MAX_RECEIVES)).isEqualTo(Disposition.RETRY);
        assertThat(handler.handle(delivery(3), MAX_RECEIVES)).isEqualTo(Disposition.DEAD_LETTER);
    }

    private void startsAttempt() {
        when(orders.startAttempt(ORDER_ID)).thenReturn(Optional.of(
                new OrderSnapshot(ORDER_ID, 7, "COFFEE", 2, new BigDecimal("33.00"), "USD", 1)));
    }

    private ReceivedMessage delivery(int receiveCount) {
        OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), OrderCreatedEvent.TYPE,
                Instant.parse("2026-03-02T06:00:00Z"), ORDER_ID, 7, 1, "COFFEE", LocalDate.of(2026, 3, 2), 2,
                new BigDecimal("33.00"), "USD");
        return message(codec.encode(event), receiveCount);
    }

    private static ReceivedMessage message(String body, int receiveCount) {
        return new ReceivedMessage("message-1", "receipt-" + receiveCount, body, Map.of(), receiveCount,
                Instant.parse("2026-03-02T06:00:01Z"));
    }
}
