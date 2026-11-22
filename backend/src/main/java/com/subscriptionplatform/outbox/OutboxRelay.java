package com.subscriptionplatform.outbox;

import com.subscriptionplatform.messaging.EventPublisher;
import com.subscriptionplatform.messaging.OutboundMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to the topic in insertion order. Called right after a billing run commits, and
 * on a fixed delay as a safety net for events whose first publish failed (broker down, crash, ...).
 *
 * <p>Delivery is at-least-once: if the process dies between publishing and recording the publish, the
 * event goes out again and the consumer's idempotency check absorbs the duplicate.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final EventPublisher publisher;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository outbox, EventPublisher publisher, OutboxProperties properties, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes every pending event, stopping at the first failure so a broker outage does not turn into a
     * retry storm; the scheduled sweep picks up where this left off.
     *
     * @return number of events published
     */
    public synchronized int publishPending() {
        int published = 0;
        while (true) {
            List<OutboxEvent> batch = outbox.findByPublishedAtIsNullOrderByIdAsc(Limit.of(properties.batchSize()));
            for (OutboxEvent event : batch) {
                try {
                    String messageId = publisher.publish(new OutboundMessage(event.getPayload(), Map.of(
                            "eventType", event.getEventType(),
                            "aggregateType", event.getAggregateType(),
                            "aggregateId", String.valueOf(event.getAggregateId()))));
                    event.markPublished(messageId, Instant.now(clock));
                    outbox.save(event);
                    published++;
                } catch (RuntimeException e) {
                    event.markPublishFailed(e.getMessage());
                    outbox.save(event);
                    log.warn("Publishing outbox event {} ({} {}) failed; will retry: {}", event.getId(),
                            event.getEventType(), event.getAggregateId(), e.toString());
                    return published;
                }
            }
            if (batch.size() < properties.batchSize()) {
                return published;
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval:PT5S}",
            initialDelayString = "${app.outbox.relay-interval:PT5S}")
    void sweep() {
        int published = publishPending();
        if (published > 0) {
            log.info("Outbox sweep published {} pending event(s)", published);
        }
    }
}
