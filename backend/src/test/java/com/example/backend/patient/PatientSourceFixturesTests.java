package com.example.backend.patient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * The patient source's development fixtures, read from the stub files the local mock serves
 * ({@code patient-source-mock/mappings}): four dummy patients under ids borrowed from Epic's
 * public sandbox test patients, covering every gender code, each with an NRIC that cannot be a
 * real person's (/docs/domain/patient.md, "The patient source's fixtures"). Each fixture's values
 * as the service reads them are {@code PatientStoreContract}'s.
 */
class PatientSourceFixturesTests {

    private static final String NRIC_SYSTEM = "https://fhir.synapxe.sg/NamingSystem/nric";

    private static final List<JsonNode> FIXTURES = PatientSourceStubs.fixtures();

    /** Every fixture's NRIC: the value of its identifier under the NRIC NamingSystem. */
    private static List<String> nrics() {
        return FIXTURES.stream()
                .map(fixture -> StreamSupport.stream(fixture.get("identifier").spliterator(), false)
                        .filter(identifier -> NRIC_SYSTEM.equals(identifier.get("system").asString()))
                        .map(identifier -> identifier.get("value").asString())
                        .findFirst().orElseThrow())
                .toList();
    }

    /**
     * The four ids the Medplum seed also names ({@code fhircast-shim/src/seed-patients.mjs}), so a
     * launch or a FHIRcast event naming any of them finds it here.
     */
    @Test
    void theFixturesAreFourPatientsUnderEpicSandboxIds() {
        assertThat(FIXTURES).extracting(fixture -> fixture.get("id").asString())
                .containsExactlyInAnyOrder(
                        "eq081-VQEgP8drUUqCWzHfw3",
                        "erXuFYUfucBZaryVksYEcMg3",
                        "e0w0LEDCYtfckT6N.CkJKCw3",
                        "egqBHVfQlt4Bw3XGXoxVxHg3");
    }

    @Test
    void theFixturesBetweenThemCarryEveryGenderCode() {
        assertThat(FIXTURES).extracting(fixture -> fixture.get("gender").asString())
                .containsExactlyInAnyOrder("male", "female", "other", "unknown");
    }

    @Test
    void everyFixtureNricIsUnique() {
        assertThat(nrics()).hasSize(FIXTURES.size()).doesNotHaveDuplicates();
    }

    @Test
    void everyFixtureNricHasTheNricShape() {
        assertThat(nrics()).isNotEmpty()
                .allSatisfy(nric -> assertThat(nric).matches("[STFG][0-9]{7}[A-Z]"));
    }

    /**
     * Pins the check-letter algorithm the next test relies on to worked values, so the
     * algorithm itself cannot drift into one every NRIC fails.
     */
    @ParameterizedTest
    @CsvSource({
        "S1234567, D",
        "T1234567, J",
        "S9212345, F",
        "T0312345, B",
        "S7812345, A",
        "F1234567, N",
        "G1234567, X"})
    void theCheckLetterAlgorithmGivesTheValidLetter(String number, char valid) {
        assertThat(NricCheckLetter.of(number)).isEqualTo(valid);
    }

    /**
     * Every dummy NRIC carries a check letter the algorithm rejects, so none can be a real
     * person's, and a real-looking value cannot slip into the stub files later.
     */
    @Test
    void everyFixtureNricFailsTheCheckLetterAlgorithm() {
        assertThat(nrics()).isNotEmpty().allSatisfy(nric ->
                assertThat(nric.charAt(8)).isNotEqualTo(NricCheckLetter.of(nric.substring(0, 8))));
    }

    /**
     * The NRIC check letter: the seven digits weighted 2, 7, 6, 5, 4, 3, 2, plus 4 for a T or G
     * prefix, taken mod 11 to index the S/T letters J Z I H G F E D C B A or the F/G letters
     * X W U T R Q P N M L K. Test code only: production never validates an NRIC.
     */
    private static final class NricCheckLetter {

        private static final int[] WEIGHTS = {2, 7, 6, 5, 4, 3, 2};

        static char of(String prefixAndDigits) {
            char prefix = prefixAndDigits.charAt(0);
            int sum = prefix == 'T' || prefix == 'G' ? 4 : 0;
            for (int i = 0; i < WEIGHTS.length; i++) {
                sum += (prefixAndDigits.charAt(i + 1) - '0') * WEIGHTS[i];
            }
            String letters = prefix == 'S' || prefix == 'T' ? "JZIHGFEDCBA" : "XWUTRQPNMLK";
            return letters.charAt(sum % 11);
        }
    }
}
