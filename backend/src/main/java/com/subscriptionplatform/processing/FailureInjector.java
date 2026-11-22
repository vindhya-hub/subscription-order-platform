package com.subscriptionplatform.processing;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Decides, deterministically, whether a processing attempt should fail. The rule depends only on the
 * configured mode, the order's plan and the delivery attempt, so retries, dead-lettering and replays can
 * be demonstrated and tested reliably.
 */
@Component
public class FailureInjector {

    private static final Logger log = LoggerFactory.getLogger(FailureInjector.class);

    private final AtomicReference<FailureInjectionSettings> settings;

    public FailureInjector(ProcessingProperties properties) {
        ProcessingProperties.FailureInjection initial = properties.failureInjection();
        this.settings = new AtomicReference<>(new FailureInjectionSettings(initial.mode(), normalize(initial.planCode())));
    }

    public FailureInjectionSettings current() {
        return settings.get();
    }

    public FailureInjectionSettings update(FailureMode mode, String planCode) {
        FailureInjectionSettings updated = new FailureInjectionSettings(mode, normalize(planCode));
        settings.set(updated);
        log.info("Failure injection set to {}{}", mode, updated.planCode() == null ? "" : " for plan " + updated.planCode());
        return updated;
    }

    /** The simulated failure for this delivery attempt (1-based), if one applies. */
    public Optional<String> failureFor(String planCode, int deliveryAttempt) {
        FailureInjectionSettings current = settings.get();
        if (current.planCode() != null && !current.planCode().equalsIgnoreCase(planCode)) {
            return Optional.empty();
        }
        return switch (current.mode()) {
            case NONE -> Optional.empty();
            case TRANSIENT -> deliveryAttempt <= 1
                    ? Optional.of("Simulated payment gateway timeout (delivery " + deliveryAttempt + ")")
                    : Optional.empty();
            case PERMANENT -> Optional.of("Simulated payment gateway outage (delivery " + deliveryAttempt + ")");
        };
    }

    private static String normalize(String planCode) {
        return StringUtils.hasText(planCode) ? planCode.strip() : null;
    }
}
