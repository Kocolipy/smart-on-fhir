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
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/auth/epic/jwks.json} with Epic Login on: the active and next keys it serves
 * verify the assertions the application signs, with no private key material in the answer or in
 * the log. That it answers a caller with no session, listing the active key and then the next, is
 * {@code ApiContractFixtureTests}' "epic jwks" fixture.
 *
 * <p>The switch-off half, a {@code 404}, is {@link EpicReleaseGateIntegrationTests}'.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
@ExtendWith(OutputCaptureExtension.class)
class EpicJwksIntegrationTests extends EpicLoginOnContext {

    private static final String PATH = "/api/auth/epic/jwks.json";

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
                .doesNotContain(d(ACTIVE_KEY), d(NEXT_KEY))
                .doesNotContain(pemBody(ACTIVE_KEY), pemBody(NEXT_KEY)));
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
