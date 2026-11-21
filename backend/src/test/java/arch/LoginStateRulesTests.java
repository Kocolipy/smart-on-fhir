package arch;

import static arch.PlantedClasses.named;
import static arch.PlantedClasses.violations;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The login-state rules in {@link ArchitectureTest} — the login-state module records a Lockout and
 * writes the login state, the login path alone reaches the login-state port, and the directory
 * decides none of it — each shown reporting a planted class and passing what it allows. The
 * service's own classes comply, so the rules alone would never be seen to fail.
 *
 * <p>The rules read a caller's name and package and the calls it makes, so each planted class is
 * written as bytecode into a directory of its own by {@link PlantedClasses#named}.
 */
class LoginStateRulesTests {

    private static final String LOGIN_STATE_MODULE =
            "com.example.backend.auth.application.LoginStateService";
    private static final String LOGIN_STATE_PORT =
            "com.example.backend.scim.domain.ScimLoginStateRepository";
    private static final String LOGIN_STATE = "com.example.backend.scim.domain.ScimLoginState";
    private static final String LOCKOUT_SET = "com.example.backend.audit.domain.Audited$LockoutSet";
    private static final String DORMANCY_LOCKOUT =
            "com.example.backend.audit.domain.Audited$DormancyLockout";

    @TempDir
    Path planted;

    // ---- recording a Lockout ------------------------------------------------------------------

    @Test
    void a_path_other_than_the_login_state_module_recording_a_lockout_is_reported() {
        named("com.example.backend.auth.application.PlantedDormancyService")
                .constructing(LOCKOUT_SET).constructing(DORMANCY_LOCKOUT).plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_login_state_module_records_a_lockout,
                        planted))
                .contains("Audited$LockoutSet")
                .contains("Audited$DormancyLockout");
    }

    @Test
    void the_login_state_module_and_the_audit_slice_building_a_lockout_are_not_reported() {
        named(LOGIN_STATE_MODULE).constructing(LOCKOUT_SET).plantUnder(planted);
        named("com.example.backend.audit.application.PlantedAuditService")
                .constructing(DORMANCY_LOCKOUT).plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_login_state_module_records_a_lockout,
                        planted))
                .isEmpty();
    }

    // ---- writing the login state --------------------------------------------------------------

    @Test
    void a_class_other_than_the_login_state_module_writing_the_login_state_is_reported() {
        named("com.example.backend.auth.application.PlantedUnlockService")
                .calling(LOGIN_STATE_PORT, "imposeLock", "()V")
                .calling(LOGIN_STATE_PORT, "writeChange", "()V")
                .plantUnder(planted);
        // Through the adapter that implements the port, which is the same write.
        named("com.example.backend.scim.application.PlantedSeedService")
                .calling("com.example.backend.scim.infrastructure.persistence"
                        + ".ScimUserPersistenceAdapter", "completePasswordChange", "()V")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_login_state_module_writes_the_login_state,
                        planted))
                .contains("PlantedUnlockService").contains("imposeLock").contains("writeChange")
                .contains("PlantedSeedService").contains("completePasswordChange");
    }

    @Test
    void the_login_state_module_writing_and_another_class_reading_the_login_state_are_not_reported() {
        named(LOGIN_STATE_MODULE).calling(LOGIN_STATE_PORT, "imposeLock", "()V")
                .plantUnder(planted);
        named("com.example.backend.auth.application.PlantedDormancyService")
                .calling(LOGIN_STATE_PORT, "findDormantUnlockedUserIds", "()V")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_login_state_module_writes_the_login_state,
                        planted))
                .isEmpty();
    }

    // ---- reaching the login-state port --------------------------------------------------------

    @Test
    void the_directory_reaching_the_login_state_port_is_reported() {
        named("com.example.backend.scim.application.PlantedService").reaching(LOGIN_STATE_PORT)
                .plantUnder(planted);
        named("com.example.backend.auth.controller.PlantedController").reaching(LOGIN_STATE_PORT)
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_login_path_reaches_the_login_state_port,
                        planted))
                .contains("PlantedService")
                .contains("PlantedController");
    }

    @Test
    void the_login_path_and_the_ports_adapter_reaching_it_are_not_reported() {
        named("com.example.backend.auth.application.PlantedService").reaching(LOGIN_STATE_PORT)
                .plantUnder(planted);
        named("com.example.backend.scim.application.PlantedService")
                .reaching("com.example.backend.scim.domain.ScimUserRepository").plantUnder(planted);
        named("com.example.backend.scim.infrastructure.persistence.PlantedAdapter")
                .reaching(LOGIN_STATE_PORT).plantUnder(planted);

        assertThat(violations(ArchitectureTest.only_the_login_path_reaches_the_login_state_port,
                        planted))
                .isEmpty();
    }

    // ---- the directory decides no login state -------------------------------------------------

    @Test
    void the_directory_applying_a_login_state_transition_is_reported() {
        named("com.example.backend.scim.application.PlantedService")
                .calling(LOGIN_STATE, "withDormancyLock",
                        "(Ljava/time/Instant;)Lcom/example/backend/scim/domain/ScimLoginState;")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.the_directory_decides_no_login_state, planted))
                .contains("PlantedService")
                .contains("withDormancyLock");
    }

    @Test
    void the_login_path_applying_a_transition_and_the_directory_reading_the_state_are_not_reported() {
        named("com.example.backend.auth.application.PlantedService")
                .calling(LOGIN_STATE, "withDormancyLock",
                        "(Ljava/time/Instant;)Lcom/example/backend/scim/domain/ScimLoginState;")
                .plantUnder(planted);
        named("com.example.backend.scim.application.PlantedReader")
                .calling(LOGIN_STATE, "failedLoginAttempts", "()I")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.the_directory_decides_no_login_state, planted))
                .isEmpty();
    }
}
