package com.subscriptionplatform.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.subscriptionplatform.support.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEventCodecTest {

    private final ObjectMapper mapper = Json.mapper();
    private final OrderEventCodec codec = new OrderEventCodec(mapper);
    private final OrderCreatedEvent event = new OrderCreatedEvent(UUID.randomUUID(), OrderCreatedEvent.TYPE,
            Instant.parse("2026-03-02T06:00:00Z"), 42, 7, 3, "COFFEE", LocalDate.of(2026, 3, 2), 2,
            new BigDecimal("33.00"), "USD");

    @Test
    void roundTripsARawEvent() {
        String json = codec.encode(event);

        assertThat(json).contains("\"runDate\":\"2026-03-02\"", "\"occurredAt\":\"2026-03-02T06:00:00Z\"",
                "\"amount\":\"33.00\"");
        assertThat(codec.decode(json)).isEqualTo(event);
        assertThat(codec.decode(json).amount()).hasToString("33.00");
    }

    @Test
    void unwrapsAnSnsNotificationEnvelope() {
        // What SQS receives from an SNS subscription without raw message delivery.
        ObjectNode envelope = mapper.createObjectNode()
                .put("Type", "Notification")
                .put("MessageId", UUID.randomUUID().toString())
                .put("TopicArn", "arn:aws:sns:us-east-1:000000000000:order-events")
                .put("Message", codec.encode(event));

        assertThat(codec.decode(envelope.toString())).isEqualTo(event);
    }

    @Test
    void rejectsBodiesThatAreNotOrderEvents() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode("not json"));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode("{\"hello\":\"world\"}"));
    }
}
