package com.example.backend.patient.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.observability.EcsLogCapture;
import com.example.backend.patient.InMemoryPatientStore;
import com.example.backend.patient.application.DevPatientFixtures;
import com.example.backend.patient.application.PatientSeedService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * The development patient fixtures' startup seeding: present only with the development fixtures
 * on, and — when it runs — every fixture seeded and one INFO record of the counts.
 */
class PatientSeedConfigTests {

    private static final String MESSAGE = "Development patient fixtures seeded";

    private final InMemoryPatientStore patients = new InMemoryPatientStore();

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withBean(PatientSeedService.class, () -> new PatientSeedService(patients))
            .withUserConfiguration(PatientSeedConfig.class);

    @Test
    void itIsAbsentUnlessTheDevelopmentFixturesAreOn() {
        contexts.run(context -> assertThat(context).doesNotHaveBean(PatientSeedConfig.class));
        contexts.withPropertyValues("app.dev-fixtures.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PatientSeedConfig.class));
        contexts.withPropertyValues("app.dev-fixtures.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PatientSeedConfig.class));
    }

    /** With the flag off, a start seeds nothing at all. */
    @Test
    void aStartWithTheFixturesOffSeedsNothing() {
        contexts.withPropertyValues("app.dev-fixtures.enabled=false").run(context -> {
            for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) {
                runner.run(new DefaultApplicationArguments());
            }
        });

        assertThat(patients.all()).isEmpty();
    }

    @Test
    void aStartWithTheFixturesOnSeedsAllFourFixtures() {
        contexts.withPropertyValues("app.dev-fixtures.enabled=true").run(context ->
                context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments()));

        assertThat(patients.all()).containsExactly(
                DevPatientFixtures.DEFAULT_PATIENT,
                DevPatientFixtures.MALAY_PATIENT,
                DevPatientFixtures.SINGLE_NAME_PATIENT,
                DevPatientFixtures.EURASIAN_PATIENT);
    }

    /** One INFO record with the counts, and none of a patient's field values. */
    @Test
    void aRunLogsOneInfoRecordOfItsCountsAndNoFieldValue() {
        try (EcsLogCapture logs = EcsLogCapture.attach(new StandardEnvironment())) {
            PatientSeedConfig.seed(new PatientSeedService(patients), DevPatientFixtures.ALL);
            PatientSeedConfig.seed(new PatientSeedService(patients), DevPatientFixtures.ALL);

            List<JsonNode> records = logs.records().stream()
                    .filter(entry -> entry.at("/message").asString().equals(MESSAGE))
                    .toList();
            assertThat(records).hasSize(2);
            JsonNode first = records.get(0);
            assertThat(first.at("/log/level").asString()).isEqualTo("INFO");
            assertThat(first.at("/app/event/action").asString()).isEqualTo("patient.seed");
            assertThat(first.at("/event/outcome").asString()).isEqualTo("success");
            assertThat(first.at("/patient/seed/inserted_count").asInt()).isEqualTo(4);
            assertThat(first.at("/patient/seed/present_count").asInt()).isZero();
            JsonNode second = records.get(1);
            assertThat(second.at("/patient/seed/inserted_count").asInt()).isZero();
            assertThat(second.at("/patient/seed/present_count").asInt()).isEqualTo(4);
            for (JsonNode record : records) {
                assertThat(record.toString()).doesNotContain(
                        "S1234567A", "Tan", "Wei", "Ming", "1985", "male",
                        "S9212345A", "Siti", "Nurhaliza", "binte", "1992", "female",
                        "T0312345C", "Kumaravel", "2003", "unknown",
                        "S7812345B", "Marcus", "Souza", "1978", "other",
                        "00000000-0000-4000-8000-00000000c00");
            }
        }
    }
}
