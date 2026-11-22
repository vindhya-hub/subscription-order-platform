package com.subscriptionplatform.messaging.aws;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/** Small helpers shared by the SQS adapters. */
final class SqsMessages {

    static final int MAX_BATCH = 10;
    static final int MAX_WAIT_SECONDS = 20;
    static final int MAX_VISIBILITY_SECONDS = 43_200;

    private SqsMessages() {
    }

    /**
     * Builds a receive request that asks for the receive count and send time. Both the current
     * {@code MessageSystemAttributeNames} and the deprecated {@code AttributeNames} parameter are set so
     * that older emulators return the attributes too.
     */
    @SuppressWarnings("deprecation")
    static ReceiveMessageRequest receiveRequest(String queueUrl, int maxMessages, int waitSeconds,
                                                Integer visibilityTimeoutSeconds) {
        ReceiveMessageRequest.Builder builder = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(clamp(maxMessages, 1, MAX_BATCH))
                .waitTimeSeconds(clamp(waitSeconds, 0, MAX_WAIT_SECONDS))
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                        MessageSystemAttributeName.SENT_TIMESTAMP)
                .attributeNamesWithStrings("ApproximateReceiveCount", "SentTimestamp")
                .messageAttributeNames("All");
        if (visibilityTimeoutSeconds != null) {
            builder.visibilityTimeout(visibilityTimeoutSeconds);
        }
        return builder.build();
    }

    static int receiveCount(Message message) {
        String value = message.attributesAsStrings().get("ApproximateReceiveCount");
        try {
            return value == null ? 1 : Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static Instant sentAt(Message message) {
        String value = message.attributesAsStrings().get("SentTimestamp");
        try {
            return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Map<String, String> stringAttributes(Message message) {
        Map<String, String> attributes = new LinkedHashMap<>();
        message.messageAttributes().forEach((name, value) -> {
            if (value.stringValue() != null) {
                attributes.put(name, value.stringValue());
            }
        });
        return attributes;
    }

    static int toVisibilitySeconds(Duration delay) {
        return (int) Math.min(toWholeSeconds(delay), MAX_VISIBILITY_SECONDS);
    }

    /** Rounds up to whole seconds, the unit SQS uses for wait times and visibility timeouts. */
    static int toWholeSeconds(Duration duration) {
        long seconds = (Math.max(0, duration.toMillis()) + 999) / 1000;
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    /** Makes the given messages visible again immediately (batches of ten). */
    static void release(SqsClient sqs, String queueUrl, List<String> receiptHandles) {
        for (int start = 0; start < receiptHandles.size(); start += MAX_BATCH) {
            List<String> chunk = receiptHandles.subList(start, Math.min(start + MAX_BATCH, receiptHandles.size()));
            List<ChangeMessageVisibilityBatchRequestEntry> entries = new ArrayList<>();
            for (int i = 0; i < chunk.size(); i++) {
                entries.add(ChangeMessageVisibilityBatchRequestEntry.builder()
                        .id("m" + i)
                        .receiptHandle(chunk.get(i))
                        .visibilityTimeout(0)
                        .build());
            }
            sqs.changeMessageVisibilityBatch(request -> request.queueUrl(queueUrl).entries(entries));
        }
    }

    static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }
}
