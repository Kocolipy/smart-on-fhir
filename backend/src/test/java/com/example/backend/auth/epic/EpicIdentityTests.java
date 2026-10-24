package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/**
 * Epic's verified identity, the principal that carries the token response's launch context to the
 * success handler within the one callback request, and never past it (ADR 0012, D8).
 */
class EpicIdentityTests {

    @Test
    void anEpicIdentityCarriesItsLaunchContextToTheSuccessHandler() {
        EpicIdentity identity = new EpicIdentity(idToken(),
                LaunchContext.of(Map.of("patient", "pat-1")), FhircastHubMembers.none());

        assertThat(EpicIdentity.launchContextOf(
                new TestingAuthenticationToken(identity, null)).fragment())
                .isEqualTo("patient=pat-1");
    }

    /**
     * The launch context is {@code transient}: an identity that went through serialization — the
     * way a session attribute is stored — comes back without it (D8).
     */
    @Test
    void aSerializedEpicIdentityCarriesNoLaunchContext() {
        EpicIdentity identity = new EpicIdentity(idToken(),
                LaunchContext.of(Map.of("patient", "pat-1", "encounter", "enc-1")),
                FhircastHubMembers.none());

        assertThat(EpicIdentity.launchContextOf(
                new TestingAuthenticationToken(roundTripped(identity), null)).fragment())
                .isEqualTo("launch");
    }

    @Test
    void anEpicIdentityHoldsNoAuthorityOfEpics() {
        assertThat(new EpicIdentity(idToken(), LaunchContext.none(), FhircastHubMembers.none())
                .getAuthorities()).isEmpty();
    }

    @Test
    void anotherKindOfPrincipalCarriesNoLaunchContext() {
        assertThat(EpicIdentity.launchContextOf(
                new TestingAuthenticationToken("someone", null)).fragment())
                .isEqualTo("launch");
    }

    // ---- the FHIRcast hub members (ADR 0016) ----------------------------------------------------

    @Test
    void anEpicIdentityCarriesItsHubMembersToTheSuccessHandler() {
        EpicIdentity identity = new EpicIdentity(idToken(), LaunchContext.none(),
                new FhircastHubMembers("http://127.0.0.1:8103/hub", 7));

        assertThat(EpicIdentity.fhircastHubOf(new TestingAuthenticationToken(identity, null)))
                .isEqualTo(new FhircastHubMembers("http://127.0.0.1:8103/hub", 7));
    }

    /** Transient as the launch context is: a serialized identity carries no hub members. */
    @Test
    void aSerializedEpicIdentityCarriesNoHubMembers() {
        EpicIdentity identity = new EpicIdentity(idToken(), LaunchContext.none(),
                new FhircastHubMembers("http://127.0.0.1:8103/hub", "topic-1"));

        assertThat(EpicIdentity.fhircastHubOf(
                new TestingAuthenticationToken(roundTripped(identity), null)))
                .isEqualTo(new FhircastHubMembers(null, null));
    }

    @Test
    void anotherKindOfPrincipalCarriesNoHubMembers() {
        assertThat(EpicIdentity.fhircastHubOf(new TestingAuthenticationToken("someone", null)))
                .isEqualTo(new FhircastHubMembers(null, null));
    }

    /** Through the serializer Spring Session's Redis repository writes session attributes with. */
    private static Object roundTripped(Object value) {
        JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();
        return serializer.deserialize(serializer.serialize(value));
    }

    private static OidcIdToken idToken() {
        return new OidcIdToken("token", Instant.parse("2026-10-20T00:00:00Z"),
                Instant.parse("2026-10-20T00:05:00Z"), Map.of("sub", "clinician"));
    }
}
