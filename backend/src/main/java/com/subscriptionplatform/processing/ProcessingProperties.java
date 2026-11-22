package com.subscriptionplatform.processing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.processing.*}
 *
 * @param simulatedLatency time the simulated payment + fulfilment step takes per attempt
 * @param failureInjection failure injection active at startup (changeable at runtime via GraphQL)
 */
@ConfigurationProperties("app.processing")
public record ProcessingProperties(
        @DefaultValue("0ms") Duration simulatedLatency,
        @DefaultValue FailureInjection failureInjection) {

    public record FailureInjection(@DefaultValue("NONE") FailureMode mode, String planCode) {
    }
}
