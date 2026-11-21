package arch;

import static arch.PlantedClasses.violations;
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
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules in {@link ArchitectureTest} that hold who signs a session in and ends it, who ends a
 * User's Sessions, the signed-in session's one module, where submitted credentials are
 * authenticated, Epic Login's one seam in the application chain, which services the login and
 * administration paths share, who writes the directory, and the self-read's shape — each shown
 * reporting a planted class and passing what it allows. The service's own classes comply, so the
 * rules alone would never be seen to fail.
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

    // ---- signing a session in, and ending the caller's -----------------------------------------

    private static final String SESSION_ESTABLISHMENT =
            "com.example.backend.auth.controller.SessionEstablishment";
    private static final String HTTP_SESSION = "jakarta.servlet.http.HttpSession";
    private static final String SECURITY_CONTEXT_HOLDER =
            "org.springframework.security.core.context.SecurityContextHolder";
    private static final String WRITE_COOKIE_VALUE =
            "(Lorg/springframework/session/web/http/CookieSerializer$CookieValue;)V";

    @Test
    void an_adapter_other_than_the_login_completion_establishing_a_session_is_reported() {
        assertThat(violationsOncePlanted(
                ArchitectureTest.only_login_completion_establishes_a_session,
                "com.example.backend.auth.controller.PlantedController",
                SESSION_ESTABLISHMENT))
                .contains("PlantedController");
    }

    @Test
    void the_login_completion_establishing_a_session_and_a_controller_reaching_it_are_not_reported() {
        PlantedClasses.plant(planted, "com.example.backend.auth.controller.LoginCompletion",
                SESSION_ESTABLISHMENT);
        PlantedClasses.plant(planted, "com.example.backend.auth.controller.PlantedController",
                "com.example.backend.auth.controller.LoginCompletion");

        assertThat(violationsOncePlanted(
                ArchitectureTest.only_login_completion_establishes_a_session,
                "com.example.backend.auth.controller.LoginCompletion$Step",
                SESSION_ESTABLISHMENT))
                .isEmpty();
    }

    @Test
    void the_login_surface_invalidating_or_clearing_by_hand_is_reported() {
        PlantedClasses.named("com.example.backend.auth.controller.InvalidatesController")
                .calling(HTTP_SESSION, "invalidate", "()V").plantUnder(planted);
        PlantedClasses.named("com.example.backend.auth.application.ClearsService")
                .calling(SECURITY_CONTEXT_HOLDER, "clearContext", "()V").plantUnder(planted);

        assertThat(violations(
                        ArchitectureTest.only_session_ending_ends_the_callers_session, planted))
                .contains("InvalidatesController")
                .contains("ClearsService");
    }

    @Test
    void session_ending_and_its_named_exceptions_ending_a_session_and_other_session_calls_are_not_reported() {
        for (String exempt : new String[] {
                "com.example.backend.auth.controller.SessionEnding$Sequence",
                "com.example.backend.auth.controller.AbsoluteSessionLifetimeFilter",
                "com.example.backend.auth.epic.controller.EpicLaunchController"}) {
            PlantedClasses.named(exempt).calling(HTTP_SESSION, "invalidate", "()V")
                    .calling(SECURITY_CONTEXT_HOLDER, "clearContext", "()V").plantUnder(planted);
        }
        PlantedClasses.named("com.example.backend.auth.controller.PlantedController")
                .calling(HTTP_SESSION, "getId", "()Ljava/lang/String;").plantUnder(planted);

        assertThat(violations(
                        ArchitectureTest.only_session_ending_ends_the_callers_session, planted))
                .isEmpty();
    }

    @Test
    void a_class_other_than_session_ending_expiring_the_session_cookie_is_reported() {
        PlantedClasses.named("com.example.backend.auth.controller.ExpiresController")
                .calling("org.springframework.session.web.http.DefaultCookieSerializer",
                        "writeCookieValue", WRITE_COOKIE_VALUE)
                .plantUnder(planted);

        assertThat(violations(
                        ArchitectureTest.only_session_ending_expires_the_session_cookie, planted))
                .contains("ExpiresController");
    }

    @Test
    void session_ending_expiring_and_another_class_reading_the_session_cookie_are_not_reported() {
        PlantedClasses.named("com.example.backend.auth.controller.SessionEnding")
                .calling("org.springframework.session.web.http.CookieSerializer",
                        "writeCookieValue", WRITE_COOKIE_VALUE)
                .plantUnder(planted);
        PlantedClasses.named("com.example.backend.auth.controller.PlantedController")
                .calling("org.springframework.session.web.http.CookieSerializer",
                        "readCookieValues", "(Ljakarta/servlet/http/HttpServletRequest;)Ljava/util/List;")
                .plantUnder(planted);

        assertThat(violations(
                        ArchitectureTest.only_session_ending_expires_the_session_cookie, planted))
                .isEmpty();
    }

    // ---- the signed-in session's one module ---------------------------------------------------

    private static final String SESSION_ATTRIBUTES =
            "com.example.backend.auth.domain.SignedInSession$Attributes";

    @Test
    void a_third_adapter_of_the_signed_in_sessions_attributes_is_reported() {
        PlantedClasses.named("com.example.backend.auth.controller.PlantedSessionAttributes")
                .implementing(SESSION_ATTRIBUTES).plantUnder(planted);

        assertThat(violations(
                        ArchitectureTest.the_signed_in_session_has_one_adapter_per_session_api,
                        planted))
                .contains("PlantedSessionAttributes");
    }

    @Test
    void the_servlet_and_spring_session_adapters_are_not_reported() throws ClassNotFoundException {
        assertThat(violations(
                ArchitectureTest.the_signed_in_session_has_one_adapter_per_session_api,
                new ClassFileImporter().importClasses(HttpSessionAttributes.class,
                Class.forName("com.example.backend.auth.infrastructure.session"
                + ".SpringSessionAttributes"))))
                .isEmpty();
    }

    @Test
    void a_second_type_named_signed_in_session_is_reported() {
        assertThat(violationsOncePlanted(ArchitectureTest.one_type_is_named_signed_in_session,
                        "com.example.backend.auth.controller.SignedInSession"))
                .contains("com.example.backend.auth.controller.SignedInSession");
    }

    @Test
    void the_signed_in_session_module_is_not_reported() {
        assertThat(violations(ArchitectureTest.one_type_is_named_signed_in_session,
                new ClassFileImporter().importClasses(
                        com.example.backend.auth.domain.SignedInSession.class)))
                .isEmpty();
    }

    // ---- credentials through the login path ---------------------------------------------------

    @Test
    void a_web_adapter_or_use_case_reaching_the_authentication_manager_is_reported() {
        PlantedClasses.plant(planted, "com.example.backend.auth.controller.PlantedController",
                "org.springframework.security.authentication.AuthenticationManager");

        assertThat(violationsOncePlanted(
                ArchitectureTest.web_adapters_never_call_authentication_manager,
                "com.example.backend.auth.application.PlantedService",
                "org.springframework.security.authentication.ProviderManager"))
                .contains("PlantedController")
                .contains("PlantedService");
    }

    @Test
    void the_login_service_and_the_configuration_reaching_the_authentication_manager_are_not_reported() {
        PlantedClasses.plant(planted, "com.example.backend.auth.application.LoginService",
                "org.springframework.security.authentication.AuthenticationManager");

        assertThat(violationsOncePlanted(
                ArchitectureTest.web_adapters_never_call_authentication_manager,
                "com.example.backend.auth.config.PlantedConfig",
                "org.springframework.security.authentication.AuthenticationManager"))
                .isEmpty();
    }

    @Test
    void a_use_case_reaching_spring_session_is_reported() {
        assertThat(violationsOncePlanted(ArchitectureTest.application_never_reaches_spring_session,
                        "com.example.backend.auth.application.PlantedService",
                        "org.springframework.session.FindByIndexNameSessionRepository"))
                .contains("PlantedService");
    }

    @Test
    void a_session_adapter_reaching_spring_session_is_not_reported() {
        assertThat(violationsOncePlanted(ArchitectureTest.application_never_reaches_spring_session,
                        "com.example.backend.auth.infrastructure.session.PlantedAdapter",
                        "org.springframework.session.FindByIndexNameSessionRepository"))
                .isEmpty();
    }

    // ---- Epic Login's one seam in the application chain ---------------------------------------

    private static final String SECURITY_CONFIG = "com.example.backend.auth.config.SecurityConfig";

    @Test
    void the_application_chain_reaching_an_epic_type_other_than_its_seam_is_reported() {
        assertThat(violationsOncePlanted(
                        ArchitectureTest.the_application_chain_knows_epic_login_through_one_seam,
                        SECURITY_CONFIG, "com.example.backend.auth.epic.EpicLaunchValue",
                        "com.example.backend.auth.epic.controller.EpicLaunchController"))
                .contains("com.example.backend.auth.epic.EpicLaunchValue")
                .contains("com.example.backend.auth.epic.controller.EpicLaunchController");
    }

    @Test
    void the_application_chain_reaching_epic_login_alone_is_not_reported() {
        assertThat(violationsOncePlanted(
                        ArchitectureTest.the_application_chain_knows_epic_login_through_one_seam,
                        SECURITY_CONFIG, "com.example.backend.auth.epic.EpicLogin"))
                .isEmpty();
    }

    // ---- the login and administration paths ---------------------------------------------------

    @Test
    void an_administrative_adapter_reaching_a_login_service_is_reported() {
        assertThat(violationsOncePlanted(
                ArchitectureTest.the_administration_path_shares_no_login_service,
                "com.example.backend.auth.controller.AdminPlantedController",
                "com.example.backend.auth.application.LoginIdentityService"))
                .contains("AdminPlantedController");
    }

    @Test
    void an_administration_service_reaching_a_login_service_is_reported() {
        assertThat(violationsOncePlanted(
                ArchitectureTest.the_administration_path_shares_no_login_service,
                "com.example.backend.auth.application.PlantedAdministrationService",
                "com.example.backend.auth.application.LoginService"))
                .contains("PlantedAdministrationService");
    }

    @Test
    void an_administrative_adapter_reaching_the_login_state_module_is_not_reported() {
        // Unlock reports to the login-state module; it is shared by design, not a login service.
        assertThat(violationsOncePlanted(
                ArchitectureTest.the_administration_path_shares_no_login_service,
                "com.example.backend.auth.controller.AdminPlantedController",
                "com.example.backend.auth.application.LoginStateService"))
                .isEmpty();
    }

    @Test
    void a_login_path_class_reaching_an_administration_service_is_reported() {
        assertThat(violationsOncePlanted(
                ArchitectureTest.the_login_path_shares_no_administration_service,
                "com.example.backend.auth.application.LoginPlantedService",
                "com.example.backend.auth.application.IdentityAdministrationService"))
                .contains("LoginPlantedService");
    }

    @Test
    void a_login_path_class_reaching_another_application_service_is_not_reported() {
        assertThat(violationsOncePlanted(
                ArchitectureTest.the_login_path_shares_no_administration_service,
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

    /**
     * {@code the_self_read_takes_no_identifier_from_the_request} selects the methods declared in
     * {@code SelfController}, by name, so its subject is one class the service already owns and
     * whose handlers comply: the rule is shown failing on a planted class file carrying that exact
     * name, written with the ClassFile API into a directory of its own and imported alone.
     *
     * <p>Only the planted path is imported. Spring's own annotation types still resolve from the
     * classpath, which is what the meta-annotation check needs: {@code @GetMapping} is annotated
     * {@code @RequestMapping}, and that is how a handler is told from an ordinary method.
     */
    private static final String SELF_CONTROLLER = "com.example.backend.auth.controller.SelfController";

    @Test
    void a_self_read_handler_taking_a_path_variable_is_reported() {
        plantSelfController(SelfHandler.handler("read", "java.util.UUID")
                .withParameterAnnotation("org.springframework.web.bind.annotation.PathVariable"));

        assertThat(violations(ArchitectureTest.the_self_read_takes_no_identifier_from_the_request,
                new ClassFileImporter().importPath(planted)))
                .contains("SelfController.read")
                .contains("from the request");
    }

    @Test
    void a_self_read_handler_taking_a_request_parameter_is_reported() {
        plantSelfController(SelfHandler.handler("read", "java.lang.String")
                .withParameterAnnotation("org.springframework.web.bind.annotation.RequestParam"));

        assertThat(violations(ArchitectureTest.the_self_read_takes_no_identifier_from_the_request,
                new ClassFileImporter().importPath(planted)))
                .contains("SelfController.read")
                .contains("from the request");
    }

    /**
     * The compliant shape, with a non-handler beside it: the session and the authentication are
     * what the self-read resolves the User from, and a method with no mapping annotation is not a
     * request handler at all, so the {@code String} it takes is none of the rule's business.
     */
    @Test
    void a_self_read_handler_taking_only_the_session_and_a_non_handler_are_not_reported() {
        plantSelfController(
                SelfHandler.handler("read", "org.springframework.security.core.Authentication",
                        "jakarta.servlet.http.HttpSession"),
                SelfHandler.plainMethod("describe", "java.lang.String"));

        assertThat(violations(ArchitectureTest.the_self_read_takes_no_identifier_from_the_request,
                new ClassFileImporter().importPath(planted)))
                .isEmpty();
    }

    /** One method of the planted {@code SelfController}: its name, parameters and annotations. */
    private record SelfHandler(String name, java.util.List<String> parameters, boolean mapped,
            String parameterAnnotation) {

        /** A request handler: annotated {@code @GetMapping}, which meta-annotates @RequestMapping. */
        static SelfHandler handler(String name, String... parameters) {
            return new SelfHandler(name, java.util.List.of(parameters), true, null);
        }

        /** A method with no mapping annotation, which the rule does not select. */
        static SelfHandler plainMethod(String name, String... parameters) {
            return new SelfHandler(name, java.util.List.of(parameters), false, null);
        }

        /** Puts {@code annotation} on the first parameter. */
        SelfHandler withParameterAnnotation(String annotation) {
            return new SelfHandler(name, parameters, mapped, annotation);
        }
    }

    private void plantSelfController(SelfHandler... methods) {
        byte[] bytes = ClassFile.of().build(ClassDesc.of(SELF_CONTROLLER), controller -> {
            controller.withFlags(ClassFile.ACC_PUBLIC);
            controller.withSuperclass(ConstantDescs.CD_Object);
            for (SelfHandler method : methods) {
                controller.withMethod(method.name(),
                        java.lang.constant.MethodTypeDesc.of(ConstantDescs.CD_void,
                                method.parameters().stream().map(ClassDesc::of)
                                        .toArray(ClassDesc[]::new)),
                        ClassFile.ACC_PUBLIC, body -> {
                            body.withCode(code -> code.return_());
                            if (method.mapped()) {
                                body.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(
                                        ClassDesc.of("org.springframework.web.bind.annotation"
                                                + ".GetMapping"))));
                            }
                            if (method.parameterAnnotation() != null) {
                                body.with(RuntimeVisibleParameterAnnotationsAttribute.of(
                                        java.util.List.of(java.util.List.of(Annotation.of(
                                                ClassDesc.of(method.parameterAnnotation()))))));
                            }
                        });
            }
        });
        PlantedClasses.write(planted, SELF_CONTROLLER, bytes);
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

    /**
     * {@code rule}'s violations once {@code name}, holding a field per type, is planted beside any
     * class the test planted already.
     */
    private String violationsOncePlanted(ArchRule rule, String name, String... reaches) {
        PlantedClasses.plant(planted, name, reaches);
        return violations(rule, planted);
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
