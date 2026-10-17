package com.example.backend.auth.epic;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Epic Login on, with generated keys and never committed ones, for the full-context suites that
 * need Epic Login's routes, settings or meters present but never sign anyone in through Epic.
 *
 * <p>A superclass, not a {@code @DynamicPropertySource} method on each suite, because the context
 * cache keys on the declaring method: one declared here puts every suite that extends this class
 * in one context, where a copy on each suite would boot a context per suite.
 */
public abstract class EpicLoginOnContext {

    @DynamicPropertySource
    protected static void epicLoginOn(DynamicPropertyRegistry registry) {
        EpicTestKeys.epicLoginOn(registry, EpicTestKeys::p384Pem, EpicTestKeys::p384Pem);
    }
}
