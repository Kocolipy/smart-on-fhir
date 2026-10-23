package com.example.backend.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller as serving {@code /api/admin}, whichever module it is in, so the one advice
 * at that edge can be scoped to it by this mark rather than by naming each controller's class —
 * which would have one module reach into another's web adapters.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AdminApiHandler {
}
