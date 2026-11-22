package com.subscriptionplatform.processing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.processing.*}, mapped to environment variables in {@code application.yml}.
 *
 * @param simulatedLatency time the simulated payment + fulfilment step takes per attempt
 * @param failureInjection failure injection active at startup (changeable at runtime via GraphQL)
 */
@ConfigurationProperties("app.processing")
public record ProcessingProperties(
        Duration simulatedLatency,
        @DefaultValue FailureInjection failureInjection) {

    /**
     * @param mode     {@code NONE} when unset
     * @param planCode restrict failures to one plan; blank means every plan
     */
    public record FailureInjection(FailureMode mode, String planCode) {
    }
}
