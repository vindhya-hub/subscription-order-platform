package com.subscriptionplatform.messaging.aws;

import com.subscriptionplatform.messaging.MessageQueue;
import com.subscriptionplatform.messaging.QueueDepth;
import com.subscriptionplatform.messaging.ReceivedMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** The order-processing SQS queue. Dead-lettering is done by SQS itself through the queue's RedrivePolicy. */
public class SqsMessageQueue implements MessageQueue {

    private final SqsClient sqs;
    private final String queueUrl;
    private final int maxReceiveCount;

    public SqsMessageQueue(SqsClient sqs, String queueUrl, int maxReceiveCount) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.maxReceiveCount = maxReceiveCount;
    }

    @Override
    public List<ReceivedMessage> receive(int maxMessages, Duration waitTime) {
        // SQS long polling works in whole seconds; round up so a sub-second wait does not become a busy loop.
        List<Message> messages = sqs.receiveMessage(SqsMessages.receiveRequest(
                queueUrl, maxMessages, SqsMessages.toWholeSeconds(waitTime), null)).messages();
        return messages.stream().map(SqsMessageQueue::toReceivedMessage).toList();
    }

    @Override
    public void acknowledge(ReceivedMessage message) {
        sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
    }

    @Override
    public void retryAfter(ReceivedMessage message, Duration delay) {
        sqs.changeMessageVisibility(request -> request
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .visibilityTimeout(SqsMessages.toVisibilitySeconds(delay)));
    }

    @Override
    public int maxReceiveCount() {
        return maxReceiveCount;
    }

    @Override
    public QueueDepth depth() {
        Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(request -> request
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED))
                .attributes();
        long visible = parse(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        long inFlight = parse(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                + parse(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED));
        return new QueueDepth(visible, inFlight);
    }

    private static ReceivedMessage toReceivedMessage(Message message) {
        return new ReceivedMessage(message.messageId(), message.receiptHandle(), message.body(),
                SqsMessages.stringAttributes(message), SqsMessages.receiveCount(message), SqsMessages.sentAt(message));
    }

    static long parse(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
