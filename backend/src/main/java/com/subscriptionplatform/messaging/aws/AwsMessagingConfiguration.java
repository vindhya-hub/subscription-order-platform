package com.subscriptionplatform.messaging.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionplatform.messaging.DeadLetterQueue;
import com.subscriptionplatform.messaging.EventPublisher;
import com.subscriptionplatform.messaging.MessageQueue;
import com.subscriptionplatform.messaging.MessagingProperties;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SNS/SQS through the AWS SDK v2: {@code app.messaging.provider=aws}.
 *
 * <p>Credentials always come from the SDK's default provider chain (environment variables, shared profile,
 * container or instance role); nothing credential-related is read from application configuration. Set
 * {@code AWS_ENDPOINT_URL} to point the clients at LocalStack.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.messaging.provider", havingValue = "aws")
public class AwsMessagingConfiguration {

    @Bean
    public SnsClient snsClient(MessagingProperties properties) {
        return configure(SnsClient.builder(), properties.aws()).build();
    }

    @Bean
    public SqsClient sqsClient(MessagingProperties properties) {
        return configure(SqsClient.builder(), properties.aws()).build();
    }

    @Bean
    public AwsMessagingResources awsMessagingResources(SnsClient sns, SqsClient sqs, MessagingProperties properties,
                                                       ObjectMapper objectMapper) {
        return AwsMessagingResources.resolve(sns, sqs, properties.aws(), properties.maxReceiveCount(), objectMapper);
    }

    @Bean
    public EventPublisher eventPublisher(SnsClient sns, AwsMessagingResources resources) {
        return new SnsEventPublisher(sns, resources.topicArn());
    }

    @Bean
    public MessageQueue messageQueue(SqsClient sqs, AwsMessagingResources resources) {
        return new SqsMessageQueue(sqs, resources.queueUrl(), resources.maxReceiveCount());
    }

    @Bean
    public DeadLetterQueue deadLetterQueue(SqsClient sqs, AwsMessagingResources resources) {
        return new SqsDeadLetterQueue(sqs, resources.deadLetterQueueUrl(), resources.queueUrl());
    }

    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder, MessagingProperties.Aws aws) {
        builder.region(Region.of(aws.region()));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint()));
        }
        return builder;
    }
}
