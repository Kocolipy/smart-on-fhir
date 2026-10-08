package com.example.backend.auth.epic.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.epic.EpicLoginProperties;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicReleaseGate;
import com.example.backend.auth.epic.EpicSigningKey;
import com.example.backend.auth.epic.EpicSigningKeys;
import com.example.backend.auth.epic.EpicTestKeys;
import com.example.backend.auth.epic.InvalidEpicConfigurationException;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The Epic Login switch and its configuration, as startup sees them: a context holding
 * {@link EpicLoginConfig} either starts with the gate it resolved, or fails to start.
 */
class EpicLoginConfigTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(EpicLoginConfig.class);

    @Test
    void withNoEpicVariablesAtAllTheContextStartsWithTheGateClosed() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EpicReleaseGate.class).open()).isFalse();
        });
    }

    /**
     * Every variable ADR 0013's configuration table names, given as the process environment
     * gives it — so this is the binding a deployment gets, relaxed-binding names included.
     */
    @Test
    void everyEpicVariableIsBoundFromTheEnvironmentAndOpensTheGate() {
        Map<String, Object> environment = new HashMap<>(validEnvironment());
        environment.put("APP_EPIC_CLIENT_NEXT_KEY", EpicTestKeys.p384Pem());
        environment.put("APP_EPIC_CLIENT_NEXT_KEY_ID", "next-2026-10");
        environment.put("APP_EPIC_CONNECT_TIMEOUT", "3s");
        environment.put("APP_EPIC_READ_TIMEOUT", "7s");

        withEnvironment(environment).run(context -> {
            assertThat(context).hasNotFailed();
            EpicLoginProperties bound = context.getBean(EpicLoginProperties.class);
            assertThat(bound.fhirBase()).isEqualTo(FHIR_BASE);
            assertThat(bound.oauthIssuer()).isEqualTo(ISSUER);
            assertThat(bound.clientId()).isEqualTo("epic-client-id");
            assertThat(bound.redirectUri()).isEqualTo(REDIRECT_URI);
            assertThat(bound.clientKey()).startsWith("-----BEGIN PRIVATE KEY-----");
            assertThat(bound.clientNextKey()).startsWith("-----BEGIN PRIVATE KEY-----");
            assertThat(bound.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(bound.readTimeout()).isEqualTo(Duration.ofSeconds(7));
            assertThat(context.getBean(EpicReleaseGate.class)).isEqualTo(new EpicReleaseGate(true));
        });
    }

    /** With the switch off nothing is parsed: there are no settings and no signing keys. */
    @Test
    void withTheSwitchOffThereAreNoSettingsAndNoSigningKeys() {
        contexts.run(context -> assertThat(context)
                .doesNotHaveBean(EpicLoginSettings.class)
                .doesNotHaveBean(EpicSigningKeys.class));
    }

    /** The accepted configuration carries each URL parsed, as the later steps use it. */
    @Test
    void theAcceptedSettingsCarryTheUrlsAsParsedUris() {
        withEnvironment(validEnvironment()).run(context -> {
            EpicLoginSettings settings = context.getBean(EpicLoginSettings.class);
            assertThat(List.of(settings.fhirBase(), settings.oauthIssuer(), settings.redirectUri()))
                    .containsExactly(URI.create(FHIR_BASE), URI.create(ISSUER),
                            URI.create(REDIRECT_URI));
        });
    }

    /** The active key is the one the PEM holds, paired with its kid, read once at startup. */
    @Test
    void theActiveSigningKeyIsThePemsKeyUnderItsKid() {
        String pem = EpicTestKeys.p384Pem();
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_KEY", pem);

        withEnvironment(environment).run(context -> {
            EpicSigningKey active = context.getBean(EpicSigningKeys.class).active();
            assertThat(List.of(active.keyId(), active.privateKey().getEncoded()))
                    .containsExactly("active-2026-04", pkcs8(pem));
        });
    }

    @Test
    void theNextSigningKeyIsItsPemsKeyUnderItsOwnKid() {
        String pem = EpicTestKeys.p384Pem();
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_NEXT_KEY", pem);
        environment.put("APP_EPIC_CLIENT_NEXT_KEY_ID", "next-2026-10");

        withEnvironment(environment).run(context -> {
            EpicSigningKey next = context.getBean(EpicSigningKeys.class).next().orElseThrow();
            assertThat(List.of(next.keyId(), next.privateKey().getEncoded()))
                    .containsExactly("next-2026-10", pkcs8(pem));
        });
    }

    /** The DER a PEM armours, decoded independently of the code under test. */
    private static byte[] pkcs8(String pem) {
        return Base64.getMimeDecoder().decode(pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", ""));
    }

    @Test
    void theTimeoutsDefaultToTwoSecondsConnectAndFiveSecondsRead() {
        withEnvironment(validEnvironment()).run(context -> {
            EpicLoginProperties bound = context.getBean(EpicLoginProperties.class);
            assertThat(List.of(bound.connectTimeout(), bound.readTimeout()))
                    .containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "APP_EPIC_FHIR_BASE", "APP_EPIC_OAUTH_ISSUER", "APP_EPIC_CLIENT_ID",
        "APP_EPIC_REDIRECT_URI", "APP_EPIC_CLIENT_KEY", "APP_EPIC_CLIENT_KEY_ID",
    })
    void withTheSwitchOnAMissingRequiredVariableFailsStartupNamingIt(String variable) {
        Map<String, Object> environment = validEnvironment();
        environment.remove(variable);

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: " + variable
                        + " is required when APP_EPIC_ENABLED is true");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "APP_EPIC_FHIR_BASE", "APP_EPIC_OAUTH_ISSUER", "APP_EPIC_CLIENT_ID",
        "APP_EPIC_REDIRECT_URI", "APP_EPIC_CLIENT_KEY", "APP_EPIC_CLIENT_KEY_ID",
    })
    void withTheSwitchOnABlankRequiredVariableFailsStartupNamingIt(String variable) {
        Map<String, Object> environment = validEnvironment();
        environment.put(variable, "   ");

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: " + variable
                        + " is required when APP_EPIC_ENABLED is true");
    }

    /** D21: outside the dev profile, an {@code http} URL is refused. */
    @ParameterizedTest
    @ValueSource(strings = {"APP_EPIC_FHIR_BASE", "APP_EPIC_OAUTH_ISSUER", "APP_EPIC_REDIRECT_URI"})
    void outsideTheDevProfileAnHttpUrlFailsStartupNamingIt(String variable) {
        Map<String, Object> environment = validEnvironment();
        environment.put(variable, "http://fhir.example.org/path");

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: " + variable
                        + " must be an absolute https URL");
    }

    /** Anything that is not an absolute URL with a host is refused the same way. */
    @ParameterizedTest
    @ValueSource(strings = {
        "fhir.example.org/path", "/api/FHIR/R4", "ftp://fhir.example.org/path", "https:///no-host",
        "https://fhir.example.org/a path with spaces", "HTTPS-but-not-a-url", "https:opaque",
    })
    void aValueThatIsNotAnAbsoluteHttpsUrlFailsStartup(String value) {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_OAUTH_ISSUER", value);

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: APP_EPIC_OAUTH_ISSUER"
                        + " must be an absolute https URL");
    }

    /** D21: the dev profile alone may use {@code http}, for the local Docker launcher. */
    @Test
    void theDevProfileMayUseHttpUrls() {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_FHIR_BASE", "http://localhost:8099/v/r4/fhir");
        environment.put("APP_EPIC_OAUTH_ISSUER", "http://localhost:8099/v/r4/fhir");
        environment.put("APP_EPIC_REDIRECT_URI", "http://localhost:8080/api/auth/epic/callback");

        withEnvironment(environment)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context.getBean(EpicReleaseGate.class).open()).isTrue());
    }

    /**
     * The dev profile alone accepts the relative {@code fhirUser} the local SMART launcher
     * issues, decided where D21's {@code http} allowance is.
     */
    @Test
    void theDevProfileAllowsARelativeFhirUser() {
        withEnvironment(validEnvironment())
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(
                        context.getBean(EpicLoginSettings.class).relativeFhirUserAllowed())
                        .isTrue());
    }

    @Test
    void outsideTheDevProfileARelativeFhirUserIsNotAllowed() {
        withEnvironment(validEnvironment())
                .run(context -> assertThat(
                        context.getBean(EpicLoginSettings.class).relativeFhirUserAllowed())
                        .isFalse());
    }

    /** Even in the dev profile, only {@code http} is added — no other scheme. */
    @Test
    void theDevProfileStillRefusesAnyOtherScheme() {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_REDIRECT_URI", "ftp://localhost/callback");

        assertThat(startupFailure(withEnvironment(environment)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))))
                .isEqualTo("Invalid Epic Login configuration: APP_EPIC_REDIRECT_URI"
                        + " must be an absolute https URL");
    }

    /** Values that are not an EC P-384 private key, each for its own reason. */
    static Stream<String> notAP384PrivateKey() {
        return Stream.of(
                "-----BEGIN PRIVATE KEY-----\nNOT-A-KEY-SENTINEL\n-----END PRIVATE KEY-----",
                "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----",
                "just some text",
                EpicTestKeys.p256Pem(),
                EpicTestKeys.p384Pem().replace("PRIVATE KEY", "PUBLIC KEY"));
    }

    @ParameterizedTest
    @MethodSource("notAP384PrivateKey")
    void anActiveKeyThatIsNotAP384PrivateKeyFailsStartupNamingIt(String pem) {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_KEY", pem);

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: APP_EPIC_CLIENT_KEY"
                        + " must be an EC P-384 private key in PKCS#8 PEM");
    }

    @ParameterizedTest
    @MethodSource("notAP384PrivateKey")
    void aNextKeyThatIsNotAP384PrivateKeyFailsStartupNamingIt(String pem) {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_NEXT_KEY", pem);
        environment.put("APP_EPIC_CLIENT_NEXT_KEY_ID", "next-2026-10");

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: APP_EPIC_CLIENT_NEXT_KEY"
                        + " must be an EC P-384 private key in PKCS#8 PEM");
    }

    /**
     * A key written on one line, as an environment file holds it: the armour and the base64 with
     * no line breaks, or with the line breaks escaped as {@code \n}.
     */
    @Test
    void aPemWrittenOnOneLineIsAccepted() {
        String pem = EpicTestKeys.p384Pem();
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_KEY", pem.replace("\n", ""));
        environment.put("APP_EPIC_CLIENT_NEXT_KEY", EpicTestKeys.p384Pem().replace("\n", "\\n"));
        environment.put("APP_EPIC_CLIENT_NEXT_KEY_ID", "next-2026-10");

        withEnvironment(environment)
                .run(context -> assertThat(context.getBean(EpicReleaseGate.class).open()).isTrue());
    }

    @Test
    void aNextKeyWithoutAKidFailsStartup() {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_NEXT_KEY", EpicTestKeys.p384Pem());

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: APP_EPIC_CLIENT_NEXT_KEY_ID"
                        + " is required when APP_EPIC_CLIENT_NEXT_KEY is set");
    }

    @Test
    void aNextKidWithoutAKeyFailsStartup() {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_NEXT_KEY_ID", "next-2026-10");

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: APP_EPIC_CLIENT_NEXT_KEY"
                        + " is required when APP_EPIC_CLIENT_NEXT_KEY_ID is set");
    }

    @Test
    void duplicateKidsFailStartup() {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_NEXT_KEY", EpicTestKeys.p384Pem());
        environment.put("APP_EPIC_CLIENT_NEXT_KEY_ID", "active-2026-04");

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: APP_EPIC_CLIENT_NEXT_KEY_ID"
                        + " must differ from APP_EPIC_CLIENT_KEY_ID");
    }

    @Test
    void withNoNextKeyThereIsTheActiveSigningKeyAlone() {
        withEnvironment(validEnvironment()).run(context ->
                assertThat(context.getBean(EpicSigningKeys.class).next()).isEmpty());
    }

    /**
     * A deployment template renders every variable, so an optional one arrives empty rather than
     * absent; empty counts as unset.
     */
    @Test
    void emptyOptionalVariablesCountAsUnset() {
        Map<String, Object> environment = validEnvironment();
        environment.put("APP_EPIC_CLIENT_NEXT_KEY", "");
        environment.put("APP_EPIC_CLIENT_NEXT_KEY_ID", "");
        environment.put("APP_EPIC_CONNECT_TIMEOUT", "");
        environment.put("APP_EPIC_READ_TIMEOUT", "");

        withEnvironment(environment).run(context -> {
            assertThat(context.getBean(EpicSigningKeys.class).next()).isEmpty();
            EpicLoginProperties bound = context.getBean(EpicLoginProperties.class);
            assertThat(List.of(bound.connectTimeout(), bound.readTimeout()))
                    .containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_EPIC_CONNECT_TIMEOUT=0s", "APP_EPIC_CONNECT_TIMEOUT=-1s",
        "APP_EPIC_READ_TIMEOUT=0s", "APP_EPIC_READ_TIMEOUT=-5s"})
    void aTimeoutThatIsNotPositiveFailsStartupNamingIt(String setting) {
        String[] variableAndValue = setting.split("=");
        Map<String, Object> environment = validEnvironment();
        environment.put(variableAndValue[0], variableAndValue[1]);

        assertThat(startupFailure(environment))
                .isEqualTo("Invalid Epic Login configuration: " + variableAndValue[0]
                        + " must be positive");
    }

    /**
     * Startup with {@code environment}, which must fail; the message of the configuration error
     * that failed it. The error carries no cause, so nothing a parser echoed can ride along.
     */
    private String startupFailure(Map<String, Object> environment) {
        return startupFailure(withEnvironment(environment));
    }

    private static String startupFailure(ApplicationContextRunner runner) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runner.run(context -> failure.set(context.getStartupFailure()));
        assertThat(failure.get()).as("startup failed").isNotNull();
        Throwable cause = failure.get();
        while (cause != null && !(cause instanceof InvalidEpicConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("the configuration error in the failure's chain").isNotNull();
        assertThat(cause.getCause()).as("the configuration error's own cause").isNull();
        return cause.getMessage();
    }

    private static final String FHIR_BASE ="https://fhir.example.org/api/FHIR/R4";

    private static final String ISSUER = "https://fhir.example.org/oauth2";

    private static final String REDIRECT_URI = "https://app.example.org/api/auth/epic/callback";

    /** The switch on and every required variable present and well formed. */
    private static Map<String, Object> validEnvironment() {
        Map<String, Object> environment = new HashMap<>();
        environment.put("APP_EPIC_ENABLED", "true");
        environment.put("APP_EPIC_FHIR_BASE", FHIR_BASE);
        environment.put("APP_EPIC_OAUTH_ISSUER", ISSUER);
        environment.put("APP_EPIC_CLIENT_ID", "epic-client-id");
        environment.put("APP_EPIC_REDIRECT_URI", REDIRECT_URI);
        environment.put("APP_EPIC_CLIENT_KEY", EpicTestKeys.p384Pem());
        environment.put("APP_EPIC_CLIENT_KEY_ID", "active-2026-04");
        return environment;
    }

    /** The context, with {@code variables} as its process environment. */
    private ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
        return contexts.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.copyOf(variables))));
    }
}
