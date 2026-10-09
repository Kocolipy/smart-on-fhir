package com.example.backend.auth.epic.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogEvent.ErrorCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * {@link EpicLoginMetrics}, the adapter the alert rules read: the series they select on exist
 * from startup at zero, spelled as the rules spell them, and each count moves its one series.
 */
class EpicLoginMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final EpicLoginMetrics metrics = new EpicLoginMetrics(registry);

    /** An alert on a count's increase sees the first event only if the series was there at 0. */
    @Test
    void everyEndingSeriesExistsAtZeroFromTheStart() {
        assertThat(ending("success", "none").count()).isZero();
        assertThat(ending("unavailable", "EPIC_UNAVAILABLE").count()).isZero();
        for (EpicLoginFailureReason reason : EpicLoginFailureReason.values()) {
            if (reason != EpicLoginFailureReason.EPIC_UNAVAILABLE) {
                assertThat(ending("refused", reason.name()).count()).as(reason.name()).isZero();
            }
        }
    }

    /** Epic being unavailable is no refusal (D23), so it has no refused series to alert on. */
    @Test
    void epicUnavailableHasNoRefusedSeries() {
        assertThat(registry.find("epic.login")
                .tag("outcome", "refused").tag("reason", "EPIC_UNAVAILABLE").counter()).isNull();
    }

    /** The description is the {@code # HELP} line Prometheus serves for each meter. */
    @Test
    void eachMeterSaysWhatItCounts() {
        assertThat(ending("success", "none").getId().getDescription())
                .isEqualTo("Epic Logins that ended, by outcome and refusal reason");
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

    @Test
    void aSignedInLoginIsCountedAsASuccessAndNothingElse() {
        metrics.signedIn();

        assertThat(ending("success", "none").count()).isEqualTo(1.0);
        assertThat(totalEndings()).isEqualTo(1.0);
    }

    @Test
    void aRefusalIsCountedUnderItsReasonAndNothingElse() {
        metrics.refused(EpicLoginFailureReason.INVALID_STATE);

        assertThat(ending("refused", "INVALID_STATE").count()).isEqualTo(1.0);
        assertThat(totalEndings()).isEqualTo(1.0);
    }

    @Test
    void anUnavailableLoginIsCountedUnderItsOneReasonAndNothingElse() {
        metrics.unavailable();

        assertThat(ending("unavailable", "EPIC_UNAVAILABLE").count()).isEqualTo(1.0);
        assertThat(totalEndings()).isEqualTo(1.0);
    }

    private Counter ending(String outcome, String reason) {
        Counter counter = registry.find("epic.login")
                .tag("outcome", outcome).tag("reason", reason).counter();
        assertThat(counter).as("epic.login{outcome=%s,reason=%s}", outcome, reason).isNotNull();
        return counter;
    }

    private Counter failedCall(String call, String category) {
        Counter counter = registry.find("epic.login.failed_calls")
                .tag("call", call).tag("error_category", category).counter();
        assertThat(counter).as("epic.login.failed_calls{call=%s,error_category=%s}",
                call, category).isNotNull();
        return counter;
    }

    private double totalEndings() {
        return registry.find("epic.login").counters().stream().mapToDouble(Counter::count).sum();
    }
}
