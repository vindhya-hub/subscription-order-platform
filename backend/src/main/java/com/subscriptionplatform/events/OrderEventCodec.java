package com.subscriptionplatform.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** JSON (de)serialization of order events, tolerant of SNS envelopes. */
@Component
public class OrderEventCodec {

    private final ObjectMapper objectMapper;

    public OrderEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + event, e);
        }
    }

    /**
     * Reads an event from a queue message body. With raw message delivery the body is the event itself;
     * without it, SQS receives the SNS notification envelope and the event is its {@code Message} field.
     *
     * @throws IllegalArgumentException if the body is not a readable order event
     */
    public OrderCreatedEvent decode(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.path("Type").asText().equals("Notification") && node.hasNonNull("Message")) {
                node = objectMapper.readTree(node.get("Message").asText());
            }
            OrderCreatedEvent event = objectMapper.treeToValue(node, OrderCreatedEvent.class);
            if (event.orderId() <= 0) {
                throw new IllegalArgumentException("Message has no orderId");
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a valid order event: " + e.getOriginalMessage(), e);
        }
    }
}
