package com.subscriptionplatform.messaging.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionplatform.messaging.MessagingProperties;
import com.subscriptionplatform.support.Json;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

class AwsMessagingResourcesTest {

    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:order-events";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/order-processing";
    private static final String DLQ_URL = "http://localhost:4566/000000000000/order-processing-dlq";
    private static final String DLQ_ARN = "arn:aws:sqs:us-east-1:000000000000:order-processing-dlq";

    private final ObjectMapper objectMapper = Json.mapper();
    private SnsClient sns;
    private SqsClient sqs;
    private String redrivePolicy;
    private String dlqArn;

    @BeforeEach
    void setUp() {
        sns = SdkMocks.sns();
        sqs = SdkMocks.sqs();
        redrivePolicy = "{\"deadLetterTargetArn\":\"" + DLQ_ARN + "\",\"maxReceiveCount\":3}";
        dlqArn = DLQ_ARN;
        doReturn(ListTopicsResponse.builder().topics(
                Topic.builder().topicArn("arn:aws:sns:us-east-1:000000000000:other-events").build(),
                Topic.builder().topicArn(TOPIC_ARN).build()).build())
                .when(sns).listTopics(any(ListTopicsRequest.class));
        doAnswer(invocation -> {
            String name = invocation.<GetQueueUrlRequest>getArgument(0).queueName();
            return GetQueueUrlResponse.builder().queueUrl("http://localhost:4566/000000000000/" + name).build();
        }).when(sqs).getQueueUrl(any(GetQueueUrlRequest.class));
        doAnswer(invocation -> {
            GetQueueAttributesRequest request = invocation.getArgument(0);
            Map<QueueAttributeName, String> attributes = request.queueUrl().equals(QUEUE_URL)
                    ? redrivePolicy == null ? Map.of() : Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy)
                    : Map.of(QueueAttributeName.QUEUE_ARN, dlqArn);
            return GetQueueAttributesResponse.builder().attributes(attributes).build();
        }).when(sqs).getQueueAttributes(any(GetQueueAttributesRequest.class));
    }

    @Test
    void resolvesResourcesByNameAndTakesMaxReceiveCountFromTheRedrivePolicy() {
        AwsMessagingResources resources = resolve(byName(), 5);

        assertThat(resources).isEqualTo(new AwsMessagingResources(TOPIC_ARN, QUEUE_URL, DLQ_URL, 3));
    }

    @Test
    void usesConfiguredArnAndUrlsWithoutLookingThemUp() {
        MessagingProperties.Aws config = new MessagingProperties.Aws("us-east-1", null, TOPIC_ARN, null, QUEUE_URL,
                null, DLQ_URL, null);

        assertThat(resolve(config, 3).topicArn()).isEqualTo(TOPIC_ARN);
        verify(sns, never()).listTopics(any(ListTopicsRequest.class));
        verify(sqs, never()).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void acceptsAMaxReceiveCountGivenAsAString() {
        redrivePolicy = "{\"deadLetterTargetArn\":\"" + DLQ_ARN + "\",\"maxReceiveCount\":\"4\"}";

        assertThat(resolve(byName(), 3).maxReceiveCount()).isEqualTo(4);
    }

    @Test
    void refusesAQueueWithoutARedrivePolicy() {
        redrivePolicy = null;

        assertThatIllegalStateException().isThrownBy(() -> resolve(byName(), 3)).withMessageContaining("no RedrivePolicy");
    }

    @Test
    void refusesARedrivePolicyThatTargetsAnotherQueue() {
        dlqArn = "arn:aws:sqs:us-east-1:000000000000:some-other-dlq";

        assertThatIllegalStateException().isThrownBy(() -> resolve(byName(), 3)).withMessageContaining("targets");
    }

    @Test
    void reportsAMissingTopic() {
        MessagingProperties.Aws config = new MessagingProperties.Aws("us-east-1", null, null, "missing-topic", null,
                "order-processing", null, "order-processing-dlq");

        assertThatIllegalStateException().isThrownBy(() -> resolve(config, 3)).withMessageContaining("missing-topic");
    }

    private AwsMessagingResources resolve(MessagingProperties.Aws config, int configuredMaxReceiveCount) {
        return AwsMessagingResources.resolve(sns, sqs, config, configuredMaxReceiveCount, objectMapper, 1, Duration.ZERO);
    }

    private static MessagingProperties.Aws byName() {
        return new MessagingProperties.Aws("us-east-1", "http://localhost:4566", null, "order-events", null,
                "order-processing", null, "order-processing-dlq");
    }
}
