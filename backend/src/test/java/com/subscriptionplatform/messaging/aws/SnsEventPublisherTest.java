package com.subscriptionplatform.messaging.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.subscriptionplatform.messaging.OutboundMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

class SnsEventPublisherTest {

    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:order-events";

    @Test
    void publishesTheBodyWithStringMessageAttributes() {
        SnsClient sns = SdkMocks.sns();
        doReturn(PublishResponse.builder().messageId("sns-1").build()).when(sns).publish(any(PublishRequest.class));

        String messageId = new SnsEventPublisher(sns, TOPIC_ARN)
                .publish(new OutboundMessage("{\"orderId\":42}", Map.of("eventType", "OrderCreated")));

        assertThat(messageId).isEqualTo("sns-1");
        ArgumentCaptor<PublishRequest> request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(request.capture());
        assertThat(request.getValue().topicArn()).isEqualTo(TOPIC_ARN);
        assertThat(request.getValue().message()).isEqualTo("{\"orderId\":42}");
        assertThat(request.getValue().messageAttributes()).containsExactly(Map.entry("eventType",
                MessageAttributeValue.builder().dataType("String").stringValue("OrderCreated").build()));
    }
}
