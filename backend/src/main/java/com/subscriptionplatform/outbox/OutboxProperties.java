package com.subscriptionplatform.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.outbox.*}
 *
 * @param relayInterval delay between scheduled sweeps for events that were not published right away
 * @param batchSize     events loaded per relay query
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @DefaultValue("PT5S") Duration relayInterval,
        @DefaultValue("100") int batchSize) {
}
