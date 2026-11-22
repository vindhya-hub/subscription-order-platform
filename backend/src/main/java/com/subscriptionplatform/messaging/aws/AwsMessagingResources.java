package com.subscriptionplatform.messaging.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionplatform.messaging.MessagingProperties;
import java.time.Duration;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Topic ARN, queue URLs and redrive threshold, looked up once at startup. The application never creates
 * infrastructure itself; that is the job of {@code localstack/init-aws.sh} (or Terraform/CDK in AWS).
 */
public record AwsMessagingResources(String topicArn, String queueUrl, String deadLetterQueueUrl,
                                    int maxReceiveCount) {

    private static final Logger log = LoggerFactory.getLogger(AwsMessagingResources.class);
    private static final int MAX_ATTEMPTS = 15;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    static AwsMessagingResources resolve(SnsClient sns, SqsClient sqs, MessagingProperties.Aws config,
                                         int fallbackMaxReceiveCount, ObjectMapper objectMapper) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String topicArn = StringUtils.hasText(config.topicArn())
                        ? config.topicArn()
                        : findTopicArn(sns, config.topicName());
                String queueUrl = sqs.getQueueUrl(request -> request.queueName(config.queueName())).queueUrl();
                String dlqUrl = sqs.getQueueUrl(request -> request.queueName(config.deadLetterQueueName())).queueUrl();
                int maxReceiveCount = readMaxReceiveCount(sqs, queueUrl, objectMapper).orElseGet(() -> {
                    log.warn("Queue {} has no RedrivePolicy; assuming maxReceiveCount={}", queueUrl, fallbackMaxReceiveCount);
                    return fallbackMaxReceiveCount;
                });
                log.info("AWS messaging ready: topic={}, queue={}, dlq={}, maxReceiveCount={}",
                        topicArn, queueUrl, dlqUrl, maxReceiveCount);
                return new AwsMessagingResources(topicArn, queueUrl, dlqUrl, maxReceiveCount);
            } catch (SdkException | IllegalStateException e) {
                lastFailure = e;
                log.warn("SNS/SQS resources not available yet (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, e.getMessage());
                sleep();
            }
        }
        throw new IllegalStateException("Could not resolve SNS topic / SQS queues. Did the init script run?", lastFailure);
    }

    private static String findTopicArn(SnsClient sns, String topicName) {
        return sns.listTopicsPaginator().topics().stream()
                .map(Topic::topicArn)
                .filter(arn -> arn.endsWith(":" + topicName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SNS topic '" + topicName + "' does not exist"));
    }

    private static OptionalInt readMaxReceiveCount(SqsClient sqs, String queueUrl, ObjectMapper objectMapper) {
        String policy = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.REDRIVE_POLICY))
                .attributes()
                .get(QueueAttributeName.REDRIVE_POLICY);
        if (!StringUtils.hasText(policy)) {
            return OptionalInt.empty();
        }
        try {
            JsonNode maxReceiveCount = objectMapper.readTree(policy).path("maxReceiveCount");
            // AWS returns the value as a number, some emulators as a string.
            int value = maxReceiveCount.isTextual() ? Integer.parseInt(maxReceiveCount.asText()) : maxReceiveCount.asInt();
            return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (Exception e) {
            log.warn("Could not parse RedrivePolicy '{}': {}", policy, e.getMessage());
            return OptionalInt.empty();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SNS/SQS", e);
        }
    }
}
