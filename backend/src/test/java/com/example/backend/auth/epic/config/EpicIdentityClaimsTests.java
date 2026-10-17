package com.example.backend.auth.epic.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * An {@code id_token} whose {@code iss} or {@code sub} begins or ends with whitespace is an invalid
 * token (ADR 0013), refused as every other claim failure is, and refused ahead of the remaining
 * checks: Spring Security's OpenID Connect validator reads {@code iss} as a URL and throws on one
 * with a space, so a padded issuer reaching it would fail the callback rather than be refused.
 */
class EpicIdentityClaimsTests {

    private static final String ISSUER = "https://epic.example.org/oauth2";

    @ParameterizedTest(name = "[{index}] sub \"{0}\"")
    @ValueSource(strings = {" epic-subject", "epic-subject ", "epic-subject\t",
            " epic-subject", "epic-subject　"})
    void aSubWithEdgeWhitespaceIsAnInvalidIdToken(String sub) {
        assertThat(errorCodes(ahead(token(ISSUER, sub)))).containsExactly("invalid_id_token");
    }

    @ParameterizedTest(name = "[{index}] iss \"{0}\"")
    @ValueSource(strings = {" " + ISSUER, ISSUER + " ", ISSUER + "\t", ISSUER + " "})
    void anIssWithEdgeWhitespaceIsAnInvalidIdToken(String iss) {
        assertThat(errorCodes(ahead(token(iss, "epic-subject")))).containsExactly("invalid_id_token");
    }

    /** The checks behind it are not run on a refused token, so one that would throw cannot. */
    @Test
    void aRefusedTokenNeverReachesTheChecksBehind() {
        OAuth2TokenValidator<Jwt> throwing = token -> {
            throw new IllegalArgumentException("Unable to convert claim 'iss' to URL");
        };

        OAuth2TokenValidatorResult result =
                EpicIdentityClaims.ahead(throwing).validate(token(ISSUER + " ", "epic-subject"));

        assertThat(errorCodes(result)).containsExactly("invalid_id_token");
    }

    @Test
    void anAcceptableTokenIsDecidedByTheChecksBehind() {
        OAuth2Error behind = new OAuth2Error("invalid_id_token", "decided behind", null);

        OAuth2TokenValidatorResult result = EpicIdentityClaims.ahead(
                token -> OAuth2TokenValidatorResult.failure(behind))
                .validate(token(ISSUER, "epic-subject"));

        assertThat(result.getErrors()).containsExactly(behind);
    }

    /** Whitespace inside a claim is not at its edge, and an absent claim has none. */
    @Test
    void innerWhitespaceAndAbsentClaimsPassToTheChecksBehind() {
        AtomicInteger reached = new AtomicInteger();
        OAuth2TokenValidator<Jwt> counting = token -> {
            reached.incrementAndGet();
            return OAuth2TokenValidatorResult.success();
        };
        OAuth2TokenValidator<Jwt> validator = EpicIdentityClaims.ahead(counting);

        assertThat(validator.validate(token(ISSUER + "/a b", "epic subject")).hasErrors()).isFalse();
        assertThat(validator.validate(token(null, "epic-subject")).hasErrors()).isFalse();
        assertThat(reached).hasValue(2);
    }

    private static OAuth2TokenValidatorResult ahead(Jwt idToken) {
        return EpicIdentityClaims.ahead(token -> OAuth2TokenValidatorResult.success())
                .validate(idToken);
    }

    private static java.util.List<String> errorCodes(OAuth2TokenValidatorResult result) {
        return result.getErrors().stream().map(OAuth2Error::getErrorCode).toList();
    }

    private static Jwt token(String iss, String sub) {
        Jwt.Builder token = Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .subject(sub)
                .issuedAt(Instant.parse("2026-10-13T00:00:00Z"))
                .expiresAt(Instant.parse("2026-10-13T00:05:00Z"));
        if (iss != null) {
            token.claim("iss", iss);
        }
        return token.build();
    }
}
