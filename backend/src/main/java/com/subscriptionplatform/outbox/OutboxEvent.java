package com.subscriptionplatform.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An event recorded in the same transaction as the state change that caused it (transactional outbox).
 * {@link OutboxRelay} publishes it to SNS afterwards, so an order is never committed without its event,
 * and an event is never published for an order that rolled back.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "message_id", length = 100)
    private String messageId;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEvent() {
        // for JPA
    }

    public OutboxEvent(String aggregateType, Long aggregateId, String eventType, String payload, Instant createdAt) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public void markPublished(String messageId, Instant now) {
        this.publishAttempts++;
        this.messageId = messageId;
        this.publishedAt = now;
        this.lastError = null;
    }

    public void markPublishFailed(String error) {
        this.publishAttempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public int getPublishAttempts() {
        return publishAttempts;
    }

    public String getLastError() {
        return lastError;
    }
}
