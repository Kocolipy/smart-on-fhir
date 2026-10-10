package com.example.backend.auth.config;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.application.LoginCounts;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * The {@code login} counter (ADR 0013, "Metrics"), the adapter of {@link LoginCounts}: one count
 * per Login that ended, by either login method, tagged by its {@code method} — {@code password} or
 * {@code sso} — its {@code outcome} — {@code success}, {@code refused} or, for an Epic Login only,
 * {@code unavailable} — and its {@code reason}.
 *
 * <p>A success carries {@code reason=none}, so every series of the meter has the same tag keys,
 * which Prometheus requires of one metric name; a refusal carries its reason by the
 * {@link AuditRefusalReason} constant's name, as the audit trail spells it. The reason is what
 * Logging §2.2 keeps out of the operational log, because it tells whether an account exists; a
 * count names no account, so it may carry it.
 *
 * <p>Every series exists from the start, at zero: an alert on a count's increase then sees the
 * first event too, which a series appearing at 1 would hide from it. Those are each method's own
 * outcomes and reasons — a password Login's {@link #PASSWORD_REASONS}, and an Epic Login's
 * {@link EpicLoginFailureReason} list — and nothing else.
 *
 * <p>Registered whether or not Epic Login is on ({@link LoginMetricsConfig}), because password
 * Login always is; in this configuration package because that is the adapter layer the onion
 * layering lets implement an application port.
 */
public final class LoginMetrics implements LoginCounts {

    /** The meter's name. */
    static final String LOGIN = "login";

    /** The reason tag's value on a success, which has none. */
    static final String NO_REASON = "none";

    /**
     * Every reason a password Login's refusal is counted under: the four Spring Security reports,
     * and {@code UNKNOWN_ACCOUNT}, which recording the refusal settles by looking the name up.
     */
    static final List<AuditRefusalReason> PASSWORD_REASONS = List.of(
            AuditRefusalReason.BAD_CREDENTIALS,
            AuditRefusalReason.UNKNOWN_ACCOUNT,
            AuditRefusalReason.ACCOUNT_LOCKED,
            AuditRefusalReason.ACCOUNT_DISABLED,
            AuditRefusalReason.OTHER);

    /** How a Login ended, as the {@code outcome} tag spells it. */
    private enum Outcome {
        SUCCESS("success"),
        REFUSED("refused"),
        UNAVAILABLE("unavailable");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    private final MeterRegistry registry;

    public LoginMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (AuditLoginMethod method : AuditLoginMethod.values()) {
            ending(method, Outcome.SUCCESS, NO_REASON);
        }
        for (AuditRefusalReason reason : PASSWORD_REASONS) {
            ending(AuditLoginMethod.PASSWORD, Outcome.REFUSED, reason.name());
        }
        for (EpicLoginFailureReason reason : EpicLoginFailureReason.values()) {
            if (reason != EpicLoginFailureReason.EPIC_UNAVAILABLE) {
                ending(AuditLoginMethod.SSO, Outcome.REFUSED, reason.audited().name());
            }
        }
        ending(AuditLoginMethod.SSO, Outcome.UNAVAILABLE,
                EpicLoginFailureReason.EPIC_UNAVAILABLE.audited().name());
    }

    @Override
    public void signedIn(AuditLoginMethod method) {
        // The registry hands back the one meter per method, outcome and reason, registering it once.
        ending(method, Outcome.SUCCESS, NO_REASON).increment();
    }

    @Override
    public void refused(AuditLoginMethod method, AuditRefusalReason reason) {
        ending(method, Outcome.REFUSED, reason.name()).increment();
    }

    @Override
    public void unavailable() {
        ending(AuditLoginMethod.SSO, Outcome.UNAVAILABLE,
                EpicLoginFailureReason.EPIC_UNAVAILABLE.audited().name()).increment();
    }

    private Counter ending(AuditLoginMethod method, Outcome outcome, String reason) {
        return Counter.builder(LOGIN)
                .description("Logins that ended, by login method, outcome and refusal reason")
                .tag("method", method.value())
                .tag("outcome", outcome.tag)
                .tag("reason", reason)
                .register(registry);
    }
}
