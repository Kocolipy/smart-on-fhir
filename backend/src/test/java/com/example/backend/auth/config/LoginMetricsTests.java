package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditRefusalReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link LoginMetrics}, the adapter the alert rules read: one {@code login} counter for both login
 * methods, whose series exist from startup at zero, spelled as the rules spell them, each with the
 * same tag keys, and each count moving its one series.
 */
class LoginMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final LoginMetrics metrics = new LoginMetrics(registry);

    /**
     * An alert on a count's increase sees the first event only if the series was there at 0. The
     * series are spelled out as the alert rules spell them, and are exactly these: each method's
     * own outcomes and reasons, so a reason added to either closed set fails here until it is.
     */
    @Test
    void exactlyTheseSeriesExistAtZeroFromTheStart() {
        List<String> password = List.of("BAD_CREDENTIALS", "UNKNOWN_ACCOUNT", "ACCOUNT_LOCKED",
                "ACCOUNT_DISABLED", "OTHER");
        List<String> epic = List.of("INVALID_LAUNCH", "ISS_MISMATCH", "INVALID_STATE",
                "INVALID_CODE", "IDP_ERROR", "TOKEN_EXCHANGE_FAILED", "INVALID_SIGNATURE",
                "INVALID_CLAIMS", "INVALID_FHIR_USER", "UNKNOWN_ACCOUNT", "ACCOUNT_DISABLED",
                "ACCOUNT_LOCKED");
        List<String> expected = new ArrayList<>(List.of(
                "password,success,none", "sso,success,none", "sso,unavailable,EPIC_UNAVAILABLE"));
        password.forEach(reason -> expected.add("password,refused," + reason));
        epic.forEach(reason -> expected.add("sso,refused," + reason));

        assertThat(registry.find("login").counters())
                .extracting(counter -> counter.getId().getTag("method") + ","
                        + counter.getId().getTag("outcome") + "," + counter.getId().getTag("reason"))
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(totalEndings()).isZero();
    }

    /** Prometheus requires every series of one metric name to carry the same tag keys. */
    @Test
    void everySeriesCarriesTheSameTagKeys() {
        assertThat(registry.find("login").counters()).allSatisfy(counter ->
                assertThat(tagKeys(counter)).containsExactlyInAnyOrder("method", "outcome", "reason"));
    }

    /** Epic being unavailable is no refusal (D23), and a password Login has no Epic to miss. */
    @Test
    void onlyAnEpicLoginCanBeUnavailable() {
        assertThat(registry.find("login").tag("reason", "EPIC_UNAVAILABLE").counters())
                .singleElement()
                .satisfies(counter -> assertThat(counter.getId().getTag("method")).isEqualTo("sso"));
        assertThat(registry.find("login").tag("method", "password").tag("outcome", "unavailable")
                .counters()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"PASSWORD, password", "SSO, sso"})
    void aSignedInLoginIsCountedAsItsMethodsSuccessAndNothingElse(
            AuditLoginMethod signedIn, String method) {
        metrics.signedIn(signedIn);

        assertThat(ending(method, "success", "none").count()).isEqualTo(1.0);
        assertThat(totalEndings()).isEqualTo(1.0);
    }

    @Test
    void aPasswordRefusalIsCountedUnderItsReasonAndNothingElse() {
        metrics.refused(AuditLoginMethod.PASSWORD, AuditRefusalReason.ACCOUNT_LOCKED);

        assertThat(ending("password", "refused", "ACCOUNT_LOCKED").count()).isEqualTo(1.0);
        assertThat(totalEndings()).isEqualTo(1.0);
    }

    @Test
    void anEpicRefusalIsCountedUnderItsReasonAndNothingElse() {
        metrics.refused(AuditLoginMethod.SSO, AuditRefusalReason.INVALID_STATE);

        assertThat(ending("sso", "refused", "INVALID_STATE").count()).isEqualTo(1.0);
        assertThat(totalEndings()).isEqualTo(1.0);
    }

    @Test
    void anUnavailableLoginIsCountedUnderItsOneReasonAndNothingElse() {
        metrics.unavailable();

        assertThat(ending("sso", "unavailable", "EPIC_UNAVAILABLE").count()).isEqualTo(1.0);
        assertThat(totalEndings()).isEqualTo(1.0);
    }

    private Counter ending(String method, String outcome, String reason) {
        Counter counter = registry.find("login")
                .tag("method", method).tag("outcome", outcome).tag("reason", reason).counter();
        assertThat(counter).as("login{method=%s,outcome=%s,reason=%s}", method, outcome, reason)
                .isNotNull();
        return counter;
    }

    private double totalEndings() {
        return registry.find("login").counters().stream().mapToDouble(Counter::count).sum();
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
    }
}
