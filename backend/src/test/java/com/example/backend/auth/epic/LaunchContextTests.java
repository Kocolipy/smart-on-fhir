package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.patient.domain.PatientId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The launch context an Epic token response names, as the landing's fragment carries it to the
 * SPA (ADR 0012, D8): each value held to FHIR's id grammar, the one {@link PatientId} holds, and
 * left out when it falls outside it.
 */
class LaunchContextTests {

    private static final String SIXTY_FOUR =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-.";

    @Test
    void aPatientAndAnEncounterRideInTheFragmentInThatOrder() {
        LaunchContext context = LaunchContext.of(Map.of(
                "encounter", "enc.42-A", "patient", "eq081-VQEgP8drUUqCWzHfw3"));

        assertThat(context.fragment())
                .isEqualTo("patient=eq081-VQEgP8drUUqCWzHfw3&encounter=enc.42-A");
    }

    @Test
    void aTokenResponseWithNeitherIsTheBareMarker() {
        assertThat(LaunchContext.of(Map.of("scope", "launch openid fhirUser")).fragment())
                .isEqualTo("launch");
    }

    @Test
    void aValueThatIsNotAStringIsLeftOut() {
        assertThat(LaunchContext.of(Map.of("patient", 42, "encounter", "enc-1")).fragment())
                .isEqualTo("encounter=enc-1");
    }

    /**
     * The grammar is mirrored from {@link PatientId}, which {@code auth} may not reach: every
     * sample {@code PatientIdTests} names, inside the grammar and out, gets the same verdict
     * here as there.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "eq081-VQEgP8drUUqCWzHfw3", "e0w0LEDCYtfckT6N.CkJKCw3", "not-a-uuid",
        "00000000-0000-4000-8000-00000000c001", "a", "-", ".", SIXTY_FOUR,
        "", SIXTY_FOUR + "x", "<img src=x onerror=alert(1)>", "1\r\nforged: S1234567A",
        "a//b", "a/b", "a b", "a_b", "Tan%20Wei", "é", "a\n", "a&encounter=b", "a#b"})
    void aValueIsKeptExactlyWhenItIsAPatientId(String value) {
        Map<String, Object> response = new HashMap<>();
        response.put("patient", value);

        assertThat(LaunchContext.of(response).fragment())
                .isEqualTo(isPatientId(value) ? "patient=" + value : "launch");
    }

    private static boolean isPatientId(String value) {
        try {
            new PatientId(value);
            return true;
        } catch (IllegalArgumentException outside) {
            return false;
        }
    }
}
