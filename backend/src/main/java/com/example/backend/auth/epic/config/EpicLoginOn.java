package com.example.backend.auth.epic.config;

import static com.example.backend.web.ReadRequests.read;

import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.auth.epic.EpicRoutes;
import com.example.backend.auth.epic.EpicSigningKey;
import com.example.backend.auth.epic.EpicSigningKeys;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Epic Login on: the whole flow on the application chain (ADR 0013) — the OAuth 2.0 half, which
 * serves the authorize hop and the callback ({@link EpicLoginFlow}), and the rules that make
 * Epic Login's four routes public. There is no release gate in this state.
 *
 * <p>The rules are added ahead of the chain's own, which name no Epic route, so they decide every
 * Epic route whatever the chain's later rules — its final deny-all included — would have said.
 * Each permits a read of its route as the chain's own rules do ({@code ReadRequests}).
 */
public final class EpicLoginOn implements EpicLogin {

    private final EpicSigningKeys signingKeys;

    private final Customizer<HttpSecurity> flow;

    /**
     * @param signingKeys our client-authentication keys, which the startup record names by
     *                    {@code kid}
     * @param flow        the OAuth 2.0 half, {@link EpicLoginFlow#applyTo}
     */
    public EpicLoginOn(EpicSigningKeys signingKeys, Customizer<HttpSecurity> flow) {
        this.signingKeys = signingKeys;
        this.flow = flow;
    }

    @Override
    public void applyTo(HttpSecurity http) {
        // The authorize hop and the callback are served by the flow's filters, ahead of every rule.
        flow.customize(http);
        http.authorizeHttpRequests(authorize -> authorize
                // Our public JWKS: Epic fetches it, with no session, to verify our client
                // assertions (D14). Public keys only.
                .requestMatchers(read(EpicRoutes.JWKS)).permitAll()
                // The browser routes: the launch URL Epic opens, the internal hop to Epic's
                // authorization endpoint, and the callback Epic redirects back to. The browser
                // arrives from Epic with no session of ours; each answers with a redirect.
                .requestMatchers(read(EpicRoutes.LAUNCH),
                        read(EpicRoutes.AUTHORIZE),
                        read(EpicRoutes.CALLBACK)).permitAll());
    }

    /** On, with the active key's {@code kid}, and the next key's when one is configured (D7, D14). */
    @Override
    public LoggingEventBuilder addStartupFields(LoggingEventBuilder record) {
        LoggingEventBuilder withActive = record
                .addKeyValue(ENABLED_FIELD, true)
                .addKeyValue(CLIENT_KEY_ID_FIELD, signingKeys.active().keyId());
        return signingKeys.next()
                .map(EpicSigningKey::keyId)
                .map(nextKeyId -> withActive.addKeyValue(CLIENT_NEXT_KEY_ID_FIELD, nextKeyId))
                .orElse(withActive);
    }
}
