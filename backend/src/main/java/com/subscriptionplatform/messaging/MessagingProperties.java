package com.subscriptionplatform.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.messaging.*}: which broker to use and how the queue consumer behaves. Values come from
 * {@code application.yml}, which maps each one to an environment variable.
 *
 * @param provider          {@code in-memory} or {@code aws}
 * @param maxReceiveCount   redrive threshold for the in-memory queue; the AWS adapter uses the queue's
 *                          RedrivePolicy instead
 * @param visibilityTimeout how long a received message stays hidden (in-memory queue)
 * @param retryBackoff      base delay before a failed message becomes visible again; multiplied by the
 *                          receive count for a linear back-off
 */
@ConfigurationProperties("app.messaging")
public record MessagingProperties(
        Provider provider,
        int maxReceiveCount,
        Duration visibilityTimeout,
        Duration retryBackoff,
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
    public record Consumer(boolean enabled, int batchSize, Duration waitTime) {
    }

    /**
     * SNS/SQS resources for {@code provider=aws}. Each resource is identified either directly (ARN / URL) or by
     * name, which is resolved once at startup. Credentials are deliberately not configurable here: the AWS SDK's
     * default provider chain supplies them.
     *
     * @param endpoint optional endpoint override, e.g. {@code http://localhost:4566} for LocalStack
     */
    public record Aws(
            String region,
            String endpoint,
            String topicArn,
            String topicName,
            String queueUrl,
            String queueName,
            String deadLetterQueueUrl,
            String deadLetterQueueName) {
    }
}
