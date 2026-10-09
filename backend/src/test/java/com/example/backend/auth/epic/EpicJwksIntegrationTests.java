package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.observability.RequestIdFilter;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.Filter;
import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/auth/epic/jwks.json} with Epic Login on: public — Epic fetches it with no
 * session and no CSRF token — and serving the active and next keys, which verify the assertions
 * the application signs, with no private key material in the answer or in the log.
 *
 * <p>The switch-off half, a {@code 404}, is {@link EpicReleaseGateIntegrationTests}'.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EpicJwksIntegrationTests {

    private static final String PATH = "/api/auth/epic/jwks.json";

    private static final KeyPair ACTIVE = EpicTestKeys.p384KeyPair();

    private static final KeyPair NEXT = EpicTestKeys.p384KeyPair();

    @DynamicPropertySource
    static void epicLoginOn(DynamicPropertyRegistry registry) {
        EpicTestKeys.epicLoginOn(registry, () -> EpicTestKeys.pem(ACTIVE),
                () -> EpicTestKeys.pem(NEXT));
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ClientAssertionSigner signer;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
    }

    /** No session, no CSRF token, no credential of any kind: Epic presents none. */
    @Test
    void withTheSwitchOnTheJwksIsServedToAnAnonymousCaller() throws Exception {
        assertThat(mvc.perform(get(PATH)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theJwksListsTheActiveKeyAndThenTheNextKey() throws Exception {
        assertThat(served().getKeys()).extracting(jwk -> jwk.getKeyID())
                .containsExactly("active-2026-04", "next-2026-10");
    }

    /** What Epic does with an assertion: find its {@code kid} in this document and verify it. */
    @Test
    void anAssertionTheApplicationSignsVerifiesAgainstTheServedJwks() throws Exception {
        SignedJWT assertion = SignedJWT.parse(
                signer.sign(URI.create("https://fhir.example.org/oauth2/token")));

        assertThat(assertion.verify(new ECDSAVerifier(
                served().getKeyByKeyId(assertion.getHeader().getKeyID()).toECKey()))).isTrue();
    }

    /**
     * D22: neither private key, as its PEM or as the JWK {@code d} value, reaches the answer or
     * anything written to the console and the log while it was served.
     */
    @Test
    void noPrivateKeyMaterialReachesTheAnswerOrTheLog(CapturedOutput output) throws Exception {
        String body = mvc.perform(get(PATH)).andReturn().getResponse().getContentAsString();

        assertThat(List.of(body, output.getAll())).allSatisfy(text -> assertThat(text)
                .doesNotContain("\"d\"")
                .doesNotContain(d(ACTIVE), d(NEXT))
                .doesNotContain(pemBody(ACTIVE), pemBody(NEXT)));
    }

    private JWKSet served() throws Exception {
        MvcResult result = mvc.perform(get(PATH)).andReturn();
        return JWKSet.parse(result.getResponse().getContentAsString());
    }

    /** The private scalar as a JWK {@code d} would carry it (RFC 7518 §6.2.2.1). */
    private static String d(KeyPair pair) {
        return EpicTestKeys.base64Url48(((ECPrivateKey) pair.getPrivate()).getS());
    }

    /** The PEM's base64 with its line breaks removed, as a log line would carry it. */
    private static String pemBody(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }
}
