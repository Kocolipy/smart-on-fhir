package com.example.backend.auth.epic.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.observability.LogEvent.ErrorCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * {@link EpicCallMetrics}, the adapter {@code EpicClientCredentialRefused} reads: every series
 * exists from startup at zero, spelled as the rule spells it, and each count moves its one series.
 */
class EpicCallMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final EpicCallMetrics metrics = new EpicCallMetrics(registry);

    /** The description is the {@code # HELP} line Prometheus serves for the meter. */
    @Test
    void theMeterSaysWhatItCounts() {
        assertThat(failedCall("token", "cert/auth").getId().getDescription())
                .isEqualTo("Epic calls whose failure ended an Epic Login, by call and category");
    }

    /** {@code EpicClientCredentialRefused} selects {@code call="token", error_category="cert/auth"}. */
    @Test
    void everyFailedCallSeriesExistsAtZeroFromTheStart() {
        for (String call : new String[] {"discovery", "jwks", "token"}) {
            for (String category : new String[] {"network", "server", "cert/auth", "data"}) {
                assertThat(failedCall(call, category).count()).as(call + "/" + category).isZero();
            }
        }
    }

    @Test
    void aFailedCallIsCountedUnderItsCallAndCategory() {
        metrics.failedCall("token", ErrorCategory.CERT_AUTH);

        assertThat(failedCall("token", "cert/auth").count()).isEqualTo(1.0);
        assertThat(registry.find("epic.login.failed_calls").counters().stream()
                .mapToDouble(Counter::count).sum()).isEqualTo(1.0);
    }

    /** The Login's ending is the {@code login} counter's, which this adapter does not register. */
    @Test
    void itRegistersNoLoginEndingSeries() {
        assertThat(registry.find("login").counters()).isEmpty();
        assertThat(registry.find("epic.login").counters()).isEmpty();
    }

    private Counter failedCall(String call, String category) {
        Counter counter = registry.find("epic.login.failed_calls")
                .tag("call", call).tag("error_category", category).counter();
        assertThat(counter).as("epic.login.failed_calls{call=%s,error_category=%s}",
                call, category).isNotNull();
        return counter;
    }
}
