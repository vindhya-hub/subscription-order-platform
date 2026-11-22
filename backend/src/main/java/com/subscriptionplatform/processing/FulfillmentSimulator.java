package com.subscriptionplatform.processing;

import com.subscriptionplatform.order.OrderSnapshot;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the external calls an order needs: capture the payment, then book the shipment. It takes
 * {@code app.processing.simulated-latency} per attempt and fails according to the {@link FailureInjector}.
 */
@Component
public class FulfillmentSimulator {

    private final FailureInjector failureInjector;
    private final Duration latency;

    public FulfillmentSimulator(FailureInjector failureInjector, ProcessingProperties properties) {
        this.failureInjector = failureInjector;
        this.latency = properties.simulatedLatency() != null ? properties.simulatedLatency() : Duration.ZERO;
    }

    /**
     * @param deliveryAttempt receive count of the message being processed (1 = first delivery)
     * @throws ProcessingFailedException when the simulated payment or shipment fails
     */
    public void process(OrderSnapshot order, int deliveryAttempt) {
        pause();
        failureInjector.failureFor(order.planCode(), deliveryAttempt).ifPresent(reason -> {
            throw new ProcessingFailedException(reason);
        });
        // Payment captured and shipment booked (simulated).
    }

    private void pause() {
        if (latency.isZero() || latency.isNegative()) {
            return;
        }
        try {
            Thread.sleep(latency.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingFailedException("Interrupted while processing");
        }
    }
}
