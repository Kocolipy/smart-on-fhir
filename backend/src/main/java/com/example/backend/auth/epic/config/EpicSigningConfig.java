package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.ClientAssertionSigner;
import com.example.backend.auth.epic.EnvironmentKeyClientAssertionSigner;
import com.example.backend.auth.epic.EpicJwks;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.auth.epic.EpicLoginSettings;
import com.example.backend.auth.epic.EpicSigningKeys;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Our client-authentication keys for Epic (D7, D14, D16): the signer every client assertion
 * goes through, and the public JWKS Epic verifies them against.
 *
 * <p>Both exist only while Epic Login is on, as the keys do. While it is off the JWKS route is not
 * mapped at all, and the release gate answers {@code 404} for it.
 */
@Configuration
public class EpicSigningConfig {

    /**
     * The environment-variable key's signer, for now (D16). A KMS-backed signer replaces this
     * bean, and nothing that uses a {@link ClientAssertionSigner} changes.
     */
    @Bean
    @Conditional(EpicLogin.WhenOn.class)
    public ClientAssertionSigner clientAssertionSigner(
            EpicLoginSettings settings, Clock clock) {
        return new EnvironmentKeyClientAssertionSigner(
                settings.clientId(), settings.signingKeys(), clock);
    }

    @Bean
    @Conditional(EpicLogin.WhenOn.class)
    public EpicJwks epicJwks(EpicSigningKeys signingKeys) {
        return EpicJwks.of(signingKeys);
    }
}
