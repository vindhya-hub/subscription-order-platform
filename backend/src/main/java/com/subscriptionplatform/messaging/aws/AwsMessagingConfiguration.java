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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * SNS/SQS through the AWS SDK v2: {@code app.messaging.provider=aws}.
 *
 * <p>Credentials come from the SDK's default provider chain (environment variables, profile, IAM role).
 * Set {@code app.messaging.aws.endpoint} to point the clients at LocalStack.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.messaging.provider", havingValue = "aws")
public class AwsMessagingConfiguration {

    @Bean
    public SnsClient snsClient(MessagingProperties properties) {
        MessagingProperties.Aws aws = properties.aws();
        SnsClientBuilder builder = SnsClient.builder().region(Region.of(aws.region()));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint()));
        }
        return builder.build();
    }

    @Bean
    public SqsClient sqsClient(MessagingProperties properties) {
        MessagingProperties.Aws aws = properties.aws();
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(aws.region()));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint()));
        }
        return builder.build();
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
}
