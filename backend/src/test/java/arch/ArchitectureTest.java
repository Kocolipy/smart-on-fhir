package arch;

import com.example.backend.web.ApiExceptionHandler;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

@com.tngtech.archunit.junit.AnalyzeClasses(
    packages = "com.example.backend",
    importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class ArchitectureTest {

    // Cycles

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_cyclic_dependencies =
        SlicesRuleDefinition.slices()
            .matching("com.example.backend.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("Cyclic dependencies prevent independent module development and deployment");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_package_cycles =
        SlicesRuleDefinition.slices()
            .matching("com.example.backend.(**)")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("A cycle between two packages, even inside one module, means neither can be"
                    + " understood or changed without the other");

    // Naming Conventions

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_classes_in_default_package =
        noClasses()
            .should().haveNameMatching("^[^.]+$")
            .allowEmptyShould(true)
            .as("No class should reside in the default (unnamed) package");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_controller =
        classes()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().haveSimpleNameEndingWith("Controller")
            .allowEmptyShould(true)
            .because("Naming conventions aid discoverability, whichever package a controller lives in");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_service =
        classes()
            .that().resideInAPackage("..application")
            .and().areAnnotatedWith(Service.class)
            .and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("Service")
            .allowEmptyShould(true)
            .because("An application-layer bean is a use-case service and is named as one");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_adapter =
        classes()
            .that().resideInAPackage("..infrastructure..")
            .and().areTopLevelClasses()
            .and().areMetaAnnotatedWith(Component.class)
            .should().haveSimpleNameEndingWith("Adapter")
            .allowEmptyShould(true)
            .because("An infrastructure bean implements a port for the layers inside it and is"
                    + " named as an adapter");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_jpa_repository =
        classes()
            .that().areInterfaces()
            .and().areAssignableTo(org.springframework.data.repository.Repository.class)
            .should().haveSimpleNameEndingWith("JpaRepository")
            .allowEmptyShould(true)
            .because("A Spring Data repository is told apart from the domain repository port it"
                    + " backs by its name");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_entity =
        classes()
            .that().areAnnotatedWith(Entity.class)
            .should().haveSimpleNameEndingWith("Entity")
            .allowEmptyShould(true)
            .because("A persistence entity is told apart from the domain type it maps by its name");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule naming_conventions_configuration =
        classes()
            .that().areAnnotatedWith(Configuration.class)
            .should().haveSimpleNameEndingWith("Config")
            .orShould().haveSimpleNameEndingWith("Configuration")
            .allowEmptyShould(true)
            .because("Naming conventions aid discoverability");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule exception_naming_convention =
        classes()
            .that().areAssignableTo(Exception.class)
            .should().haveSimpleNameEndingWith("Exception")
            .allowEmptyShould(true)
            .because("Exception naming must be explicit");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule exception_names_are_exceptions =
        classes()
            .that().haveSimpleNameEndingWith("Exception")
            .should().beAssignableTo(Throwable.class)
            .allowEmptyShould(true)
            .because("A class named *Exception that cannot be thrown misleads every reader");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_generic_exception_throws =
        noMethods()
            .should().declareThrowableOfType(Exception.class)
            .orShould().declareThrowableOfType(Throwable.class)
            .allowEmptyShould(true)
            .because("A method that throws Exception tells its caller nothing about what can go wrong");

    // Class Containment

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule class_containment_controller =
        classes()
            .that().haveSimpleNameEndingWith("Controller")
            .and().areTopLevelClasses()
            .should().resideInAPackage("..controller")
            .allowEmptyShould(true)
            .because("Web adapters live in the module's controller package");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule class_containment_service =
        classes()
            .that().haveSimpleNameEndingWith("Service")
            .and().areTopLevelClasses()
            .should().resideInAPackage("..application")
            .allowEmptyShould(true)
            .because("Use-case services live in the module's application package");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule class_containment_adapter =
        classes()
            .that().haveSimpleNameEndingWith("Adapter")
            .and().areTopLevelClasses()
            .should().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true)
            .because("Adapters live in the module's infrastructure packages");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule class_containment_jpa_repository =
        classes()
            .that().haveSimpleNameEndingWith("JpaRepository")
            .and().areTopLevelClasses()
            .should().resideInAPackage("..infrastructure.persistence")
            .allowEmptyShould(true)
            .because("Spring Data repositories are a persistence adapter detail");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule configuration_classes_in_config_package =
        classes()
            .that().areAnnotatedWith(Configuration.class)
            .should().resideInAPackage("..config..")
            .allowEmptyShould(true)
            .because("Configuration classes must be isolated in config packages");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule entities_only_in_entity_packages =
        classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..entity..")
            .allowEmptyShould(true)
            .because("Entity classes belong to the persistence adapter, never the framework-free domain");

    // Layer Boundaries

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule onion_architecture =
        onionArchitecture()
            .domainModels("com.example.backend..domain..")
            .domainServices("com.example.backend..domain.service..")
            .applicationServices("com.example.backend..application..")
            .adapter("persistence", "com.example.backend..infrastructure.persistence..")
            .adapter("session", "com.example.backend..infrastructure.session..")
            .adapter("transaction", "com.example.backend..infrastructure.transaction..")
            .adapter("request", "com.example.backend..infrastructure.request..")
            .adapter("alert", "com.example.backend..infrastructure.alert..")
            .adapter("web", "com.example.backend..controller..")
            .adapter("config", "com.example.backend..config..")
            .withOptionalLayers(true)
            .because("Dependencies point inward: adapters depend on application, application on domain, domain on nothing");

    // The onion rule above cannot see this: the auth.epic root matches no layer, yet it holds
    // adapter-grade code (Micrometer meters, key signing, bound properties).
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule inner_layers_never_reach_the_epic_root =
        noClasses()
            .that().resideInAnyPackage("com.example.backend..application..", "com.example.backend..domain..")
            .should().dependOnClassesThat().resideInAPackage("com.example.backend.auth.epic")
            .because("Dependencies point inward: the Epic package root is adapter code, which the"
                    + " application and domain layers must not depend on");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_domain_infrastructure_imports =
        noClasses()
            .that().resideInAnyPackage("..domain..", "..model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..infrastructure..", "..adapter..", "..web..", "..controller..",
                    "..config..", "..application..")
            .allowEmptyShould(true)
            .because("Domain must remain infrastructure-agnostic");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_domain_framework_imports =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "jakarta.servlet..", "org.hibernate..")
            .allowEmptyShould(true)
            .because("The domain is plain Java, so it can be tested and reasoned about without a framework");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_spring_annotations_in_domain =
        noClasses()
            .that().resideInAnyPackage("..entity..", "..model..", "..domain..")
            .and().resideOutsideOfPackage("..mapper..")
            .and().areTopLevelClasses()
            .should().beAnnotatedWith(Component.class)
            .orShould().beAnnotatedWith(Service.class)
            .orShould().beAnnotatedWith(Repository.class)
            .orShould().beAnnotatedWith(Controller.class)
            .allowEmptyShould(true)
            .because("Domain objects must not carry Spring stereotype annotations");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_business_logic_in_controllers =
        noClasses()
            .that().resideInAPackage("..controller..")
            .or().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..repository..", "..persistence..", "com.example.backend..dao..")
            .allowEmptyShould(true)
            .because("Controllers must delegate to services, not access repositories directly");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_transactional_outside_service =
        noClasses()
            .that().resideInAnyPackage("..controller..", "..domain..")
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
            .orShould().beAnnotatedWith("jakarta.transaction.Transactional")
            .allowEmptyShould(true)
            .because("Transaction management belongs at the use-case/service boundary");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_jpa_outside_persistence_adapters =
        noClasses()
            .that().resideOutsideOfPackage("..infrastructure.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.data..", "org.hibernate..")
            .allowEmptyShould(true)
            .because("JPA, Spring Data and Hibernate are a persistence adapter's implementation detail");

    // ADR 0010: every protected handler declares its Permission with method security, beside the
    // code it protects; the chain repeats it as a backstop. One mechanism, on handlers only.
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule one_method_security_mechanism =
        noMethods()
            .should().beAnnotatedWith("org.springframework.security.access.prepost.PostAuthorize")
            .orShould().beAnnotatedWith("org.springframework.security.access.annotation.Secured")
            .orShould().beAnnotatedWith("jakarta.annotation.security.RolesAllowed")
            .allowEmptyShould(true)
            .because("ADR 0010: a Permission is declared with @PreAuthorize alone, so every"
                    + " declaration reads the same way and the authorization contract test finds them");

    // ADR 0010: the declaration sits on the web adapter's handler, never deeper or on a type
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule permissions_are_declared_on_handlers =
        methods()
            .that().areAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .should().beDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .andShould().beDeclaredInClassesThat().resideInAPackage("..controller..")
            .because("ADR 0010: each protected operation declares its Permission where the"
                    + " operation is defined, so the requirement is read next to the route");

    // ADR 0010: a class-level declaration would silently cover a handler added later
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_class_level_method_security =
        noClasses()
            .should().beAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .orShould().beAnnotatedWith("org.springframework.security.access.annotation.Secured")
            .orShould().beAnnotatedWith("jakarta.annotation.security.RolesAllowed")
            .allowEmptyShould(true)
            .because("ADR 0010: every operation names its own Permission; a class-wide one would"
                    + " grant a handler added later a Permission nobody chose for it");

    /**
     * The session-chain adapters whose handlers need NO Permission: self-service (authenticated
     * only) and the public login surface. Every other application-chain handler is protected.
     */
    private static final java.util.Set<String> SELF_SERVICE_OR_PUBLIC_CONTROLLERS = java.util.Set.of(
            "com.example.backend.auth.controller.AuthController",
            "com.example.backend.auth.controller.SelfController",
            // Our public JWKS, which Epic fetches with no session (D14).
            "com.example.backend.auth.epic.controller.EpicJwksController",
            // The EHR launch, which the browser opens from Epic with no session of ours (D1).
            "com.example.backend.auth.epic.controller.EpicLaunchController",
            "com.example.backend.session.controller.SessionController");

    // ADR 0010: a forgotten declaration fails closed at build time, not only at the chain
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule every_protected_handler_declares_a_permission =
        methods()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.PostMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.PutMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.PatchMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.DeleteMapping.class)
            .or().areAnnotatedWith(org.springframework.web.bind.annotation.RequestMapping.class)
            .and().areDeclaredInClassesThat(new DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                    "are application-chain adapters that are neither self-service nor public") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaClass owner) {
                    return owner.isAnnotatedWith(RestController.class)
                            && !SELF_SERVICE_OR_PUBLIC_CONTROLLERS.contains(owner.getName())
                            // The SCIM protocol's own handlers are the bearer chain's, which
                            // authorizes connector tokens itself; only its admin adapter is ours.
                            && !(owner.getPackageName().equals("com.example.backend.scim.controller")
                                    && owner.getSimpleName().startsWith("Scim"));
                }
            })
            .should().beAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .because("ADR 0010: every protected operation declares the Permission it requires;"
                    + " a handler added without one would otherwise be held only by the chain's"
                    + " backstop rule, if anyone remembered to write it");

    // Module Boundaries

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule authorization_depends_on_no_module =
        noClasses()
            .that().resideInAPackage("com.example.backend.authorization..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.backend.audit..", "com.example.backend.auth..",
                    "com.example.backend.counter..", "com.example.backend.lifecycle..",
                    "com.example.backend.observability..", "com.example.backend.scheduling..",
                    "com.example.backend.scim..", "com.example.backend.session..",
                    "com.example.backend.web..")
            .because("the Permission vocabulary and the role mapping are read by both the session"
                    + " and the SCIM side, so they know neither; a Group id is checked against the"
                    + " directory by scim, not here");
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule observability_depends_on_no_module =
        noClasses()
            .that().resideInAPackage("com.example.backend.observability..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.backend.audit..", "com.example.backend.auth..",
                    "com.example.backend.counter..", "com.example.backend.lifecycle..",
                    "com.example.backend.scim..", "com.example.backend.session..",
                    "com.example.backend.web..")
            .allowEmptyShould(true)
            .because("observability is the shared base every module logs through, so it knows none of them");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule audit_depends_only_on_observability =
        noClasses()
            .that().resideInAPackage("com.example.backend.audit..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.backend.auth..", "com.example.backend.counter..",
                    "com.example.backend.lifecycle..", "com.example.backend.scim..",
                    "com.example.backend.session..", "com.example.backend.web..")
            .allowEmptyShould(true)
            .because("audit is shared by every business module, so it depends on none of them");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule scheduling_depends_on_no_business_module =
        noClasses()
            .that().resideInAPackage("com.example.backend.scheduling..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.backend.audit..", "com.example.backend.auth..",
                    "com.example.backend.counter..", "com.example.backend.lifecycle..",
                    "com.example.backend.scim..", "com.example.backend.session..",
                    "com.example.backend.web..")
            .allowEmptyShould(true)
            .because("the scheduled-job lock is shared by the auth and audit jobs (ADR 0005), so it"
                    + " knows neither of them nor any other business module");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule web_depends_on_no_feature =
        noClasses()
            .that().resideInAPackage("com.example.backend.web..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.backend.audit..", "com.example.backend.auth..",
                    "com.example.backend.counter..", "com.example.backend.lifecycle..",
                    "com.example.backend.scim..", "com.example.backend.session..")
            .allowEmptyShould(true)
            .because("SPA routing is shared plumbing and knows no business module");

    /**
     * The app-wide error handler names the controller packages it answers for, rather than
     * applying everywhere, so that it never answers for the SCIM namespace. The cost is that a
     * new controller package would be silently uncovered; this is what makes it loud.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule every_application_controller_is_covered_by_the_api_exception_handler =
        classes()
            .that().areAnnotatedWith(RestController.class)
            .and().resideOutsideOfPackage("com.example.backend.scim..")
            .should().resideInAnyPackage(apiExceptionHandlerPackages())
            .because("an application controller's unexpected failures must be logged and answered"
                    + " by ApiExceptionHandler; add the package to its basePackages");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_api_exception_handler_never_covers_the_scim_namespace =
        classes()
            .that().areAnnotatedWith(RestController.class)
            .and().resideInAnyPackage(apiExceptionHandlerPackages())
            .should().resideOutsideOfPackage("com.example.backend.scim..")
            .allowEmptyShould(true)
            .because("SCIM handlers' errors are SCIM error documents, rendered by"
                    + " ScimExceptionHandler; a generic body there is one no connector can parse");

    private static String[] apiExceptionHandlerPackages() {
        return ApiExceptionHandler.class.getAnnotation(RestControllerAdvice.class).basePackages();
    }

    // backend/AGENTS.md: nothing in scim mentions auth
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule scim_never_depends_on_auth =
        noClasses()
            .that().resideInAPackage("com.example.backend.scim..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.backend.auth..", "com.example.backend.counter..",
                    "com.example.backend.lifecycle..", "com.example.backend.session..")
            .allowEmptyShould(true)
            .because("backend/AGENTS.md: auth reaches SCIM through scim.domain ports, and nothing in"
                    + " scim mentions auth");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule leaf_modules_have_no_dependents =
        noClasses()
            .that().resideOutsideOfPackages(
                    "com.example.backend.counter..", "com.example.backend.lifecycle..",
                    "com.example.backend.session..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.backend.counter..", "com.example.backend.lifecycle..",
                    "com.example.backend.session..")
            .allowEmptyShould(true)
            .because("counter, lifecycle and session are leaves: removing one must not break another module");

    // Dependency Injection

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_field_injection =
        noFields()
            .that().areDeclaredInClassesThat().areTopLevelClasses()
            .should().beAnnotatedWith(Autowired.class)
            .allowEmptyShould(true)
            .because("Constructor injection is required for testability and immutability");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_new_inside_service =
        noClasses()
            .that().resideInAPackage("..application")
            .and().areTopLevelClasses()
            .should().callConstructorWhere(new DescribedPredicate<JavaConstructorCall>(
                    "the target is a Service, Repository or Adapter") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    String name = call.getTargetOwner().getSimpleName();
                    return name.endsWith("Service") || name.endsWith("Repository") || name.endsWith("Adapter");
                }
            })
            .allowEmptyShould(true)
            .because("Collaborators are injected, so a service can be tested with a stand-in for each");

    // Code Quality

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule utility_classes_private_constructor =
        classes()
            .that().haveSimpleNameEndingWith("Util")
            .or().haveSimpleNameEndingWith("Utils")
            .should().haveOnlyPrivateConstructors()
            .allowEmptyShould(true)
            .because("Utility classes must not be instantiable");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_test_imports_in_production =
        noClasses()
            .that().resideOutsideOfPackages("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("org.junit..", "org.mockito..", "org.testng..")
            .allowEmptyShould(true)
            .because("Test dependencies must not leak into production code");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_system_out =
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .allowEmptyShould(true);

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_dependency_on_deprecated =
        noClasses()
            .should().dependOnClassesThat().areAnnotatedWith(Deprecated.class)
            .allowEmptyShould(true)
            .because("Deprecated code is on its way out; new callers keep it alive");

    // JPA / Persistence

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule entities_have_required_annotations =
        classes()
            .that().areAnnotatedWith(Entity.class)
            .should().beAnnotatedWith(Table.class)
            .allowEmptyShould(true)
            .because("Explicit table mapping prevents runtime surprises");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_entity_in_controllers =
        noClasses()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .allowEmptyShould(true)
            .because("Controllers must use DTOs, not entities, to prevent lazy-loading issues and data exposure");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule repositories_must_be_interfaces =
        classes()
            .that().resideInAnyPackage("..domain", "..infrastructure.persistence")
            .and().haveSimpleNameEndingWith("Repository")
            .and().areTopLevelClasses()
            .should().beInterfaces()
            .allowEmptyShould(true)
            .because("Domain repositories are ports and Spring Data repositories are generated;"
                    + " both are interfaces");

    // Spring Annotations

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule controllers_must_be_annotated =
        classes()
            .that().haveSimpleNameEndingWith("Controller")
            .and().areTopLevelClasses()
            .and().areNotInterfaces()
            .should().beAnnotatedWith(RestController.class)
            .orShould().beAnnotatedWith(Controller.class)
            .allowEmptyShould(true)
            .because("Controllers must be annotated for Spring component scanning");

    // ADR-derived

    /**
     * The logging context is written through one class or not at all.
     *
     * <p>Logs must carry correlation ids and never a userName, filter expression,
     * password, bearer value, hash or cookie value. That is a property of every
     * call site at once, so it cannot be held by reviewing them: scattered
     * {@code MDC.put} calls would each need checking, and a new one would be added
     * by someone who never read this rule. {@code LogContext} exposes three named
     * setters and no general-purpose one, so with this rule in force "what can
     * enter the logging context" has a single, readable answer.
     *
     * <p>The exemption is written as a name pattern rather than one fully qualified
     * name because {@code LogContext.Scope} — the nested class whose {@code close()}
     * restores a key's previous value — is a class of its own to ArchUnit. Naming
     * only the outer class would fail the rule on the contract's own
     * implementation, so the pattern covers {@code LogContext} and its nested
     * classes and nothing else.
     */
    // ADR 0003: ECS structured logging with redaction
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule mdc_is_only_touched_by_the_log_context =
        noClasses()
            .that().haveNameNotMatching("com\\.example\\.backend\\.observability\\.LogContext(\\$.*)?")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.slf4j.MDC")
            .allowEmptyShould(true)
            .because("LogContext is the only way anything writes to the logging context, so"
                    + " the three permitted keys are the only keys that exist");

    /**
     * The audit trail's boundary admits no free text.
     *
     * <p>Every audit-worthy event's actor and subject must be the account's stable
     * id, and no event body may carry a username, a password or a bearer value.
     * That is a property of every present and future call site at once, so it cannot
     * be held by reviewing them — the leak that matters is the event someone records
     * next year in a flow no test covers. It can be held by a signature: a username,
     * a password and a bearer value are all {@code String}s, so a boundary that
     * declares no {@code String} parameter cannot be handed one.
     *
     * <p>The event's own textual fields are filled in behind this boundary, from
     * vocabularies the audit slice owns — a resource type, a status class, an error
     * code that is a reason name, a route template. Adding a {@code String} parameter
     * to {@link com.example.backend.audit.domain.AuditTrail} to pass one of them in
     * from outside is what this rule refuses.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_audit_trail_boundary_admits_no_free_text =
        methods()
            .that().areDeclaredInClassesThat()
                .haveFullyQualifiedName("com.example.backend.audit.domain.AuditTrail")
            .should(new ArchCondition<JavaMethod>("declare no String parameter") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    method.getRawParameterTypes().stream()
                        .filter(parameter -> parameter.getName().equals("java.lang.String"))
                        .forEach(parameter -> events.add(SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " declares a String parameter; an audit"
                                + " event's actor, subject and classification are ids and"
                                + " closed sets, and a String is how a username or a"
                                + " credential would get in")));
                }
            })
            .allowEmptyShould(true)
            .because("A username, a password and a bearer value are all Strings, so the one"
                    + " boundary that records events admits none");

    /**
     * SCIM attribute facts have one owner: {@link com.example.backend.scim.domain.ScimResourceSchema}
     * declares every attribute — its type, case sensitivity, cardinality, mutability and
     * returnability — and discovery, request reading, projection, PATCH path classification and the
     * query vocabulary all read it from there. A class that built its own
     * {@code ScimAttribute} would be a second fact table, which is exactly what issue #104
     * removed: the two would drift, and discovery would advertise one comparison while a filter
     * applied another.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_resource_schema_defines_scim_attributes =
        noClasses()
            .that().doNotHaveFullyQualifiedName("com.example.backend.scim.domain.ScimResourceSchema")
            .and().doNotHaveFullyQualifiedName("com.example.backend.scim.domain.ScimAttribute")
            .should().callCodeUnitWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    new DescribedPredicate<com.tngtech.archunit.core.domain.AccessTarget>(
                            "a ScimAttribute constructor, factory or modifier") {
                        @Override
                        public boolean test(com.tngtech.archunit.core.domain.AccessTarget target) {
                            return target.getOwner().getFullName()
                                            .equals("com.example.backend.scim.domain.ScimAttribute")
                                    && java.util.Set.of("<init>", "singular", "complex",
                                            "multiValuedComplex", "asRequired", "asCaseExact", "unique",
                                            "canonical", "references")
                                            .contains(target.getName());
                        }
                    }))
            .allowEmptyShould(true)
            .because("attribute facts are declared once, in ScimResourceSchema, and every"
                    + " protocol path derives from that definition (issue #104)");

    /**
     * Password acceptance has one owner: {@link com.example.backend.scim.domain.PasswordAcceptance}
     * reads a User's password history to refuse reuse and records the accepted hash, and nothing
     * else in the service does either. A caller that reached the history port itself would be
     * writing its own reuse predicate or remembering a hash outside the acceptance sequence; the
     * persistence adapter that implements the port is the one other class that may name it.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_password_acceptance_uses_the_password_history =
        noClasses()
            .that().doNotHaveFullyQualifiedName("com.example.backend.scim.domain.PasswordAcceptance")
            .and().resideOutsideOfPackage("com.example.backend.scim.infrastructure.persistence..")
            .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo(
                                    "com.example.backend.scim.domain.ScimPasswordHistoryRepository"))))
            .allowEmptyShould(true)
            .because("callers decide on a new password through PasswordAcceptance, so reuse and"
                    + " remembering an accepted hash have one implementation (issue #103)");

    /**
     * Which resources a SCIM write advances has one owner:
     * {@code scim.infrastructure.persistence.RepresentationChange}. An adapter describes the write
     * and the module decides; an adapter that advanced versions itself would be working out who
     * moved on its own, which is how Group create with members once advanced none of them — and a
     * miss is silent, a stale ETag rather than an error.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_representation_change_advances_versions =
        noClasses()
            .that().doNotHaveFullyQualifiedName(
                    "com.example.backend.scim.infrastructure.persistence.RepresentationChange")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is ScimResourceJpaRepository.advanceVersions",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                            call.getName().equals("advanceVersions")
                                    && call.getTargetOwner().isAssignableTo(
                                            "com.example.backend.scim.infrastructure.persistence"
                                                    + ".ScimResourceJpaRepository")))
            .allowEmptyShould(true)
            .because("RepresentationChange decides which resources' versions a write advances,"
                    + " so every write path applies one rule (issue #137)");

    /**
     * What a newly imposed failure Lockout implies has one owner:
     * {@code auth.application.FailureCounter}. It persists the counted failure and, on the
     * transition into the lock, records {@code LOCKOUT_SET} and revokes the User's Sessions after
     * the commit. A counting path that recorded the Lockout itself would be repeating the edge
     * test, and a copy that forgot the revocation would read like its neighbour. The audit slice,
     * which implements the trail, is the one other place that may name the method.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule only_the_failure_counter_records_a_lockout =
        noClasses()
            .that().doNotHaveFullyQualifiedName(
                    "com.example.backend.auth.application.FailureCounter")
            .and().resideOutsideOfPackage("com.example.backend.audit..")
            .should().callMethodWhere(DescribedPredicate.describe(
                    "target is AuditTrail.recordLockoutSet",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                            call.getName().equals("recordLockoutSet")
                                    && call.getTargetOwner().isAssignableTo(
                                            "com.example.backend.audit.domain.AuditTrail")))
            .allowEmptyShould(true)
            .because("FailureCounter carries out what a newly imposed Lockout implies, so every"
                    + " counting path audits it and revokes Sessions the same way (issue #139)");

    /**
     * An audit event is constructed inside the audit slice and nowhere else.
     *
     * <p>The boundary rule above is only worth having while
     * {@link com.example.backend.audit.domain.AuditEvent} is unreachable from
     * outside: a caller that could build an event itself could put anything in the
     * textual fields the boundary keeps it away from.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule audit_events_are_built_only_inside_the_audit_slice =
        noClasses()
            .that().resideOutsideOfPackage("com.example.backend.audit..")
            .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.example.backend.audit.domain.AuditEvent")
            .allowEmptyShould(true)
            .because("The audit slice owns what goes into an event body; a caller says only"
                    + " what happened, through AuditTrail");

    /**
     * A connector token's stored form never reaches a web adapter.
     *
     * <p>{@link com.example.backend.scim.domain.ConnectorTokenDigest} and the token
     * aggregate that holds one are the two types a credential's stored form lives in. A
     * controller that depended on either could render it, and a token hash in a response
     * body is a credential leak even though it is not the credential: it is offline-
     * crackable in a way the 256-bit value is not only because nothing else about the
     * value is known.
     *
     * <p>Held as a rule rather than by review because the safe shape already exists —
     * {@code ConnectorTokenSummary} has no field a digest could occupy — and what a rule
     * adds is that a future adapter cannot reach around it by taking the domain type
     * directly. The exemptions are written as name PATTERNS because ArchUnit treats a
     * nested class as its own class, so an exact name would miss a record nested in a
     * controller.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule a_connector_token_digest_never_reaches_a_web_adapter =
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.scim\\.domain\\."
                        + "(ConnectorTokenDigest|ScimConnectorToken)(\\$.*)?")
            .allowEmptyShould(true)
            .because("A web adapter returns projections that have no field a token digest"
                    + " could be written into; reaching the domain type directly is how"
                    + " that guarantee would be bypassed");

    /**
     * A SCIM User's credential never reaches a web adapter.
     *
     * <p>{@link com.example.backend.scim.domain.ScimUser} is the one type a User's password
     * hash lives in, and the SCIM adapter renders whatever it is handed. Handing it the
     * projection instead — {@code ScimUserResource}, which has no field a hash could occupy
     * — is what makes "the password never appears in any response" a property of the shape
     * rather than of the renderer's care.
     *
     * <p>Held as a rule rather than by review because the safe shape already exists and what
     * a rule adds is that a future handler cannot reach around it by taking the domain type
     * directly. The name is a PATTERN because ArchUnit treats a nested class as its own
     * class, so an exact name would miss a record nested in a controller.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule a_scim_user_credential_never_reaches_a_web_adapter =
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.scim\\.domain\\.ScimUser(\\$.*)?")
            .allowEmptyShould(true)
            .because("A web adapter renders the projection, which has no field a password"
                    + " hash could be written into; reaching the aggregate directly is how"
                    + " that guarantee would be bypassed");

    /**
     * Epic's tokens never reach a response.
     *
     * <p>{@link com.example.backend.auth.domain.EpicTokenSet} holds the access token, refresh
     * token and {@code id_token} an Epic Login kept for its session (ADR 0013, addendum
     * 2026-10-09), and {@link com.example.backend.auth.domain.EpicTokens} hands them out. They are
     * for the backend to act at Epic on the clinician's behalf, never for the browser, so no REST
     * controller may depend on either: a handler that could take them could render them. The one
     * web adapter that writes them, Epic Login's success handler, is a filter-chain handler and
     * answers only with a redirect.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule epic_tokens_never_reach_a_rest_controller =
        noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.auth\\.domain\\."
                        + "(EpicTokenSet|EpicTokens)(\\$.*)?")
            .allowEmptyShould(true)
            .because("ADR 0013 (addendum 2026-10-09): Epic's tokens are held server-side and"
                    + " never returned to the browser, so no handler can be handed them");

    /**
     * The application chain knows Epic Login through one seam.
     *
     * <p>Whether Epic Login is on, and everything it adds to the chain while it is — the release
     * gate while off; the callback filter, {@code oauth2Login}, its routes' rules and its handlers
     * while on — is the Epic module's, behind {@code EpicLogin#applyTo}. The shared chain makes that
     * one call and names no other Epic type, so turning Epic Login on or off, or changing what it
     * adds, never touches the chain every other request goes through.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_application_chain_knows_epic_login_through_one_seam =
        noClasses()
            .that().haveFullyQualifiedName("com.example.backend.auth.config.SecurityConfig")
            .should().dependOnClassesThat(DescribedPredicate.describe(
                    "are Epic types other than EpicLogin",
                    (com.tngtech.archunit.core.domain.JavaClass type) ->
                            type.getPackageName().startsWith("com.example.backend.auth.epic")
                                    && !type.getName().equals(
                                            "com.example.backend.auth.epic.EpicLogin")))
            .because("Epic Login plugs into the application chain at one seam, EpicLogin, which"
                    + " owns its gate, routes and handlers");

    /**
     * A connector token's stored form does not reach the audit slice either.
     *
     * <p>The audit boundary already refuses a {@code String}, which is what a plaintext
     * value is. This closes the other shape: an event body cannot be handed a digest or
     * a token aggregate to render, so "no bearer value in an audit event" holds for the
     * hash as well as for the value.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_audit_slice_never_sees_a_connector_token =
        noClasses()
            .that().resideInAPackage("com.example.backend.audit..")
            .should().dependOnClassesThat()
                .resideInAPackage("com.example.backend.scim..")
            .allowEmptyShould(true)
            .because("The audit slice records a connector by its stable id and has no"
                    + " reason to reach the credential types at all");

    /**
     * The self-read's projection has nowhere to put lockout state or a failure run.
     *
     * <p>{@code SelfRecord} is serialised as-is, so what it cannot hold cannot reach the wire.
     * Two halves, because either alone leaves a way round: a component NAMED for the lock or
     * the run, and a component TYPED as the login state that carries both. The name is a
     * pattern so the nested {@code Group} record is held too.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_self_read_has_no_field_for_lockout_or_failures =
        noFields()
            .that().areDeclaredInClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.auth\\.application\\.SelfRecord(\\$.*)?")
            .should().haveNameMatching("(?i).*(lock|fail|attempt).*")
            .because("Telling a caller how close it is to a lock helps an attacker guessing its"
                    + " password more than the owner; the self-read omits it by shape");

    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_self_read_cannot_carry_the_login_state =
        noClasses()
            .that().haveNameMatching("com\\.example\\.backend\\.auth\\.application\\.SelfRecord(\\$.*)?")
            .should().dependOnClassesThat()
                .haveNameMatching("com\\.example\\.backend\\.scim\\.domain\\.(ScimLoginState|ScimUser)(\\$.*)?")
            .because("The login state holds the failure run, the lock and the hash; a projection"
                    + " component typed as it would publish all three under an innocent name");

    /**
     * The self-read's handlers take nothing from the request that could name a User.
     *
     * <p>The User is the one the session belongs to. A path variable, query parameter, header or
     * body on this adapter would be the first place a client-supplied identifier could enter, so
     * the rule refuses the parameter rather than trusting a handler to ignore it.
     */
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule the_self_read_takes_no_identifier_from_the_request =
        methods()
            .that().areDeclaredIn(com.example.backend.auth.controller.SelfController.class)
            .and().areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
            .should(new ArchCondition<JavaMethod>("declare no request-bound parameter") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    method.getParameters().stream()
                            .filter(parameter -> parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.PathVariable.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.RequestParam.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.RequestHeader.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.RequestBody.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.CookieValue.class)
                                    || parameter.isAnnotatedWith(
                                            org.springframework.web.bind.annotation.ModelAttribute.class)
                                    || parameter.getRawType().isEquivalentTo(java.util.UUID.class)
                                    || parameter.getRawType().isEquivalentTo(String.class))
                            .forEach(parameter -> events.add(SimpleConditionEvent.violated(
                                    method, method.getFullName() + " binds " + parameter
                                            + " from the request")));
                }
            })
            .because("The self-read resolves the User from the session alone, so no request value"
                    + " can name somebody else");

    // ADR 0001: count login attempts on the login path
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule web_adapters_never_call_authentication_manager =
        noClasses()
            .that().resideInAnyPackage("..controller..", "..application..")
            .and().haveNameNotMatching("com\\.example\\.backend\\.auth\\.application\\.LoginService(\\$.*)?")
            .should().dependOnClassesThat().areAssignableTo(
                    org.springframework.security.authentication.AuthenticationManager.class)
            .allowEmptyShould(true)
            .because("ADR 0001: submitted credentials are authenticated only through LoginService,"
                    + " which is where every attempt is counted");

    // backend/AGENTS.md: sessions end through the AccountSessions port
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule application_never_reaches_spring_session =
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.session..")
            .allowEmptyShould(true)
            .because("backend/AGENTS.md: application services end sessions through the"
                    + " AccountSessions port, never a session repository");

    // ADR 0002: revoke sessions after commit
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule after_commit_only_through_the_port =
        noClasses()
            .that().resideOutsideOfPackage("..infrastructure.transaction..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.transaction.support.TransactionSynchronization")
            .orShould().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.transaction.support.TransactionSynchronizationManager")
            .allowEmptyShould(true)
            .because("ADR 0002: after-commit work is registered through the AfterCommit port, so"
                    + " there is one place that decides what runs once a transaction commits");

    // ADR 0002: revoke sessions after commit
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_transactional_event_listeners =
        noMethods()
            .should().beAnnotatedWith("org.springframework.transaction.event.TransactionalEventListener")
            .allowEmptyShould(true)
            .because("ADR 0002: after-commit work goes through the AfterCommit port, not an event"
                    + " listener the publisher cannot see");

    // ADR 0001: count login attempts on the login path
    @com.tngtech.archunit.junit.ArchTest
    static final ArchRule no_authentication_event_listeners =
        noClasses()
            .should().dependOnClassesThat().resideInAPackage(
                    "org.springframework.security.authentication.event..")
            .allowEmptyShould(true)
            .because("ADR 0001: login attempts are recorded on the login path itself, never by"
                    + " an authentication event listener");
}
