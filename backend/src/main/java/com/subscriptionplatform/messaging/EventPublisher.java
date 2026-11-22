package com.subscriptionplatform.messaging;

/** Publishes to the order-events topic (an SNS topic in AWS). */
public interface EventPublisher {

    /**
     * Publishes the message to every subscriber of the topic.
     *
     * @return the broker-assigned message id
     */
    String publish(OutboundMessage message);
}
