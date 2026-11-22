package com.subscriptionplatform.messaging.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionplatform.messaging.MessagingProperties;
import java.time.Duration;
import java.util.Map;
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
 *
 * <p>The order queue must have a RedrivePolicy that targets the configured dead-letter queue: without it,
 * failed messages would never reach the DLQ, so startup fails instead of running with broken semantics.
 */
public record AwsMessagingResources(String topicArn, String queueUrl, String deadLetterQueueUrl,
                                    int maxReceiveCount) {

    private static final Logger log = LoggerFactory.getLogger(AwsMessagingResources.class);
    private static final int MAX_ATTEMPTS = 15;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    /**
     * Resolves the resources, retrying for a while so the application can start alongside LocalStack while its
     * init script is still creating them.
     *
     * @param configuredMaxReceiveCount {@code app.messaging.max-receive-count}; only compared with the queue's
     *                                  RedrivePolicy, which is authoritative
     */
    static AwsMessagingResources resolve(SnsClient sns, SqsClient sqs, MessagingProperties.Aws config,
                                         int configuredMaxReceiveCount, ObjectMapper objectMapper) {
        return resolve(sns, sqs, config, configuredMaxReceiveCount, objectMapper, MAX_ATTEMPTS, RETRY_DELAY);
    }

    static AwsMessagingResources resolve(SnsClient sns, SqsClient sqs, MessagingProperties.Aws config,
                                         int configuredMaxReceiveCount, ObjectMapper objectMapper,
                                         int maxAttempts, Duration retryDelay) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                AwsMessagingResources resources = lookUp(sns, sqs, config, objectMapper);
                if (resources.maxReceiveCount() != configuredMaxReceiveCount) {
                    log.warn("Queue RedrivePolicy has maxReceiveCount={} but app.messaging.max-receive-count={}; "
                            + "using the queue's value", resources.maxReceiveCount(), configuredMaxReceiveCount);
                }
                log.info("AWS messaging ready: topic={}, queue={}, dlq={}, maxReceiveCount={}",
                        resources.topicArn(), resources.queueUrl(), resources.deadLetterQueueUrl(),
                        resources.maxReceiveCount());
                return resources;
            } catch (SdkException | IllegalStateException e) {
                lastFailure = e;
                log.warn("SNS/SQS resources not ready (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleep(retryDelay);
                }
            }
        }
        throw new IllegalStateException("Could not resolve the SNS topic and SQS queues (did localstack/init-aws.sh "
                + "or your infrastructure code create them?): "
                + (lastFailure != null ? lastFailure.getMessage() : "no attempts made"), lastFailure);
    }

    private static AwsMessagingResources lookUp(SnsClient sns, SqsClient sqs, MessagingProperties.Aws config,
                                                ObjectMapper objectMapper) {
        String topicArn = StringUtils.hasText(config.topicArn())
                ? config.topicArn()
                : findTopicArn(sns, config.topicName());
        String queueUrl = StringUtils.hasText(config.queueUrl())
                ? config.queueUrl()
                : sqs.getQueueUrl(request -> request.queueName(config.queueName())).queueUrl();
        String dlqUrl = StringUtils.hasText(config.deadLetterQueueUrl())
                ? config.deadLetterQueueUrl()
                : sqs.getQueueUrl(request -> request.queueName(config.deadLetterQueueName())).queueUrl();

        RedrivePolicy policy = readRedrivePolicy(sqs, queueUrl, objectMapper);
        String dlqArn = sqs.getQueueAttributes(request -> request.queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
        if (dlqArn != null && !dlqArn.equals(policy.deadLetterTargetArn())) {
            throw new IllegalStateException("The RedrivePolicy of " + queueUrl + " targets "
                    + policy.deadLetterTargetArn() + ", not the configured dead-letter queue " + dlqArn);
        }
        return new AwsMessagingResources(topicArn, queueUrl, dlqUrl, policy.maxReceiveCount());
    }

    private static String findTopicArn(SnsClient sns, String topicName) {
        return sns.listTopicsPaginator().topics().stream()
                .map(Topic::topicArn)
                .filter(arn -> arn.endsWith(":" + topicName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SNS topic '" + topicName + "' does not exist"));
    }

    private record RedrivePolicy(String deadLetterTargetArn, int maxReceiveCount) {
    }

    private static RedrivePolicy readRedrivePolicy(SqsClient sqs, String queueUrl, ObjectMapper objectMapper) {
        Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.REDRIVE_POLICY)).attributes();
        String policy = attributes.get(QueueAttributeName.REDRIVE_POLICY);
        if (!StringUtils.hasText(policy)) {
            throw new IllegalStateException("Queue " + queueUrl + " has no RedrivePolicy, so failed messages would "
                    + "never reach a dead-letter queue");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(policy);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable RedrivePolicy on " + queueUrl + ": " + policy, e);
        }
        JsonNode count = node.path("maxReceiveCount");
        // AWS returns the count as a number; some emulators return it as a string.
        int maxReceiveCount = count.isTextual() ? parsePositive(count.asText()) : count.asInt();
        String target = node.path("deadLetterTargetArn").asText(null);
        if (maxReceiveCount < 1 || !StringUtils.hasText(target)) {
            throw new IllegalStateException("Incomplete RedrivePolicy on " + queueUrl + ": " + policy);
        }
        return new RedrivePolicy(target, maxReceiveCount);
    }

    private static int parsePositive(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SNS/SQS", e);
        }
    }
}
