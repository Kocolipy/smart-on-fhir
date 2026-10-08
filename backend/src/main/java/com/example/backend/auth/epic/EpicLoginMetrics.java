package com.example.backend.auth.epic;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The {@code epic.login} counter (ADR 0013, "Metrics"): one count per Epic Login that ended, tagged by
 * its {@code outcome} — {@code success}, {@code refused} or {@code unavailable} — and, for the
 * last two, its {@code reason}.
 *
 * <p>A success carries {@code reason=none}, so every series of the meter has the same tag keys,
 * which Prometheus requires of one metric name. A refusal carries its reason by the name the
 * audit trail spells it with. The reason arrives as that name, not as the domain's closed list,
 * because this package root is adapter code the onion layering keeps off the domain.
 */
public final class EpicLoginMetrics {

    /** The meter's name. */
    static final String LOGIN = "epic.login";

    /** The reason tag's value on a success, which has none. */
    static final String NO_REASON = "none";

    /** The reason tag's value on an unavailable outcome, the one reason it has. */
    static final String UNAVAILABLE_REASON = "EPIC_UNAVAILABLE";

    private final MeterRegistry registry;

    private final Counter successes;

    private final Counter unavailable;

    public EpicLoginMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.successes = counter("success", NO_REASON);
        this.unavailable = counter("unavailable", UNAVAILABLE_REASON);
    }

    /** Counts an Epic Login that signed a User in. */
    public void success() {
        successes.increment();
    }

    /**
     * Counts an Epic Login refused for {@code reason}.
     *
     * @param reason the refusal reason's name, as the audit trail records it
     */
    public void refused(String reason) {
        // The registry hands back the one meter per outcome and reason, registering it once.
        counter("refused", reason).increment();
    }

    /**
     * Counts an Epic Login that could not complete because Epic was unavailable (D23), under
     * {@code outcome=unavailable} and the one reason that outcome has, {@code EPIC_UNAVAILABLE}
     * as the audit trail records it.
     */
    public void unavailable() {
        unavailable.increment();
    }

    private Counter counter(String outcome, String reason) {
        return Counter.builder(LOGIN)
                .description("Epic Logins that ended, by outcome and refusal reason")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(registry);
    }
}
