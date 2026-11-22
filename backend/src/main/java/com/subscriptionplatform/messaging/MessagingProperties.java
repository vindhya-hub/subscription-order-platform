package com.subscriptionplatform.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.messaging.*}: which broker to use and how the queue consumer behaves.
 *
 * @param provider          {@code in-memory} (default) or {@code aws}
 * @param maxReceiveCount   redrive threshold for the in-memory queue; the AWS adapter reads it from the
 *                          queue's RedrivePolicy and only falls back to this value
 * @param visibilityTimeout how long a received message stays hidden (in-memory queue)
 * @param retryBackoff      base delay before a failed message becomes visible again; multiplied by the
 *                          receive count for a linear back-off
 */
@ConfigurationProperties("app.messaging")
public record MessagingProperties(
        @DefaultValue("in-memory") Provider provider,
        @DefaultValue("3") int maxReceiveCount,
        @DefaultValue("30s") Duration visibilityTimeout,
        @DefaultValue("2s") Duration retryBackoff,
        @DefaultValue Consumer consumer,
        @DefaultValue Aws aws) {

    public enum Provider {
        IN_MEMORY,
        AWS;

        public String label() {
            return this == AWS ? "aws" : "in-memory";
        }
    }

    /**
     * @param enabled   run the background poller (disable to run an API-only instance)
     * @param batchSize messages per receive call (SQS caps this at 10)
     * @param waitTime  long-poll wait per receive call
     */
    public record Consumer(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10") int batchSize,
            @DefaultValue("5s") Duration waitTime) {
    }

    /**
     * @param endpoint            optional endpoint override, e.g. {@code http://localhost:4566} for LocalStack
     * @param topicArn            optional; resolved from {@code topicName} when blank
     * @param topicName           SNS topic for order events
     * @param queueName           SQS queue subscribed to the topic
     * @param deadLetterQueueName SQS queue targeted by the redrive policy
     */
    public record Aws(
            @DefaultValue("us-east-1") String region,
            String endpoint,
            String topicArn,
            @DefaultValue("order-events") String topicName,
            @DefaultValue("order-processing") String queueName,
            @DefaultValue("order-processing-dlq") String deadLetterQueueName) {
    }
}
