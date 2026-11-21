package com.example.backend.auth.epic;

import java.security.KeyPair;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Epic Login on, with generated keys and never committed ones, for the full-context suites that
 * need Epic Login's routes, settings or meters present but never sign anyone in through Epic.
 *
 * <p>A superclass, not a {@code @DynamicPropertySource} method on each suite, because the context
 * cache keys on the declaring method: one declared here puts every suite that extends this class
 * in one context, where a copy on each suite would boot a context per suite.
 *
 * <p>The keys are minted once per run and held here, because the context resolves a dynamic
 * property each time it reads one: every read of a key setting names the same key, and a suite
 * can hold what the context serves or signs with to the private half it was given.
 */
public abstract class EpicLoginOnContext {

    /** The private key {@code app.epic.client-key} carries, under the kid {@code active-2026-04}. */
    protected static final KeyPair ACTIVE_KEY = EpicTestKeys.p384KeyPair();

    /** The private key {@code app.epic.client-next-key} carries, under the kid {@code next-2026-10}. */
    protected static final KeyPair NEXT_KEY = EpicTestKeys.p384KeyPair();

    @DynamicPropertySource
    protected static void epicLoginOn(DynamicPropertyRegistry registry) {
        EpicTestKeys.epicLoginOn(registry, () -> EpicTestKeys.pem(ACTIVE_KEY),
                () -> EpicTestKeys.pem(NEXT_KEY));
    }
}
