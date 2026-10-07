package com.example.backend.auth.epic;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The {@code epic.login} counter (spec section 5): one count per Epic Login that ended, tagged by
 * its {@code outcome} and, for a refusal, its {@code reason}.
 *
 * <p>A success carries {@code reason=none}, so every series of the meter has the same tag keys,
 * which Prometheus requires of one metric name.
 */
public final class EpicLoginMetrics {

    /** The meter's name. */
    static final String LOGIN = "epic.login";

    /** The reason tag's value on a success, which has none. */
    static final String NO_REASON = "none";

    private final Counter successes;

    public EpicLoginMetrics(MeterRegistry registry) {
        this.successes = Counter.builder(LOGIN)
                .description("Epic Logins that ended, by outcome and refusal reason")
                .tag("outcome", "success")
                .tag("reason", NO_REASON)
                .register(registry);
    }

    /** Counts an Epic Login that signed a User in. */
    public void success() {
        successes.increment();
    }
}
