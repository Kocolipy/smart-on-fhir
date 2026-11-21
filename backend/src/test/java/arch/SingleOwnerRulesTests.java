package arch;

import static arch.PlantedClasses.named;
import static arch.PlantedClasses.violations;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules in {@link ArchitectureTest} that give a mechanism one owner — the clock, the outbound
 * HTTP client, the logging context, the SCIM attribute facts, password acceptance, the version
 * advance, the write locks, the scheduled-job lock and schedule, the audit event, and after-commit
 * work — each shown reporting a planted class and passing the owner it names. The service's own
 * classes comply, so the rules alone would never be seen to fail.
 *
 * <p>The rules read a caller's name and package and the calls, constructions and dependencies it
 * has, so each planted class is written as bytecode into a directory of its own by
 * {@link PlantedClasses#named}.
 */
class SingleOwnerRulesTests {

    private static final String PERSISTENCE = "com.example.backend.scim.infrastructure.persistence.";
    private static final String RESOURCE_REPOSITORY = PERSISTENCE + "ScimResourceJpaRepository";
    private static final String WRITE_LOCK_MODULE = PERSISTENCE + "ScimWriteLockAdapter";
    private static final String JOB_RUN =
            "com.example.backend.scheduling.application.ScheduledJobRunService";
    private static final String SCHEDULING_CONFIGURER =
            "org.springframework.scheduling.annotation.SchedulingConfigurer";

    @TempDir
    Path planted;

    // ---- the clock ----------------------------------------------------------------------------

    @Test
    void reading_the_system_clock_is_reported() {
        named("com.example.backend.auth.application.PlantedService")
                .calling("java.time.Instant", "now", "()Ljava/time/Instant;")
                .calling("java.time.LocalDate", "now", "()Ljava/time/LocalDate;")
                .calling("java.time.ZonedDateTime", "now",
                        "(Ljava/time/ZoneId;)Ljava/time/ZonedDateTime;")
                .calling("java.time.Clock", "systemUTC", "()Ljava/time/Clock;")
                .calling("java.lang.System", "currentTimeMillis", "()J")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.time_is_read_through_the_clock_bean, planted))
                .contains("Instant.now()")
                .contains("LocalDate.now()")
                .contains("ZonedDateTime.now(java.time.ZoneId)")
                .contains("Clock.systemUTC()")
                .contains("System.currentTimeMillis()");
    }

    @Test
    void reading_the_injected_clock_and_the_configuration_reading_the_system_clock_are_not_reported() {
        named("com.example.backend.auth.application.PlantedService")
                .calling("java.time.Instant", "now", "(Ljava/time/Clock;)Ljava/time/Instant;")
                .plantUnder(planted);
        named("com.example.backend.auth.config.PlantedConfig")
                .calling("java.time.Clock", "systemUTC", "()Ljava/time/Clock;")
                .calling("java.time.Instant", "now", "()Ljava/time/Instant;")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.time_is_read_through_the_clock_bean, planted))
                .isEmpty();
    }

    // ---- the outbound HTTP client -------------------------------------------------------------

    @Test
    void an_http_client_outside_the_two_outbound_adapters_is_reported() {
        named("com.example.backend.scim.application.PlantedService")
                .reaching("org.springframework.web.client.RestClient",
                        "org.springframework.web.reactive.function.client.WebClient",
                        "java.net.http.HttpClient", "java.net.HttpURLConnection")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.one_outbound_http_client, planted))
                .contains("org.springframework.web.client.RestClient")
                .contains("org.springframework.web.reactive.function.client.WebClient")
                .contains("java.net.http.HttpClient")
                .contains("java.net.HttpURLConnection");
    }

    @Test
    void the_epic_and_patient_source_adapters_reaching_an_http_client_are_not_reported() {
        named("com.example.backend.auth.epic.config.PlantedConfig")
                .reaching("org.springframework.web.client.RestClient").plantUnder(planted);
        named("com.example.backend.patient.infrastructure.http.PlantedAdapter")
                .reaching("java.net.http.HttpClient").plantUnder(planted);

        assertThat(violations(ArchitectureTest.one_outbound_http_client, planted)).isEmpty();
    }

    // ---- the logging context ------------------------------------------------------------------

    @Test
    void a_class_other_than_the_log_context_reaching_the_mdc_is_reported() {
        named("com.example.backend.observability.LogContextHelper").reaching("org.slf4j.MDC")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.mdc_is_only_touched_by_the_log_context, planted))
                .contains("LogContextHelper");
    }

    @Test
    void the_log_context_and_its_nested_classes_reaching_the_mdc_are_not_reported() {
        named("com.example.backend.observability.LogContext").reaching("org.slf4j.MDC")
                .plantUnder(planted);
        named("com.example.backend.observability.LogContext$Scope").reaching("org.slf4j.MDC")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.mdc_is_only_touched_by_the_log_context, planted))
                .isEmpty();
    }

    // ---- the SCIM attribute facts -------------------------------------------------------------

    private static final String SCIM_ATTRIBUTE = "com.example.backend.scim.domain.ScimAttribute";

    @Test
    void a_class_other_than_the_resource_schema_defining_an_attribute_is_reported() {
        named("com.example.backend.scim.application.PlantedService")
                .constructing(SCIM_ATTRIBUTE)
                .calling(SCIM_ATTRIBUTE, "singular", "()V")
                .calling(SCIM_ATTRIBUTE, "asCaseExact", "()V")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_resource_schema_defines_scim_attributes,
                        planted))
                .contains("ScimAttribute.<init>()")
                .contains("ScimAttribute.singular()")
                .contains("ScimAttribute.asCaseExact()");
    }

    @Test
    void the_resource_schema_defining_and_another_class_reading_an_attribute_are_not_reported() {
        named("com.example.backend.scim.domain.ScimResourceSchema")
                .constructing(SCIM_ATTRIBUTE).calling(SCIM_ATTRIBUTE, "singular", "()V")
                .plantUnder(planted);
        named("com.example.backend.scim.application.PlantedService")
                .calling(SCIM_ATTRIBUTE, "isWritable", "()Z").plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_resource_schema_defines_scim_attributes,
                        planted))
                .isEmpty();
    }

    // ---- password acceptance ------------------------------------------------------------------

    private static final String PASSWORD_HISTORY =
            "com.example.backend.scim.domain.ScimPasswordHistoryRepository";

    @Test
    void a_class_other_than_password_acceptance_using_the_password_history_is_reported() {
        named("com.example.backend.scim.application.PlantedService")
                .calling(PASSWORD_HISTORY, "findRecentHashes", "()V").plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_password_acceptance_uses_the_password_history,
                        planted))
                .contains("PlantedService")
                .contains("findRecentHashes");
    }

    @Test
    void password_acceptance_and_the_persistence_adapter_using_the_history_are_not_reported() {
        named("com.example.backend.scim.domain.PasswordAcceptance")
                .calling(PASSWORD_HISTORY, "findRecentHashes", "()V").plantUnder(planted);
        named(PERSISTENCE + "PlantedAdapter")
                .calling(PASSWORD_HISTORY, "record", "()V").plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_password_acceptance_uses_the_password_history,
                        planted))
                .isEmpty();
    }

    // ---- the version advance ------------------------------------------------------------------

    @Test
    void an_adapter_advancing_versions_itself_is_reported() {
        named(PERSISTENCE + "PlantedAdapter")
                .calling(RESOURCE_REPOSITORY, "advanceVersions", "()V").plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_resource_version_statements_advance_versions,
                        planted))
                .contains("PlantedAdapter")
                .contains("advanceVersions");
    }

    @Test
    void the_version_statements_advancing_and_an_adapter_reading_resources_are_not_reported() {
        named(PERSISTENCE + "ResourceVersionStatements")
                .calling(RESOURCE_REPOSITORY, "advanceVersions", "()V").plantUnder(planted);
        named(PERSISTENCE + "PlantedAdapter")
                .calling(RESOURCE_REPOSITORY, "findById", "()V").plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_resource_version_statements_advance_versions,
                        planted))
                .isEmpty();
    }

    // ---- the write locks ----------------------------------------------------------------------

    @Test
    void a_class_other_than_the_write_lock_module_locking_a_resource_is_reported() {
        named(PERSISTENCE + "PlantedAdapter")
                .calling(RESOURCE_REPOSITORY, "lockResource", "()V").plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_write_lock_module_locks_scim_resources,
                        planted))
                .contains("PlantedAdapter")
                .contains("lockResource");
    }

    @Test
    void the_write_lock_module_locking_a_resource_is_not_reported() {
        named(WRITE_LOCK_MODULE).calling(RESOURCE_REPOSITORY, "lockResource", "()V")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_write_lock_module_locks_scim_resources,
                        planted))
                .isEmpty();
    }

    @Test
    void a_class_other_than_the_write_lock_module_locking_a_connector_or_token_is_reported() {
        named(PERSISTENCE + "PlantedAdapter")
                .calling(PERSISTENCE + "ScimConnectorJpaRepository", "lockById", "()V")
                .calling(PERSISTENCE + "ScimConnectorTokenJpaRepository", "lockById", "()V")
                .plantUnder(planted);

        assertThat(violations(
                        ArchitectureTest.only_the_write_lock_module_locks_connectors_and_their_tokens,
                        planted))
                .contains("ScimConnectorJpaRepository.lockById()")
                .contains("ScimConnectorTokenJpaRepository.lockById()");
    }

    @Test
    void the_write_lock_module_locking_and_an_adapter_reading_a_connector_are_not_reported() {
        named(WRITE_LOCK_MODULE)
                .calling(PERSISTENCE + "ScimConnectorJpaRepository", "lockById", "()V")
                .calling(PERSISTENCE + "ScimConnectorTokenJpaRepository", "lockById", "()V")
                .plantUnder(planted);
        named(PERSISTENCE + "PlantedAdapter")
                .calling(PERSISTENCE + "ScimConnectorJpaRepository", "findById", "()V")
                .plantUnder(planted);

        assertThat(violations(
                        ArchitectureTest.only_the_write_lock_module_locks_connectors_and_their_tokens,
                        planted))
                .isEmpty();
    }

    // ---- the scheduled-job lock and schedule --------------------------------------------------

    private static final String JOB_LOCK = "com.example.backend.scheduling.domain.ScheduledJobLock";

    @Test
    void a_job_body_taking_its_own_lock_is_reported() {
        named("com.example.backend.audit.application.PlantedRetentionService")
                .calling(JOB_LOCK, "tryAcquire", "()V").plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_scheduled_job_run_takes_a_job_lock,
                        planted))
                .contains("PlantedRetentionService")
                .contains("tryAcquire");
    }

    @Test
    void the_scheduled_job_run_taking_the_lock_is_not_reported() {
        named(JOB_RUN).calling(JOB_LOCK, "tryAcquire", "()V").plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_scheduled_job_run_takes_a_job_lock,
                        planted))
                .isEmpty();
    }

    @Test
    void a_method_scheduled_by_annotation_is_reported() {
        named("com.example.backend.audit.config.PlantedScheduleConfig")
                .withMethodAnnotatedWith("run",
                        "org.springframework.scheduling.annotation.Scheduled")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_scheduled_annotation, planted))
                .contains("PlantedScheduleConfig.run()");
    }

    @Test
    void a_schedule_outside_configuration_or_never_reaching_the_job_run_is_reported() {
        named("com.example.backend.audit.application.PlantedScheduleService")
                .implementing(SCHEDULING_CONFIGURER).reaching(JOB_RUN).plantUnder(planted);
        named("com.example.backend.audit.config.UnlockedScheduleConfig")
                .implementing(SCHEDULING_CONFIGURER).plantUnder(planted);

        assertThat(violations(ArchitectureTest.scheduled_jobs_run_through_the_job_run, planted))
                .contains("PlantedScheduleService")
                .contains("UnlockedScheduleConfig");
    }

    @Test
    void a_configured_schedule_whose_job_reaches_the_job_run_is_not_reported() {
        named("com.example.backend.audit.config.PlantedScheduleConfig")
                .implementing(SCHEDULING_CONFIGURER)
                .reaching("com.example.backend.audit.application.PlantedJob")
                .plantUnder(planted);
        named("com.example.backend.audit.application.PlantedJob").reaching(JOB_RUN)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.scheduled_jobs_run_through_the_job_run, planted))
                .isEmpty();
    }

    // ---- the audit event ----------------------------------------------------------------------

    private static final String AUDIT_EVENT = "com.example.backend.audit.domain.AuditEvent";

    @Test
    void a_class_outside_the_audit_slice_reaching_the_audit_event_is_reported() {
        named("com.example.backend.auth.application.PlantedService").reaching(AUDIT_EVENT)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.audit_events_are_built_only_inside_the_audit_slice,
                        planted))
                .contains("PlantedService");
    }

    @Test
    void the_audit_slice_reaching_the_audit_event_is_not_reported() {
        named("com.example.backend.audit.infrastructure.persistence.PlantedAdapter")
                .reaching(AUDIT_EVENT).plantUnder(planted);

        assertThat(violations(ArchitectureTest.audit_events_are_built_only_inside_the_audit_slice,
                        planted))
                .isEmpty();
    }

    // ---- after-commit work --------------------------------------------------------------------

    @Test
    void after_commit_work_registered_outside_the_transaction_adapter_is_reported() {
        named("com.example.backend.auth.application.PlantedService")
                .reaching("org.springframework.transaction.support.TransactionSynchronizationManager",
                        "org.springframework.transaction.support.TransactionSynchronization")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.after_commit_only_through_the_port, planted))
                .contains("TransactionSynchronizationManager")
                .contains("TransactionSynchronization>");
    }

    @Test
    void the_transaction_adapter_registering_after_commit_work_is_not_reported() {
        named("com.example.backend.observability.infrastructure.transaction.PlantedAdapter")
                .reaching("org.springframework.transaction.support.TransactionSynchronizationManager",
                        "org.springframework.transaction.support.TransactionSynchronization")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.after_commit_only_through_the_port, planted))
                .isEmpty();
    }

    @Test
    void a_transactional_event_listener_is_reported() {
        named("com.example.backend.auth.application.PlantedService")
                .withMethodAnnotatedWith("onCommit",
                        "org.springframework.transaction.event.TransactionalEventListener")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_transactional_event_listeners, planted))
                .contains("PlantedService.onCommit()");
    }

    @Test
    void a_plain_event_listener_is_not_reported() {
        named("com.example.backend.auth.application.PlantedService")
                .withMethodAnnotatedWith("onEvent",
                        "org.springframework.context.event.EventListener")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_transactional_event_listeners, planted))
                .isEmpty();
    }

    @Test
    void an_authentication_event_listener_is_reported() {
        named("com.example.backend.auth.config.PlantedListener")
                .reaching("org.springframework.security.authentication.event"
                        + ".AuthenticationSuccessEvent")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_authentication_event_listeners, planted))
                .contains("PlantedListener");
    }

    @Test
    void a_class_reaching_authentication_outside_its_events_is_not_reported() {
        named("com.example.backend.auth.config.PlantedConfig")
                .reaching("org.springframework.security.authentication.AuthenticationManager")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.no_authentication_event_listeners, planted))
                .isEmpty();
    }
}
