package com.subscriptionplatform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FrequencyTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);

    @Test
    void cycleZeroIsTheAnchorDate() {
        for (Frequency frequency : Frequency.values()) {
            assertThat(frequency.runDate(MONDAY, 0)).isEqualTo(MONDAY);
        }
    }

    @Test
    void weeklyAndBiweeklyAddWholeWeeks() {
        assertThat(Frequency.WEEKLY.runDate(MONDAY, 3)).isEqualTo(LocalDate.of(2026, 3, 23));
        assertThat(Frequency.BIWEEKLY.runDate(MONDAY, 2)).isEqualTo(LocalDate.of(2026, 3, 30));
    }

    @Test
    void monthlyRunDatesAreDerivedFromTheAnchorSoTheyDoNotDrift() {
        LocalDate endOfJanuary = LocalDate.of(2026, 1, 31);

        assertThat(IntStream.range(0, 5).mapToObj(cycle -> Frequency.MONTHLY.runDate(endOfJanuary, cycle)))
                .containsExactly(
                        LocalDate.of(2026, 1, 31),
                        LocalDate.of(2026, 2, 28),
                        LocalDate.of(2026, 3, 31),
                        LocalDate.of(2026, 4, 30),
                        LocalDate.of(2026, 5, 31));
    }

    @Test
    void rejectsNegativeCycles() {
        assertThatIllegalArgumentException().isThrownBy(() -> Frequency.WEEKLY.runDate(MONDAY, -1));
    }
}
