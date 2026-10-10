package com.example.backend.auth.epic;

import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Epic Login, as the application chain sees it: one call, {@link #applyTo}, and nothing else
 * (ADR 0013). The startup record asks it one more thing, {@link #addStartupFields}.
 *
 * <p>The one answer to "is Epic Login on?". No caller asks that question of it directly; each
 * hands it what it adds to, and the adapter in place answers. Exactly one of its two adapters is
 * wired, decided once from {@code APP_EPIC_ENABLED} — and, when that is on, from configuration
 * that passed {@link EpicLoginProperties#validate}, since startup fails otherwise:
 *
 * <ul>
 *   <li><b>Off</b> ({@code EpicLoginOff}): the release gate alone, which answers {@code 404} to
 *       every request under {@code /api/auth/epic} ahead of the session, CSRF and authorization
 *       filters, mirroring the SCIM release gate. No Epic route, setting or outbound client
 *       exists.
 *   <li><b>On</b> ({@code EpicLoginOn}): the whole flow — the callback filter, {@code oauth2Login}
 *       with its handlers, and the rules that make the four Epic routes public. Everything it
 *       needs, the settings included, exists only in this state, so the code downstream of it is
 *       handed the settings rather than asking whether there are any.
 * </ul>
 *
 * <p>Both adapters live in {@code com.example.backend.auth.epic.config}. This interface sits at
 * the package root, which belongs to no onion layer, because the switch below must be readable by
 * the configuration adapter and by the web adapter alike, and neither may depend on the other.
 */
public interface EpicLogin {

    /** The startup record's field for the switch, {@code true} exactly while Epic Login is on. */
    String ENABLED_FIELD = "app.epic.enabled";

    /** The startup record's field for the active signing key's {@code kid}, while on. */
    String CLIENT_KEY_ID_FIELD = "app.epic.client_key_id";

    /** The startup record's field for the next signing key's {@code kid}, while on with one. */
    String CLIENT_NEXT_KEY_ID_FIELD = "app.epic.client_next_key_id";

    /** Adds Epic Login, in whichever state this deployment has it, to the application chain. */
    void applyTo(HttpSecurity http);

    /**
     * Adds what the startup record says of Epic Login to {@code record}: the switch, and while it is
     * on the signing keys' {@code kid}s, so each redeploy that promotes a key leaves a record of
     * it. A {@code kid} is a public label the JWKS publishes; no key material, URL or issuer is
     * added (D22).
     */
    LoggingEventBuilder addStartupFields(LoggingEventBuilder record);

    /**
     * Registers a bean only while Epic Login is on: what exists only because Epic Login is
     * served — its settings, its outbound client, its routes and handlers, and the on adapter.
     */
    final class WhenOn implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return switchedOn(context.getEnvironment());
        }
    }

    /** Registers a bean only while Epic Login is off: the off adapter, and nothing else. */
    final class WhenOff implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !switchedOn(context.getEnvironment());
        }
    }

    /**
     * Whether {@code APP_EPIC_ENABLED} is on, read through the same binding as
     * {@link EpicLoginProperties}, so an unset, empty or {@code false} switch is off here exactly
     * as it is there. A condition must read it from the environment itself: it decides which beans
     * exist, so it runs before any of them, the bound properties included.
     */
    private static boolean switchedOn(Environment environment) {
        return Binder.get(environment)
                .bind("app.epic", EpicLoginProperties.class)
                .map(EpicLoginProperties::enabled)
                .orElse(false);
    }
}
