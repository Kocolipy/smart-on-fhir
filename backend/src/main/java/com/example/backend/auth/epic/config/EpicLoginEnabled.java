package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicLoginProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Whether {@code APP_EPIC_ENABLED} is on. It reads the switch through the same binding as
 * {@link EpicLoginProperties}, so an unset, empty or {@code false} switch is off here exactly
 * as it is there.
 */
class EpicLoginEnabled implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("app.epic", EpicLoginProperties.class)
                .map(EpicLoginProperties::enabled)
                .orElse(false);
    }
}
