package com.example.backend.patient.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientName;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The development patient fixtures: four dummy patients under fixed ids, covering Singapore's name
 * styles and every gender code, each with an NRIC that cannot be a real person's.
 */
class DevPatientFixturesTests {

    @Test
    void theFixturesAreFourPatientsUnderFixedIdsInSeedingOrder() {
        assertThat(DevPatientFixtures.ALL).extracting(Patient::id).containsExactly(
                UUID.fromString("00000000-0000-4000-8000-00000000c001"),
                UUID.fromString("00000000-0000-4000-8000-00000000c002"),
                UUID.fromString("00000000-0000-4000-8000-00000000c003"),
                UUID.fromString("00000000-0000-4000-8000-00000000c004"));
    }

    @Test
    void theDefaultPatientIsTanWeiMingFamilyNameFirst() {
        assertThat(DevPatientFixtures.DEFAULT_PATIENT).isEqualTo(new Patient(
                UUID.fromString("00000000-0000-4000-8000-00000000c001"),
                "S1234567A",
                new PatientName("Tan Wei Ming", "Tan", List.of("Wei", "Ming")),
                AdministrativeGender.MALE,
                LocalDate.of(1985, 3, 12)));
    }

    @Test
    void theMalayFixtureIsABinteNameLeftUnsplit() {
        assertThat(DevPatientFixtures.MALAY_PATIENT).isEqualTo(new Patient(
                UUID.fromString("00000000-0000-4000-8000-00000000c002"),
                "S9212345A",
                new PatientName("Siti Nurhaliza binte Ahmad", null, List.of()),
                AdministrativeGender.FEMALE,
                LocalDate.of(1992, 8, 21)));
    }

    @Test
    void theIndianFixtureIsASingleGivenNameWithNoFamilyName() {
        assertThat(DevPatientFixtures.SINGLE_NAME_PATIENT).isEqualTo(new Patient(
                UUID.fromString("00000000-0000-4000-8000-00000000c003"),
                "T0312345C",
                new PatientName("Kumaravel", null, List.of("Kumaravel")),
                AdministrativeGender.UNKNOWN,
                LocalDate.of(2003, 11, 5)));
    }

    @Test
    void theEurasianFixtureIsGivenNamesFirstAndAMultiWordFamilyName() {
        assertThat(DevPatientFixtures.EURASIAN_PATIENT).isEqualTo(new Patient(
                UUID.fromString("00000000-0000-4000-8000-00000000c004"),
                "S7812345B",
                new PatientName("Marcus Anthony de Souza", "de Souza", List.of("Marcus", "Anthony")),
                AdministrativeGender.OTHER,
                LocalDate.of(1978, 6, 30)));
    }

    @Test
    void theFixturesBetweenThemCarryEveryGenderCode() {
        assertThat(DevPatientFixtures.ALL).extracting(Patient::gender)
                .containsExactlyInAnyOrder(AdministrativeGender.values());
    }

    @Test
    void everyFixtureNricIsUnique() {
        assertThat(DevPatientFixtures.ALL).extracting(Patient::nric).doesNotHaveDuplicates();
    }

    @Test
    void everyFixtureNricHasTheNricShape() {
        assertThat(DevPatientFixtures.ALL).extracting(Patient::nric)
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
     * person's, and a real-looking value cannot slip into the fixtures later.
     */
    @Test
    void everyFixtureNricFailsTheCheckLetterAlgorithm() {
        assertThat(DevPatientFixtures.ALL).extracting(Patient::nric).allSatisfy(nric ->
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
