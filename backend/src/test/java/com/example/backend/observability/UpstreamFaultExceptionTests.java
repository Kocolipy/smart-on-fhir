package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.observability.LogEvent.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What an upstream fault tells its record: the category and the reason it was raised with, and
 * whether a person must follow it up — ADR 0012's table for a failed outbound call, where no
 * answer and a {@code 5xx} pass on their own and an answer the service cannot use does not.
 */
class UpstreamFaultExceptionTests {

    /** An upstream fault as a calling module raises one. */
    private static final class Raised extends UpstreamFaultException {

        Raised(ErrorCategory category) {
            super("The source failed", category, "a-reason", null);
        }
    }

    @Test
    void aFaultCarriesTheCategoryAndTheReasonItWasRaisedWith() {
        Raised fault = new Raised(ErrorCategory.SERVER);

        assertThat(new Object[] {fault.category(), fault.reason(), fault.getMessage()})
                .containsExactly(ErrorCategory.SERVER, "a-reason", "The source failed");
    }

    /**
     * One category each way: which categories are unavailable is {@link ErrorCategory}'s table,
     * held across every category by {@code LogEventTests}; this holds that a fault reads it.
     */
    @ParameterizedTest
    @CsvSource({
        "NETWORK, false",
        "DATA, true"})
    void anUnavailableServiceNeedsNoFollowUpAndAnUnusableAnswerDoes(ErrorCategory category, boolean followUp) {
        assertThat(new Raised(category).followUp()).isEqualTo(followUp);
    }
}
