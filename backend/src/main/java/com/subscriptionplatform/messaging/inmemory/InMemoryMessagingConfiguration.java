package com.subscriptionplatform.messaging.inmemory;

import com.subscriptionplatform.messaging.DeadLetterQueue;
import com.subscriptionplatform.messaging.EventPublisher;
import com.subscriptionplatform.messaging.MessageQueue;
import com.subscriptionplatform.messaging.MessagingProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default broker for local development and tests: {@code app.messaging.provider=in-memory}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.messaging.provider", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryMessagingConfiguration {

    @Bean
    public InMemoryMessaging inMemoryMessaging(MessagingProperties properties, Clock clock) {
        return InMemoryMessaging.create(properties.visibilityTimeout(), properties.maxReceiveCount(), clock);
    }

    @Bean
    public EventPublisher eventPublisher(InMemoryMessaging messaging) {
        return messaging.topic();
    }

    @Bean
    public MessageQueue messageQueue(InMemoryMessaging messaging) {
        return messaging.queue();
    }

    @Bean
    public DeadLetterQueue deadLetterQueue(InMemoryMessaging messaging) {
        return messaging.deadLetters();
    }
}
