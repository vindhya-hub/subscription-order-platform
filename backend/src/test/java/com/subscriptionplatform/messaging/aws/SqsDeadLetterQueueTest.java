package com.subscriptionplatform.messaging.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.subscriptionplatform.messaging.DeadLetterMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class SqsDeadLetterQueueTest {

    private static final String DLQ_URL = "http://localhost:4566/000000000000/order-processing-dlq";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/order-processing";

    private final Message first = message("m-1");
    private final Message second = message("m-2");
    private SqsClient sqs;
    private SqsDeadLetterQueue deadLetters;

    @BeforeEach
    void setUp() {
        sqs = SdkMocks.sqs();
        deadLetters = new SqsDeadLetterQueue(sqs, DLQ_URL, QUEUE_URL);
        doReturn(ReceiveMessageResponse.builder().messages(first, second).build(),
                ReceiveMessageResponse.builder().build())
                .when(sqs).receiveMessage(any(ReceiveMessageRequest.class));
        doReturn(SendMessageResponse.builder().messageId("new").build()).when(sqs).sendMessage(any(SendMessageRequest.class));
        doReturn(DeleteMessageResponse.builder().build()).when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        doReturn(ChangeMessageVisibilityBatchResponse.builder().build())
                .when(sqs).changeMessageVisibilityBatch(any(ChangeMessageVisibilityBatchRequest.class));
        doReturn(GetQueueAttributesResponse.builder()
                .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "2")).build())
                .when(sqs).getQueueAttributes(any(GetQueueAttributesRequest.class));
    }

    @Test
    void redrivingSelectedMessagesMovesOnlyThoseAndReleasesTheRest() {
        List<DeadLetterMessage> moved = deadLetters.redrive(Set.of("m-2"));

        assertThat(moved).extracting(DeadLetterMessage::messageId).containsExactly("m-2");
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(sent.capture());
        assertThat(sent.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(sent.getValue().messageBody()).isEqualTo(second.body());
        ArgumentCaptor<DeleteMessageRequest> deleted = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs).deleteMessage(deleted.capture());
        assertThat(deleted.getValue().queueUrl()).isEqualTo(DLQ_URL);
        assertThat(deleted.getValue().receiptHandle()).isEqualTo("rh-m-2");
        assertThat(releasedReceiptHandles()).containsExactly("rh-m-1");
    }

    @Test
    void theBeforeResendHookRunsBeforeEachMessageIsSentBack() {
        List<String> events = new ArrayList<>();
        doAnswer(invocation -> {
            events.add("send " + invocation.<SendMessageRequest>getArgument(0).messageBody());
            return SendMessageResponse.builder().messageId("new").build();
        }).when(sqs).sendMessage(any(SendMessageRequest.class));

        deadLetters.redrive(Set.of(), message -> events.add("hook " + message.body()));

        assertThat(events).containsExactly("hook " + first.body(), "send " + first.body(),
                "hook " + second.body(), "send " + second.body());
    }

    @Test
    void redrivingWithoutASelectionMovesEverything() {
        List<DeadLetterMessage> moved = deadLetters.redrive(Set.of());

        assertThat(moved).extracting(DeadLetterMessage::messageId).containsExactly("m-1", "m-2");
        verify(sqs, times(2)).sendMessage(any(SendMessageRequest.class));
        verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, never()).changeMessageVisibilityBatch(any(ChangeMessageVisibilityBatchRequest.class));
    }

    @Test
    void peekingReturnsTheMessagesAndMakesThemVisibleAgain() {
        List<DeadLetterMessage> peeked = deadLetters.peek(100);

        assertThat(peeked).extracting(DeadLetterMessage::messageId).containsExactly("m-1", "m-2");
        assertThat(peeked.get(0).body()).isEqualTo(first.body());
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(releasedReceiptHandles()).containsExactly("rh-m-1", "rh-m-2");
    }

    @Test
    void peekingAnEmptyQueueDoesNotReceive() {
        doReturn(GetQueueAttributesResponse.builder()
                .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0")).build())
                .when(sqs).getQueueAttributes(any(GetQueueAttributesRequest.class));

        assertThat(deadLetters.peek(100)).isEmpty();
        verify(sqs, never()).receiveMessage(any(ReceiveMessageRequest.class));
    }

    private List<String> releasedReceiptHandles() {
        ArgumentCaptor<ChangeMessageVisibilityBatchRequest> captor =
                ArgumentCaptor.forClass(ChangeMessageVisibilityBatchRequest.class);
        verify(sqs).changeMessageVisibilityBatch(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(DLQ_URL);
        assertThat(captor.getValue().entries()).allSatisfy(entry -> assertThat(entry.visibilityTimeout()).isZero());
        return captor.getValue().entries().stream().map(ChangeMessageVisibilityBatchRequestEntry::receiptHandle).toList();
    }

    private static Message message(String id) {
        return Message.builder()
                .messageId(id)
                .receiptHandle("rh-" + id)
                .body("{\"orderId\":" + id.substring(2) + "}")
                .attributesWithStrings(Map.of("SentTimestamp", "1772431200000"))
                .build();
    }
}
