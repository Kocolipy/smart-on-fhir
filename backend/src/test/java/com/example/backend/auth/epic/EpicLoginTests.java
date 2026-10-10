package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Which state of Epic Login a context is in, as the beans registered under
 * {@link EpicLogin.WhenOn} and {@link EpicLogin.WhenOff} see it: exactly one of the two, decided
 * by {@code APP_EPIC_ENABLED} read as its bound property reads it.
 */
class EpicLoginTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(BothStates.class);

    @Test
    void withNoSwitchAtAllOnlyTheOffStatesBeanExists() {
        contexts.run(context -> assertThat(context.getBeansOfType(String.class).values())
                .containsExactly("off"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "yes", "on"})
    void withTheSwitchOnOnlyTheOnStatesBeanExists(String value) {
        contexts.withPropertyValues("app.epic.enabled=" + value)
                .run(context -> assertThat(context.getBeansOfType(String.class).values())
                        .containsExactly("on"));
    }

    /** A deployment template renders an unset switch as empty; empty is off, as unset is. */
    @ParameterizedTest
    @ValueSource(strings = {"false", "no", ""})
    void withTheSwitchOffOrEmptyOnlyTheOffStatesBeanExists(String value) {
        contexts.withPropertyValues("app.epic.enabled=" + value)
                .run(context -> assertThat(context.getBeansOfType(String.class).values())
                        .containsExactly("off"));
    }

    @Configuration
    static class BothStates {

        @Bean
        @Conditional(EpicLogin.WhenOn.class)
        String on() {
            return "on";
        }

        @Bean
        @Conditional(EpicLogin.WhenOff.class)
        String off() {
            return "off";
        }
    }
}
