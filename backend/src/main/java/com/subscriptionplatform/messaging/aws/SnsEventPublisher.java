package com.subscriptionplatform.messaging.aws;

import com.subscriptionplatform.messaging.EventPublisher;
import com.subscriptionplatform.messaging.OutboundMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/** Publishes to the order-events SNS topic. Message attributes allow subscription filter policies. */
public class SnsEventPublisher implements EventPublisher {

    private final SnsClient sns;
    private final String topicArn;

    public SnsEventPublisher(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
    }

    @Override
    public String publish(OutboundMessage message) {
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        message.attributes().forEach((name, value) -> attributes.put(name,
                MessageAttributeValue.builder().dataType("String").stringValue(value).build()));
        return sns.publish(request -> request
                        .topicArn(topicArn)
                        .message(message.body())
                        .messageAttributes(attributes))
                .messageId();
    }
}
