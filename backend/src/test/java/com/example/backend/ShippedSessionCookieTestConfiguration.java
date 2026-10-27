package com.example.backend;

import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.boot.web.server.Cookie;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Gives a test context's session cookie the name and attributes {@code session.yaml} configures,
 * as the deployed service's embedded server gives them.
 *
 * <p>Needed because a {@code @SpringBootTest} context in the default mock web environment holds a
 * mock servlet context, which Boot's session auto-configuration takes for a war deployment: it
 * then builds the cookie serializer from that servlet context's session-cookie settings — which
 * the mock leaves empty, so Spring Session falls back to its default name, {@code SESSION} — and
 * never reads {@code server.servlet.session.cookie.*}. The deployed service runs its embedded
 * server, where Boot does read those settings. This customizer applies them in both cases, from
 * the one document both configurations import, so no test context issues a cookie the shipped
 * configuration does not; {@code LoginSessionHardeningIntegrationTests} holds the result to the
 * shipped name.
 */
public class ShippedSessionCookieTestConfiguration {

    /**
     * The deployed cookie settings, applied to the serializer as Boot's embedded mapping does —
     * the four {@code session.yaml} sets, and only those. A cookie attribute added there needs its
     * line here too, or test contexts will not issue it.
     */
    @Bean
    DefaultCookieSerializerCustomizer shippedSessionCookie(Environment environment) {
        Cookie cookie = Binder.get(environment)
                .bind("server.servlet.session.cookie", Cookie.class)
                .orElseGet(Cookie::new);
        return serializer -> {
            PropertyMapper map = PropertyMapper.get();
            map.from(cookie::getName).to(serializer::setCookieName);
            map.from(cookie::getHttpOnly).to(serializer::setUseHttpOnlyCookie);
            map.from(cookie::getSecure).to(serializer::setUseSecureCookie);
            map.from(cookie::getSameSite).as(Cookie.SameSite::attributeValue)
                    .to(serializer::setSameSite);
        };
    }
}
