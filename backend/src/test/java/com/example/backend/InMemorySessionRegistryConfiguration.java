package com.example.backend;

import com.example.backend.auth.InMemoryAccountSessions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The session registry, in memory, for a full-context test that asserts on what the registry
 * was asked to hold or revoke. Every context has Redis ({@link ContainerTestConfiguration}),
 * so import this only where the test reads {@code InMemoryAccountSessions}: each extra
 * import combination is a context, and its containers, of its own.
 *
 * <p>A top-level class rather than a nested {@code @TestConfiguration} in each test,
 * and the reason is the container. {@link ContainerTestConfiguration} declares the
 * Postgres container as a {@code @Bean}, so there is one container per test
 * {@code ApplicationContext} — and Spring's context cache keys on the configuration
 * CLASSES, which means two test classes with identical annotations share one context
 * while two that each declare their own nested configuration cannot, however
 * identical those nested classes look. A nested copy per test class therefore costs a
 * Postgres container per test class, which is how a suite ends up with more
 * containers than the host can keep alive.
 *
 * <p>Imported rather than component-scanned, so a test that genuinely wants Redis is
 * unaffected by its existence.
 */
@TestConfiguration
public class InMemorySessionRegistryConfiguration {

    @Bean
    @Primary
    InMemoryAccountSessions inMemoryAccountSessions() {
        return new InMemoryAccountSessions();
    }
}
