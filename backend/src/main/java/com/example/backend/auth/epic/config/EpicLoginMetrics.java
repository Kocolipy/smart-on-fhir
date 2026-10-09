package com.example.backend.auth.epic.config;

import com.example.backend.auth.application.EpicLoginCounts;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.epic.EpicOutboundCall;
import com.example.backend.observability.LogEvent.ErrorCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * Epic Login's ending meters (ADR 0013, "Metrics"), the adapter of {@link EpicLoginCounts}.
 *
 * <ul>
 *   <li>{@code epic.login}: one count per Epic Login that ended, tagged by its {@code outcome} —
 *       {@code success}, {@code refused} or {@code unavailable} — and its {@code reason}. A
 *       success carries {@code reason=none}, so every series of the meter has the same tag keys,
 *       which Prometheus requires of one metric name; a refusal carries its reason by the
 *       constant's name, as the audit trail spells it.
 *   <li>{@code epic.login.failed_calls}: one count per Epic call whose failure ended a Login,
 *       tagged {@code call} and {@code error_category} — so a refused credential
 *       ({@code cert/auth}), which no outbound status says, can be alerted on.
 * </ul>
 *
 * <p>Every series exists from the start, at zero: an alert on a count's increase then sees the
 * first event too, which a series appearing at 1 would hide from it.
 *
 * <p>In the Epic configuration package because that is the Epic adapter layer the onion layering
 * lets implement an application port; the Epic package root may not.
 */
public final class EpicLoginMetrics implements EpicLoginCounts {

    /** The ending meter's name. */
    static final String LOGIN = "epic.login";

    /** The failed-call meter's name. */
    static final String FAILED_CALLS = "epic.login.failed_calls";

    /** Every {@code error.category} an Epic call can fail under (ADR 0013, "Log"). */
    private static final List<ErrorCategory> CALL_CATEGORIES = List.of(
            ErrorCategory.NETWORK, ErrorCategory.SERVER, ErrorCategory.CERT_AUTH, ErrorCategory.DATA);

    /** The reason tag's value on a success, which has none. */
    static final String NO_REASON = "none";

    private final MeterRegistry registry;

    private final Counter successes;

    private final Counter unavailable;

    public EpicLoginMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.successes = ending("success", NO_REASON);
        this.unavailable = ending("unavailable", EpicLoginFailureReason.EPIC_UNAVAILABLE.name());
        for (EpicLoginFailureReason reason : EpicLoginFailureReason.values()) {
            if (reason != EpicLoginFailureReason.EPIC_UNAVAILABLE) {
                ending("refused", reason.name());
            }
        }
        for (EpicOutboundCall call : EpicOutboundCall.values()) {
            for (ErrorCategory category : CALL_CATEGORIES) {
                failedCallCounter(call.tag(), category);
            }
        }
    }

    @Override
    public void signedIn() {
        successes.increment();
    }

    @Override
    public void refused(EpicLoginFailureReason reason) {
        // The registry hands back the one meter per outcome and reason, registering it once.
        ending("refused", reason.name()).increment();
    }

    @Override
    public void unavailable() {
        unavailable.increment();
    }

    @Override
    public void failedCall(String call, ErrorCategory category) {
        failedCallCounter(call, category).increment();
    }

    private Counter ending(String outcome, String reason) {
        return Counter.builder(LOGIN)
                .description("Epic Logins that ended, by outcome and refusal reason")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(registry);
    }

    private Counter failedCallCounter(String call, ErrorCategory category) {
        return Counter.builder(FAILED_CALLS)
                .description("Epic calls whose failure ended an Epic Login, by call and category")
                .tag("call", call)
                .tag("error_category", category.value())
                .register(registry);
    }
}
