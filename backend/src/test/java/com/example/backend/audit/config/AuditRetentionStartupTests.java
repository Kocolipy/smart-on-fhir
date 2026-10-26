package com.example.backend.audit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.domain.AuditRetentionPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * That a too-short retention window stops the application, rather than being
 * noticed in a log nobody reads.
 *
 * <p>Driven through a real context refresh instead of by constructing the policy,
 * because the claim is about startup: the rule is only load-bearing if the bean
 * that carries it is created while the context comes up, so a refusal fails the
 * refresh. {@code AuditRetentionPolicyTests} covers the rule itself.
 *
 * <p>The conversion service is the one Boot installs at startup, so
 * {@code 30d} is parsed here exactly as a deployment's environment variable would
 * be. Without it the context would fail for the wrong reason — an unconvertible
 * property rather than a refused window — and the test would pass while proving
 * nothing.
 */
class AuditRetentionStartupTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(PropertySourcesPlaceholderConfigurer.class)
            .withUserConfiguration(AuditRetentionPolicyConfig.class);

    @Test
    void aConfiguredWindowBelowNinetyDaysFailsStartup() {
        contexts.withPropertyValues("app.audit.retention.period=30d")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause()
                        .hasMessageContaining("below"));
    }

    @Test
    void anUnconfiguredWindowStartsWithTheOneYearDefault() {
        contexts.run(context -> assertThat(context)
                .hasNotFailed()
                .getBean(AuditRetentionPolicy.class)
                .extracting(AuditRetentionPolicy::period)
                .isEqualTo(Duration.ofDays(365)));
    }

    @Test
    void aConfiguredWindowAtOrAboveTheFloorStarts() {
        contexts.withPropertyValues("app.audit.retention.period=90d")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(AuditRetentionPolicy.class)
                        .extracting(AuditRetentionPolicy::period)
                        .isEqualTo(Duration.ofDays(90)));
    }

    @Test
    void aConfiguredScheduleReachesThePolicy() {
        contexts.withPropertyValues("app.audit.retention.schedule=0 0 4 * * *")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(AuditRetentionPolicy.class)
                        .extracting(AuditRetentionPolicy::schedule)
                        .isEqualTo("0 0 4 * * *"));
    }
}
