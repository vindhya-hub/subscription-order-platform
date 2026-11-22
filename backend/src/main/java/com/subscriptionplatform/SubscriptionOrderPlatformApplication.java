package com.subscriptionplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SubscriptionOrderPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubscriptionOrderPlatformApplication.class, args);
    }
}
