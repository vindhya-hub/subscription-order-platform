package com.subscriptionplatform.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.outbox.*}, mapped in {@code application.yml}.
 *
 * @param relayInterval delay between scheduled sweeps for events that were not published right away
 * @param batchSize     events loaded per relay query
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(Duration relayInterval, int batchSize) {
}
