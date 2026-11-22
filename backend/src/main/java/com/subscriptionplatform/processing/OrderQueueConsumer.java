package com.subscriptionplatform.processing;

import com.subscriptionplatform.messaging.MessagingProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Background long-polling loop over the order queue. Disable with
 * {@code app.messaging.consumer.enabled=false} to run an API-only instance (or in tests that drive
 * {@link OrderQueueWorker} by hand).
 */
@Component
@ConditionalOnProperty(name = "app.messaging.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class OrderQueueConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueConsumer.class);
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(2);

    private final OrderQueueWorker worker;
    private final Duration waitTime;
    private volatile boolean running;
    private Thread thread;

    public OrderQueueConsumer(OrderQueueWorker worker, MessagingProperties properties) {
        this.worker = worker;
        this.waitTime = properties.consumer().waitTime();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::pollLoop, "order-queue-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("Order queue consumer started (long-poll wait {})", waitTime);
    }

    private void pollLoop() {
        while (running) {
            try {
                worker.pollOnce(waitTime);
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                log.warn("Polling the order queue failed; retrying in {}: {}", ERROR_BACKOFF, e.toString());
                sleepQuietly(ERROR_BACKOFF);
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            try {
                current.join(waitTime.plusSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Order queue consumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
