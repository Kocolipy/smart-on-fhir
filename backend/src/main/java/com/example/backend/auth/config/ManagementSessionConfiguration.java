package com.example.backend.auth.config;

import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.session.web.context.AbstractHttpSessionApplicationInitializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * Lets the session reach the management port when actuator runs on its own port
 * ({@code MANAGEMENT_SERVER_PORT}).
 *
 * <p>Spring Boot builds a separate child context for that port, and the only filter it
 * copies across from the application is Spring Security's {@code springSecurityFilterChain}.
 * Spring Session's {@link SessionRepositoryFilter} stays behind. Without it the security
 * chain on the management port looks the session cookie up in that Tomcat's own empty
 * in-memory store, so the {@code /actuator/**} ADMIN rule rejects everyone, Admin
 * included, and the internal-port deployment in infra/docs/operational-telemetry.md has no working scrape.
 *
 * <p>This registers the application's own filter bean in the child, resolved through the
 * parent context and placed ahead of the security chain. That is the same order Boot uses on the
 * application port. The session, its cookie and the ADMIN rule are the same on both
 * ports. {@code ManagementPortIntegrationTests} pins both halves.
 *
 * <p>Wiring: listed in
 * {@code META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports}
 * and deliberately NOT annotated {@code @Configuration} or
 * {@code @ManagementContextConfiguration}, both of which would make the application's
 * component scan also load it into the main context. An unannotated entry is imported
 * for any context type, so {@link ConditionalOnManagementPort} limits it to the child
 * that exists only when the management port differs.
 */
@ConditionalOnClass(SessionRepositoryFilter.class)
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
class ManagementSessionConfiguration {

    @Bean
    DelegatingFilterProxyRegistrationBean managementSessionRepositoryFilterRegistration() {
        // Spring Session registers its filter under this name; DelegatingFilterProxy
        // resolves it through the child context, which falls back to the parent's bean.
        DelegatingFilterProxyRegistrationBean registration = new DelegatingFilterProxyRegistrationBean(
                AbstractHttpSessionApplicationInitializer.DEFAULT_FILTER_NAME);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
        return registration;
    }
}
