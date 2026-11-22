package com.subscriptionplatform.messaging.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.subscriptionplatform.messaging.QueueDepth;
import com.subscriptionplatform.messaging.ReceivedMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsMessageQueueTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/order-processing";

    private SqsClient sqs;
    private SqsMessageQueue queue;

    @BeforeEach
    void setUp() {
        sqs = SdkMocks.sqs();
        queue = new SqsMessageQueue(sqs, QUEUE_URL, 3);
    }

    @Test
    void receiveLongPollsAndExposesTheReceiveCount() {
        Message message = Message.builder()
                .messageId("m-1")
                .receiptHandle("rh-1")
                .body("{\"orderId\":42}")
                .attributesWithStrings(Map.of("ApproximateReceiveCount", "2", "SentTimestamp", "1772431200000"))
                .messageAttributes(Map.of("eventType",
                        MessageAttributeValue.builder().dataType("String").stringValue("OrderCreated").build()))
                .build();
        doReturn(ReceiveMessageResponse.builder().messages(message).build())
                .when(sqs).receiveMessage(any(ReceiveMessageRequest.class));

        List<ReceivedMessage> received = queue.receive(25, Duration.ofMillis(200));

        assertThat(received).singleElement().satisfies(m -> {
            assertThat(m.messageId()).isEqualTo("m-1");
            assertThat(m.receiptHandle()).isEqualTo("rh-1");
            assertThat(m.receiveCount()).isEqualTo(2);
            assertThat(m.sentAt()).isEqualTo(Instant.ofEpochMilli(1772431200000L));
            assertThat(m.attributes()).containsEntry("eventType", "OrderCreated");
        });
        ArgumentCaptor<ReceiveMessageRequest> request = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs).receiveMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.getValue().maxNumberOfMessages()).isEqualTo(10);
        assertThat(request.getValue().waitTimeSeconds()).isEqualTo(1);
        assertThat(request.getValue().messageSystemAttributeNames())
                .contains(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        assertThat(request.getValue().messageAttributeNames()).containsExactly("All");
    }

    @Test
    void acknowledgeDeletesWithTheReceiptHandle() {
        doReturn(DeleteMessageResponse.builder().build()).when(sqs).deleteMessage(any(DeleteMessageRequest.class));

        queue.acknowledge(received("rh-7"));

        ArgumentCaptor<DeleteMessageRequest> request = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs).deleteMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.getValue().receiptHandle()).isEqualTo("rh-7");
    }

    @Test
    void retryChangesTheVisibilityTimeoutRoundedUpToSeconds() {
        doReturn(ChangeMessageVisibilityResponse.builder().build())
                .when(sqs).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));

        queue.retryAfter(received("rh-7"), Duration.ofMillis(1500));

        ArgumentCaptor<ChangeMessageVisibilityRequest> request =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(request.capture());
        assertThat(request.getValue().receiptHandle()).isEqualTo("rh-7");
        assertThat(request.getValue().visibilityTimeout()).isEqualTo(2);
    }

    @Test
    void depthCountsDelayedAndInvisibleMessagesAsInFlight() {
        doReturn(GetQueueAttributesResponse.builder().attributes(Map.of(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "4",
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "2",
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, "1")).build())
                .when(sqs).getQueueAttributes(any(GetQueueAttributesRequest.class));

        assertThat(queue.depth()).isEqualTo(new QueueDepth(4, 3));
        assertThat(queue.maxReceiveCount()).isEqualTo(3);
    }

    private static ReceivedMessage received(String receiptHandle) {
        return new ReceivedMessage("m-1", receiptHandle, "{}", Map.of(), 1, Instant.EPOCH);
    }
}
