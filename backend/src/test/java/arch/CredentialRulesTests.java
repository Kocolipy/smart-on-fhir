package arch;

import static arch.PlantedClasses.named;
import static arch.PlantedClasses.violations;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules in {@link ArchitectureTest} that keep a credential's stored form away from the web
 * adapters — a connector token's digest and a SCIM User's password hash — each shown reporting a
 * planted controller and passing the projection it hands out instead. The service's own
 * controllers comply, so the rules alone would never be seen to fail.
 *
 * <p>The rules read a class's package, so each planted class is written as bytecode into a
 * directory of its own by {@link PlantedClasses#named}.
 */
class CredentialRulesTests {

    private static final String CONTROLLER = "com.example.backend.scim.controller.PlantedController";

    @TempDir
    Path planted;

    @Test
    void a_web_adapter_reaching_a_connector_tokens_stored_form_is_reported() {
        named(CONTROLLER).reaching("com.example.backend.scim.domain.ConnectorTokenDigest",
                        "com.example.backend.scim.domain.ScimConnectorToken",
                        "com.example.backend.scim.domain.ScimConnectorToken$Planted")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.a_connector_token_digest_never_reaches_a_web_adapter,
                        planted))
                .contains("ConnectorTokenDigest")
                .contains("ScimConnectorToken>")
                .contains("ScimConnectorToken$Planted");
    }

    @Test
    void a_web_adapter_reaching_the_token_projection_and_a_use_case_reaching_the_digest_are_not_reported() {
        named(CONTROLLER).reaching("com.example.backend.scim.application.ConnectorTokenSummary")
                .plantUnder(planted);
        named("com.example.backend.scim.application.PlantedService")
                .reaching("com.example.backend.scim.domain.ConnectorTokenDigest")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.a_connector_token_digest_never_reaches_a_web_adapter,
                        planted))
                .isEmpty();
    }

    @Test
    void a_web_adapter_reaching_the_user_aggregate_is_reported() {
        named(CONTROLLER).reaching("com.example.backend.scim.domain.ScimUser",
                        "com.example.backend.scim.domain.ScimUser$Planted")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.a_scim_user_credential_never_reaches_a_web_adapter,
                        planted))
                .contains("ScimUser>")
                .contains("ScimUser$Planted");
    }

    @Test
    void a_web_adapter_reaching_the_user_projection_is_not_reported() {
        named(CONTROLLER).reaching("com.example.backend.scim.application.ScimUserResource",
                        "com.example.backend.scim.domain.ScimUserRepository")
                .plantUnder(planted);

        assertThat(violations(ArchitectureTest.a_scim_user_credential_never_reaches_a_web_adapter,
                        planted))
                .isEmpty();
    }
}
