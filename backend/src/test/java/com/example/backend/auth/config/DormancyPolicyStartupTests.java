package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scim.domain.DormancyPolicy;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * That the two dormancy windows reach the rule from deployment configuration, and that an
 * unusable combination stops startup with a message naming the problem.
 *
 * <p>Driven through a real context refresh, with the conversion service Boot installs, so
 * {@code 60d} is parsed exactly as a deployment's environment variable would be.
 * {@code DormancyPolicyTests} covers the rule itself.
 */
class DormancyPolicyStartupTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(PropertySourcesPlaceholderConfigurer.class)
            .withUserConfiguration(DormancyPolicyConfig.class);

    @Test
    void unconfiguredWindowsStartWithTheDocumentedDefaults() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            DormancyPolicy policy = context.getBean(DormancyPolicy.class);
            assertThat(policy.lockoutWindow()).isEqualTo(Duration.ofDays(90));
            assertThat(policy.membershipRevocationWindow()).isEqualTo(Duration.ofDays(180));
        });
    }

    @Test
    void configuredWindowsReachThePolicy() {
        contexts.withPropertyValues(
                        "app.dormancy.lockout.window=60d",
                        "app.dormancy.membership.revocation.window=120d")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DormancyPolicy policy = context.getBean(DormancyPolicy.class);
                    assertThat(policy.lockoutWindow()).isEqualTo(Duration.ofDays(60));
                    assertThat(policy.membershipRevocationWindow()).isEqualTo(Duration.ofDays(120));
                });
    }

    /** The environment variable names the README and {@code .env.example} document. */
    @Test
    void theDocumentedEnvironmentVariablesReachThePolicy() {
        contexts.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-env", Map.<String, Object>of(
                                "APP_DORMANCY_LOCKOUT_WINDOW", "30d",
                                "APP_DORMANCY_MEMBERSHIP_REVOCATION_WINDOW", "45d"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DormancyPolicy policy = context.getBean(DormancyPolicy.class);
                    assertThat(policy.lockoutWindow()).isEqualTo(Duration.ofDays(30));
                    assertThat(policy.membershipRevocationWindow()).isEqualTo(Duration.ofDays(45));
                });
    }

    @ParameterizedTest
    @CsvSource({
            "app.dormancy.lockout.window=0d,                 lockout window is configured at PT0S",
            "app.dormancy.membership.revocation.window=-5d,  membership revocation window is configured at PT-120H"
    })
    void aNonPositiveWindowFailsStartupNamingTheWindow(String setting, String message) {
        contexts.withPropertyValues(setting)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause()
                        .hasMessageContaining(message)
                        .hasMessageContaining("must be positive"));
    }

    @Test
    void aMembershipRevocationWindowNotLongerThanTheLockoutWindowFailsStartup() {
        contexts.withPropertyValues(
                        "app.dormancy.lockout.window=90d",
                        "app.dormancy.membership.revocation.window=90d")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause()
                        .hasMessageContaining("must be longer than the lockout window"));
    }
}
