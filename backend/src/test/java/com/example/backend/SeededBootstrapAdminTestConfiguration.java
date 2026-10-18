package com.example.backend;

import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tests start from a deployment whose operator has already completed the Bootstrap Admin's
 * first-login password change.
 *
 * <p>Seeding flags the Bootstrap Admin, because its configured password is a default credential,
 * and a flagged session is confined to the change flow. Every integration test that administers
 * the directory logs in as the seeded {@code test-admin} with its configured password, so without
 * this each one would first have to change that password — which would then differ from the one
 * the next class sharing the cached context logs in with.
 *
 * <p>So the flag is cleared through the real
 * {@link ScimLoginStateRepository#completePasswordChange} port, keeping the same hash: the state a deployment is in after its operator rotated the
 * credential, minus the rotation. It runs on {@link ApplicationReadyEvent}, which fires after every
 * {@code ApplicationRunner} — seeding included — has finished.
 *
 * <p>What this hides is asserted elsewhere: {@code ScimSeedServiceTests} pins that seeding sets
 * the flag, and the confinement of a flagged session has its own integration tests.
 */
public class SeededBootstrapAdminTestConfiguration {

    @Bean
    ApplicationListener<ApplicationReadyEvent> bootstrapAdminFirstLoginChangeCompleted(
            ScimUserRepository users, ScimLoginStateRepository loginStates,
            PlatformTransactionManager transactions, Clock clock) {
        return ready -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ScimUser bootstrapAdmin = users
                    .findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN)
                    .orElseThrow();
            if (bootstrapAdmin.login().isPasswordChangeRequired()) {
                loginStates.completePasswordChange(
                        bootstrapAdmin.id(), bootstrapAdmin.login().passwordHash(), clock.instant());
            }
        });
    }
}
