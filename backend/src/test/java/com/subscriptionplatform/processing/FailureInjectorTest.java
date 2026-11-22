package com.subscriptionplatform.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FailureInjectorTest {

    private final FailureInjector injector = new FailureInjector(
            new ProcessingProperties(Duration.ZERO, new ProcessingProperties.FailureInjection(FailureMode.NONE, null)));

    @Test
    void noneNeverFails() {
        assertThat(injector.failureFor("COFFEE", 1)).isEmpty();
        assertThat(injector.failureFor("COFFEE", 3)).isEmpty();
    }

    @Test
    void transientFailsOnlyTheFirstDelivery() {
        injector.update(FailureMode.TRANSIENT, null);

        assertThat(injector.failureFor("COFFEE", 1)).hasValueSatisfying(reason -> assertThat(reason).contains("timeout"));
        assertThat(injector.failureFor("COFFEE", 2)).isEmpty();
    }

    @Test
    void permanentFailsEveryDelivery() {
        injector.update(FailureMode.PERMANENT, null);

        for (int delivery = 1; delivery <= 5; delivery++) {
            assertThat(injector.failureFor("COFFEE", delivery)).isPresent();
        }
    }

    @Test
    void canBeLimitedToOnePlan() {
        FailureInjectionSettings settings = injector.update(FailureMode.PERMANENT, "  coffee ");

        assertThat(settings.planCode()).isEqualTo("coffee");
        assertThat(injector.failureFor("COFFEE", 1)).isPresent();
        assertThat(injector.failureFor("TEA", 1)).isEmpty();
    }

    @Test
    void blankPlanCodeMeansEveryPlan() {
        assertThat(injector.update(FailureMode.PERMANENT, " ").planCode()).isNull();
        assertThat(injector.failureFor("TEA", 1)).isPresent();
    }

    @Test
    void startsWithTheConfiguredMode() {
        FailureInjector configured = new FailureInjector(new ProcessingProperties(Duration.ZERO,
                new ProcessingProperties.FailureInjection(FailureMode.TRANSIENT, "TEA")));

        assertThat(configured.current()).isEqualTo(new FailureInjectionSettings(FailureMode.TRANSIENT, "TEA"));
    }
}
