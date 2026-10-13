package com.example.backend.auth.epic.config;

import com.example.backend.scim.domain.EdgeWhitespace;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * The {@code id_token}'s identity claims, {@code iss} and {@code sub}, must not begin or end with
 * whitespace (ADR 0015) — the rule the directory's keys and the password Login's username are held
 * to, by the one definition in {@link EdgeWhitespace}, which also says why the rule exists.
 *
 * <p>A failure is a claim failure like every other the decoder reports, {@code invalid_id_token},
 * so the Epic failure handler refuses it as {@code INVALID_CLAIMS} (flow step 4).
 *
 * <p>Checked AHEAD of every other claim check, and the others are not run on a token it refuses.
 * The order is load-bearing: Spring Security's OpenID Connect validator reads {@code iss} as a URL
 * and throws on one carrying a space instead of reporting it, and Spring's delegating validator
 * runs every validator it holds whatever the earlier ones found, so a padded issuer would fail the
 * callback rather than be refused.
 */
final class EpicIdentityClaims {

    /** The error code every claim failure reports, as Spring Security's own claim checks do. */
    private static final String INVALID_ID_TOKEN = "invalid_id_token";

    private EpicIdentityClaims() {
    }

    /**
     * A validator refusing a token whose {@code iss} or {@code sub} has edge whitespace, and
     * otherwise answering as {@code behind} does.
     */
    static OAuth2TokenValidator<Jwt> ahead(OAuth2TokenValidator<Jwt> behind) {
        return idToken -> {
            if (EdgeWhitespace.borders(idToken.getClaimAsString(JwtClaimNames.ISS))
                    || EdgeWhitespace.borders(idToken.getClaimAsString(JwtClaimNames.SUB))) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(INVALID_ID_TOKEN,
                        "The iss or sub claim begins or ends with whitespace", null));
            }
            return behind.validate(idToken);
        };
    }
}
