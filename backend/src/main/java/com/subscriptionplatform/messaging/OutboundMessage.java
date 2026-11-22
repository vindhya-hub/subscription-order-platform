package com.subscriptionplatform.messaging;

import java.util.Map;

/** A message to publish: a body plus string message attributes. */
public record OutboundMessage(String body, Map<String, String> attributes) {

    public OutboundMessage {
        attributes = Map.copyOf(attributes);
    }
}
