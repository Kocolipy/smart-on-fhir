package arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.auth.application.SelfRecord;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.auth.controller.HttpSessionAttributes;
import com.example.backend.auth.domain.AccountSessions;
import com.example.backend.auth.epic.EpicLaunchValue;
import com.example.backend.auth.infrastructure.session.AccountSessionsAdapter;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules in {@link ArchitectureTest} that hold who ends a User's Sessions, which services the
 * login and administration paths share, who writes the directory, and the self-read's shape —
 * each shown reporting a planted class and passing what it allows. The service's own classes
 * comply, so the rules alone would never be seen to fail.
 *
 * <p>The path rules read a class's name and package, so their planted classes are written as
 * bytecode into a directory of their own by {@link PlantedClasses#plant}, as in
 * {@link ModuleRulesTests}: one field per type reached, which ArchUnit reads as a dependency on it.
 */
class SessionAndIdentityRulesTests {

    @TempDir
    Path planted;

    // ---- Session revocation ------------------------------------------------------------------

    @Test
    void a_class_other_than_the_revocation_module_reaching_the_port_is_reported() {
        assertThat(violations(
                ArchitectureTest.only_session_revocation_reaches_the_account_sessions_port,
                new ClassFileImporter().importClasses(EndsSessionsDirectly.class)))
                .contains("EndsSessionsDirectly");
    }

    @Test
    void the_revocation_module_and_the_ports_adapter_are_not_reported() {
        assertThat(violations(
                ArchitectureTest.only_session_revocation_reaches_the_account_sessions_port,
                new ClassFileImporter().importClasses(SessionRevocationService.class,
                        AccountSessionsAdapter.class, AccountSessions.class,
                        ReadsTheDirectory.class)))
                .isEmpty();
    }

    // ---- the session's attributes -------------------------------------------------------------

    @Test
    void a_class_reading_a_servlet_session_attribute_is_reported() {
        assertThat(violations(ArchitectureTest.only_the_named_owners_touch_session_attributes,
                new ClassFileImporter().importClasses(ReadsAServletSessionAttribute.class)))
                .contains("ReadsAServletSessionAttribute");
    }

    @Test
    void a_class_writing_a_spring_session_attribute_is_reported() {
        assertThat(violations(ArchitectureTest.only_the_named_owners_touch_session_attributes,
                new ClassFileImporter().importClasses(WritesASpringSessionAttribute.class)))
                .contains("WritesASpringSessionAttribute");
    }

    @Test
    void the_named_owners_and_other_session_calls_are_not_reported() throws ClassNotFoundException {
        assertThat(violations(ArchitectureTest.only_the_named_owners_touch_session_attributes,
                new ClassFileImporter().importClasses(HttpSessionAttributes.class,
                        EpicLaunchValue.class,
                        Class.forName("com.example.backend.auth.infrastructure.session"
                                + ".SpringSessionAttributes"),
                        ReadsTheSessionId.class)))
                .isEmpty();
    }

    // ---- the login and administration paths ---------------------------------------------------

    @Test
    void an_administrative_adapter_reaching_a_login_service_is_reported() {
        assertThat(violations(ArchitectureTest.the_administration_path_shares_no_login_service,
                        "com.example.backend.auth.controller.AdminPlantedController",
                        "com.example.backend.auth.application.LoginIdentityService"))
                .contains("AdminPlantedController");
    }

    @Test
    void an_administration_service_reaching_a_login_service_is_reported() {
        assertThat(violations(ArchitectureTest.the_administration_path_shares_no_login_service,
                        "com.example.backend.auth.application.PlantedAdministrationService",
                        "com.example.backend.auth.application.LoginService"))
                .contains("PlantedAdministrationService");
    }

    @Test
    void an_administrative_adapter_reaching_the_login_state_module_is_not_reported() {
        // Unlock reports to the login-state module; it is shared by design, not a login service.
        assertThat(violations(ArchitectureTest.the_administration_path_shares_no_login_service,
                        "com.example.backend.auth.controller.AdminPlantedController",
                        "com.example.backend.auth.application.LoginStateService"))
                .isEmpty();
    }

    @Test
    void a_login_path_class_reaching_an_administration_service_is_reported() {
        assertThat(violations(ArchitectureTest.the_login_path_shares_no_administration_service,
                        "com.example.backend.auth.application.LoginPlantedService",
                        "com.example.backend.auth.application.IdentityAdministrationService"))
                .contains("LoginPlantedService");
    }

    @Test
    void a_login_path_class_reaching_another_application_service_is_not_reported() {
        assertThat(violations(ArchitectureTest.the_login_path_shares_no_administration_service,
                        "com.example.backend.auth.application.LoginPlantedService",
                        "com.example.backend.auth.application.LoginStateService"))
                .isEmpty();
    }

    // ---- the directory's writes ---------------------------------------------------------------

    @Test
    void a_user_write_outside_the_directory_is_reported() {
        assertThat(violations(ArchitectureTest.only_the_directory_writes_users_and_groups,
                new ClassFileImporter().importClasses(DeletesAUser.class)))
                .contains("DeletesAUser");
    }

    @Test
    void a_group_write_by_method_reference_outside_the_directory_is_reported() {
        assertThat(violations(ArchitectureTest.only_the_directory_writes_users_and_groups,
                new ClassFileImporter().importClasses(RemovesAMemberByReference.class)))
                .contains("RemovesAMemberByReference");
    }

    @Test
    void a_directory_read_outside_the_directory_is_not_reported() {
        assertThat(violations(ArchitectureTest.only_the_directory_writes_users_and_groups,
                new ClassFileImporter().importClasses(ReadsTheDirectory.class)))
                .isEmpty();
    }

    // ---- the self-read's shape ----------------------------------------------------------------

    /** Without {@code SelfRecord} the rules check nothing, and fail rather than pass. */
    @Test
    void the_self_read_rules_fail_on_a_class_set_without_the_self_read() {
        JavaClasses withoutTheSelfRead =
                new ClassFileImporter().importClasses(ReadsTheDirectory.class);

        assertThatThrownBy(() -> ArchitectureTest.the_self_read_has_no_field_for_lockout_or_failures
                        .evaluate(withoutTheSelfRead))
                .hasMessageContaining("failed to check any classes");
        assertThatThrownBy(() -> ArchitectureTest.the_self_read_cannot_carry_the_login_state
                        .evaluate(withoutTheSelfRead))
                .hasMessageContaining("failed to check any classes");
    }

    @Test
    void the_self_read_rules_check_the_self_read() {
        JavaClasses theSelfRead = new ClassFileImporter().importPackagesOf(SelfRecord.class)
                .that(com.tngtech.archunit.base.DescribedPredicate.describe("the self-read",
                        type -> type.getName().startsWith(SelfRecord.class.getName())));

        assertThat(violations(ArchitectureTest.the_self_read_has_no_field_for_lockout_or_failures,
                theSelfRead))
                .isEmpty();
        assertThat(violations(ArchitectureTest.the_self_read_cannot_carry_the_login_state,
                theSelfRead))
                .isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static String violations(ArchRule rule, JavaClasses classes) {
        EvaluationResult result = rule.evaluate(classes);
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

    /** {@code rule}'s violations on one planted class, {@code name}, holding a field per type. */
    private String violations(ArchRule rule, String name, String... reaches) {
        return violations(rule, new ClassFileImporter().importPath(PlantedClasses.plant(planted, name, reaches)));
    }


    // ---- planted classes ----------------------------------------------------------------------

    static class EndsSessionsDirectly {
        private AccountSessions sessions;
    }

    static class DeletesAUser {
        void delete(ScimUserRepository users) {
            users.deleteById(UUID.randomUUID(), null);
        }
    }

    static class RemovesAMemberByReference {
        interface MemberRemoval {
            boolean remove(UUID group, UUID user, java.time.Instant at);
        }

        MemberRemoval remover(ScimGroupRepository groups) {
            return groups::removeMember;
        }
    }

    static class ReadsAServletSessionAttribute {
        Object read(jakarta.servlet.http.HttpSession session) {
            return session.getAttribute("planted");
        }
    }

    static class WritesASpringSessionAttribute {
        void write(org.springframework.session.Session session) {
            session.setAttribute("planted", "value");
        }
    }

    static class ReadsTheSessionId {
        String read(jakarta.servlet.http.HttpSession session) {
            return session.getId();
        }
    }

    static class ReadsTheDirectory {
        void read(ScimUserRepository users, ScimGroupRepository groups) {
            users.findById(UUID.randomUUID());
            groups.existsById(UUID.randomUUID());
        }
    }
}
