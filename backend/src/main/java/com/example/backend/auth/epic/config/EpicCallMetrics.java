package com.example.backend.auth.epic.config;

import com.example.backend.auth.application.EpicCallCounts;
import com.example.backend.auth.epic.EpicOutboundCall;
import com.example.backend.observability.LogEvent.ErrorCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * {@code epic.login.failed_calls} (ADR 0013, "Metrics"), the adapter of {@link EpicCallCounts}: one
 * count per Epic call whose failure ended a Login, tagged {@code call} and {@code error_category} —
 * so a refused credential ({@code cert/auth}), which no outbound status says, can be alerted on.
 * How the Login itself ended is the {@code login} counter's, which counts both login methods.
 *
 * <p>Every series exists from the start, at zero: an alert on a count's increase then sees the
 * first event too, which a series appearing at 1 would hide from it.
 *
 * <p>In the Epic configuration package because that is the Epic adapter layer the onion layering
 * lets implement an application port; the Epic package root may not.
 */
public final class EpicCallMetrics implements EpicCallCounts {

    /** The failed-call meter's name. */
    static final String FAILED_CALLS = "epic.login.failed_calls";

    /** Every {@code error.category} an Epic call can fail under (ADR 0013, "Log"). */
    private static final List<ErrorCategory> CALL_CATEGORIES = List.of(
            ErrorCategory.NETWORK, ErrorCategory.SERVER, ErrorCategory.CERT_AUTH, ErrorCategory.DATA);

    private final MeterRegistry registry;

    public EpicCallMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (EpicOutboundCall call : EpicOutboundCall.values()) {
            for (ErrorCategory category : CALL_CATEGORIES) {
                failedCallCounter(call.tag(), category);
            }
        }
    }

    @Override
    public void failedCall(String call, ErrorCategory category) {
        failedCallCounter(call, category).increment();
    }

    private Counter failedCallCounter(String call, ErrorCategory category) {
        return Counter.builder(FAILED_CALLS)
                .description("Epic calls whose failure ended an Epic Login, by call and category")
                .tag("call", call)
                .tag("error_category", category.value())
                .register(registry);
    }
}
