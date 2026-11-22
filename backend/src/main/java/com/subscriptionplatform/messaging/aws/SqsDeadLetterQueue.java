package com.subscriptionplatform.messaging.aws;

import com.subscriptionplatform.messaging.DeadLetterMessage;
import com.subscriptionplatform.messaging.DeadLetterQueue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * The SQS dead-letter queue. SQS has no real "peek", so messages are received with a short visibility
 * timeout (to page past the first ten) and released straight afterwards. Replay re-sends each message to the
 * source queue and deletes it from the DLQ. Both operations are serialized within this instance so a
 * concurrent peek cannot hide messages from a replay.
 */
public class SqsDeadLetterQueue implements DeadLetterQueue {

    private static final int PEEK_VISIBILITY_SECONDS = 5;
    private static final int REDRIVE_VISIBILITY_SECONDS = 30;
    private static final int MAX_ROUNDS = 100;

    private final SqsClient sqs;
    private final String deadLetterQueueUrl;
    private final String sourceQueueUrl;

    public SqsDeadLetterQueue(SqsClient sqs, String deadLetterQueueUrl, String sourceQueueUrl) {
        this.sqs = sqs;
        this.deadLetterQueueUrl = deadLetterQueueUrl;
        this.sourceQueueUrl = sourceQueueUrl;
    }

    @Override
    public synchronized List<DeadLetterMessage> peek(int maxMessages) {
        if (approximateVisible() == 0) {
            return List.of();
        }
        Map<String, Message> seen = new LinkedHashMap<>();
        List<String> receiptHandles = new ArrayList<>();
        try {
            for (int round = 0; round < MAX_ROUNDS && seen.size() < maxMessages; round++) {
                List<Message> batch = receive(PEEK_VISIBILITY_SECONDS);
                if (batch.isEmpty()) {
                    break;
                }
                for (Message message : batch) {
                    receiptHandles.add(message.receiptHandle());
                    seen.putIfAbsent(message.messageId(), message);
                }
            }
        } finally {
            SqsMessages.release(sqs, deadLetterQueueUrl, receiptHandles);
        }
        return seen.values().stream().limit(maxMessages).map(SqsDeadLetterQueue::toDeadLetter).toList();
    }

    @Override
    public synchronized List<DeadLetterMessage> redrive(Set<String> messageIds) {
        List<DeadLetterMessage> moved = new ArrayList<>();
        List<String> notSelected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<Message> batch = receive(REDRIVE_VISIBILITY_SECONDS);
                if (batch.isEmpty()) {
                    break;
                }
                boolean progress = false;
                for (Message message : batch) {
                    if (!seen.add(message.messageId())) {
                        notSelected.add(message.receiptHandle());
                        continue;
                    }
                    progress = true;
                    if (messageIds.isEmpty() || messageIds.contains(message.messageId())) {
                        sqs.sendMessage(request -> request
                                .queueUrl(sourceQueueUrl)
                                .messageBody(message.body())
                                .messageAttributes(message.messageAttributes()));
                        sqs.deleteMessage(request -> request
                                .queueUrl(deadLetterQueueUrl)
                                .receiptHandle(message.receiptHandle()));
                        moved.add(toDeadLetter(message));
                    } else {
                        notSelected.add(message.receiptHandle());
                    }
                }
                if (!progress || (!messageIds.isEmpty() && moved.size() >= messageIds.size())) {
                    break;
                }
            }
        } finally {
            SqsMessages.release(sqs, deadLetterQueueUrl, notSelected);
        }
        return moved;
    }

    @Override
    public long size() {
        Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(request -> request
                        .queueUrl(deadLetterQueueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                .attributes();
        return SqsMessageQueue.parse(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + SqsMessageQueue.parse(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    private long approximateVisible() {
        return SqsMessageQueue.parse(sqs.getQueueAttributes(request -> request
                        .queueUrl(deadLetterQueueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .attributes()
                .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
    }

    private List<Message> receive(int visibilityTimeoutSeconds) {
        // A 1s long poll makes SQS query all of its servers instead of a sample.
        return sqs.receiveMessage(SqsMessages.receiveRequest(deadLetterQueueUrl, SqsMessages.MAX_BATCH, 1,
                visibilityTimeoutSeconds)).messages();
    }

    private static DeadLetterMessage toDeadLetter(Message message) {
        return new DeadLetterMessage(message.messageId(), message.body(), SqsMessages.stringAttributes(message),
                SqsMessages.sentAt(message));
    }
}
