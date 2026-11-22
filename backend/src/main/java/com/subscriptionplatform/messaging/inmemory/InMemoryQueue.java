package com.subscriptionplatform.messaging.inmemory;

import com.subscriptionplatform.messaging.MessageQueue;
import com.subscriptionplatform.messaging.QueueDepth;
import com.subscriptionplatform.messaging.ReceivedMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Thread-safe in-memory queue with the SQS semantics the pipeline relies on:
 * <ul>
 *   <li>a received message is hidden for the visibility timeout and reappears unless acknowledged;</li>
 *   <li>every receive issues a new receipt handle, and only the latest one can acknowledge or retry;</li>
 *   <li>with a redrive policy, a message that has already been received {@code maxReceiveCount} times is
 *       moved to the dead-letter queue on its next receive instead of being delivered again.</li>
 * </ul>
 * Messages are delivered in send order (SQS standard queues only approximate this).
 */
public class InMemoryQueue implements MessageQueue {

    private static final long POLL_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final String name;
    private final Duration visibilityTimeout;
    private final RedrivePolicy redrivePolicy;
    private final Clock clock;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    /**
     * @param redrivePolicy where to move messages that keep failing, or {@code null} for none
     */
    public InMemoryQueue(String name, Duration visibilityTimeout, RedrivePolicy redrivePolicy, Clock clock) {
        this.name = Objects.requireNonNull(name);
        this.visibilityTimeout = Objects.requireNonNull(visibilityTimeout);
        this.redrivePolicy = redrivePolicy;
        this.clock = Objects.requireNonNull(clock);
    }

    public record RedrivePolicy(InMemoryQueue deadLetterQueue, int maxReceiveCount) {
        public RedrivePolicy {
            Objects.requireNonNull(deadLetterQueue, "deadLetterQueue");
            if (maxReceiveCount < 1) {
                throw new IllegalArgumentException("maxReceiveCount must be >= 1");
            }
        }
    }

    /** Point-in-time copy of a stored message. */
    public record StoredMessage(String messageId, String body, Map<String, String> attributes, Instant sentAt,
                                int receiveCount) {
    }

    public String name() {
        return name;
    }

    /** Sends a new message and returns its id. */
    public String send(String body, Map<String, String> attributes) {
        String messageId = UUID.randomUUID().toString();
        add(new Entry(messageId, body, Map.copyOf(attributes), clock.instant()));
        return messageId;
    }

    @Override
    public List<ReceivedMessage> receive(int maxMessages, Duration waitTime) {
        if (maxMessages < 1) {
            throw new IllegalArgumentException("maxMessages must be >= 1");
        }
        long deadline = System.nanoTime() + Math.max(0, waitTime.toNanos());
        while (true) {
            List<Entry> exhausted = new ArrayList<>();
            List<ReceivedMessage> batch;
            lock.lock();
            try {
                batch = takeVisible(maxMessages, exhausted);
                if (batch.isEmpty() && exhausted.isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return List.of();
                    }
                    // Wake up on new messages, or periodically to notice expired visibility timeouts.
                    changed.awaitNanos(Math.min(remaining, POLL_SLICE_NANOS));
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                lock.unlock();
            }
            // Hand over to the DLQ outside our lock so two queues never hold each other's locks.
            exhausted.forEach(entry -> redrivePolicy.deadLetterQueue().add(entry.deadLettered()));
            if (!batch.isEmpty() || System.nanoTime() >= deadline) {
                return batch;
            }
        }
    }

    @Override
    public void acknowledge(ReceivedMessage message) {
        lock.lock();
        try {
            Entry entry = entries.get(message.messageId());
            if (entry != null && entry.receiptHandle != null && entry.receiptHandle.equals(message.receiptHandle())) {
                entries.remove(message.messageId());
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void retryAfter(ReceivedMessage message, Duration delay) {
        lock.lock();
        try {
            Entry entry = entries.get(message.messageId());
            if (entry != null && entry.receiptHandle != null && entry.receiptHandle.equals(message.receiptHandle())) {
                entry.visibleAt = clock.instant().plus(delay.isNegative() ? Duration.ZERO : delay);
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int maxReceiveCount() {
        return redrivePolicy != null ? redrivePolicy.maxReceiveCount() : Integer.MAX_VALUE;
    }

    @Override
    public QueueDepth depth() {
        lock.lock();
        try {
            Instant now = clock.instant();
            long visible = entries.values().stream().filter(entry -> !entry.visibleAt.isAfter(now)).count();
            return new QueueDepth(visible, entries.size() - visible);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    /** Copies of up to {@code max} messages, oldest first, without changing their state. */
    public List<StoredMessage> snapshot(int max) {
        lock.lock();
        try {
            return entries.values().stream().limit(max).map(Entry::toStoredMessage).toList();
        } finally {
            lock.unlock();
        }
    }

    /** Removes and returns every message matching {@code filter}. */
    public List<StoredMessage> removeIf(Predicate<StoredMessage> filter) {
        lock.lock();
        try {
            List<StoredMessage> removed = new ArrayList<>();
            Iterator<Entry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                StoredMessage message = iterator.next().toStoredMessage();
                if (filter.test(message)) {
                    iterator.remove();
                    removed.add(message);
                }
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    public void purge() {
        lock.lock();
        try {
            entries.clear();
        } finally {
            lock.unlock();
        }
    }

    private void add(Entry entry) {
        lock.lock();
        try {
            entries.put(entry.messageId, entry);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Must hold the lock. Moves exhausted messages into {@code exhausted} and delivers the rest. */
    private List<ReceivedMessage> takeVisible(int maxMessages, List<Entry> exhausted) {
        Instant now = clock.instant();
        List<ReceivedMessage> batch = new ArrayList<>();
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext() && batch.size() < maxMessages) {
            Entry entry = iterator.next();
            if (entry.visibleAt.isAfter(now)) {
                continue;
            }
            if (redrivePolicy != null && entry.receiveCount >= redrivePolicy.maxReceiveCount()) {
                iterator.remove();
                exhausted.add(entry);
                continue;
            }
            entry.receiveCount++;
            entry.receiptHandle = UUID.randomUUID().toString();
            entry.visibleAt = now.plus(visibilityTimeout);
            batch.add(new ReceivedMessage(entry.messageId, entry.receiptHandle, entry.body, entry.attributes,
                    entry.receiveCount, entry.sentAt));
        }
        return batch;
    }

    private static final class Entry {
        private final String messageId;
        private final String body;
        private final Map<String, String> attributes;
        private final Instant sentAt;
        private int receiveCount;
        private Instant visibleAt = Instant.EPOCH;
        private String receiptHandle;

        private Entry(String messageId, String body, Map<String, String> attributes, Instant sentAt) {
            this.messageId = messageId;
            this.body = body;
            this.attributes = attributes;
            this.sentAt = sentAt;
        }

        /** The copy that lands in the DLQ keeps its id, body, attributes and original send time. */
        private Entry deadLettered() {
            return new Entry(messageId, body, attributes, sentAt);
        }

        private StoredMessage toStoredMessage() {
            return new StoredMessage(messageId, body, attributes, sentAt, receiveCount);
        }
    }
}
