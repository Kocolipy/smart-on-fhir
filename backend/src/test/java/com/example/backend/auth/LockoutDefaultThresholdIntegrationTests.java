package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.application.PasswordRefusalReason;
import com.example.backend.auth.application.LoginAttemptService;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The default lockout threshold is 3 (#98): the 2nd consecutive failure leaves a User
 * unlocked and the 3rd locks it, counted through the real {@link LoginAttemptService}
 * against real Postgres.
 *
 * <p>The test resources' {@code application.yaml} shadows the deployed one, so the
 * threshold this context runs with is the test profile's. The first assertion ties the
 * two together: it resolves {@code app.auth.lockout.max-attempts} from the DEPLOYED file
 * alone, with no environment behind it, which is exactly the
 * "{@code APP_LOCKOUT_MAX_ATTEMPTS} unset" case, and requires that and the running value
 * both to be 3.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class LockoutDefaultThresholdIntegrationTests {

    private static final int DEFAULT_THRESHOLD = 3;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private LoginAttemptService attempts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${app.auth.lockout.max-attempts}")
    private int maxAttempts;

    private final List<UUID> seeded = new ArrayList<>();

    @Test
    void theThirdConsecutiveFailureLocksAndTheSecondDoesNot() throws IOException {
        assertThat(deployedDefaultMaxAttempts()).isEqualTo(DEFAULT_THRESHOLD);
        assertThat(maxAttempts).isEqualTo(DEFAULT_THRESHOLD);

        String userName = create("lockout-default-threshold").profile().userName();

        attempts.recordFailure(userName, PasswordRefusalReason.BAD_CREDENTIALS);
        attempts.recordFailure(userName, PasswordRefusalReason.BAD_CREDENTIALS);
        assertThat(require(userName).login().isLocked())
                .as("locked after the 2nd consecutive failure")
                .isFalse();

        attempts.recordFailure(userName, PasswordRefusalReason.BAD_CREDENTIALS);
        assertThat(require(userName).login().isLocked())
                .as("locked after the 3rd consecutive failure")
                .isTrue();
    }

    /** {@code app.auth.lockout.max-attempts} from the deployed file, with no environment. */
    private static Integer deployedDefaultMaxAttempts() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("deployed", new FileSystemResource("src/main/resources/application.yaml"))
                .forEach(sources::addLast);
        assertThat(sources.size()).as("documents loaded from the deployed file").isPositive();
        return new PropertySourcesPropertyResolver(sources)
                .getProperty("app.auth.lockout.max-attempts", Integer.class);
    }

    private ScimUser create(String userName) {
        ScimUser created = new TransactionTemplate(transactionManager).execute(status -> users.create(
                ScimUser.created(
                        UUID.randomUUID(),
                        ScimIdentities.profile(userName, true),
                        "hash",
                        ScimIdentities.NOW)));
        seeded.add(created.id());
        return created;
    }

    private ScimUser require(String userName) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName)).orElseThrow();
    }

    /** Removes only what this class seeded; the cascade takes the User row with it. */
    @AfterEach
    void removeSeededIdentities() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
        seeded.clear();
    }
}
