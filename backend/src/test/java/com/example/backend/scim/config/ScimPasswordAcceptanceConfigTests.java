package com.example.backend.scim.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.auth.config.PasswordEncoderFixtures;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The container's {@link PasswordAcceptance} is built over the history and encoder it is given. */
class ScimPasswordAcceptanceConfigTests {

    private final PasswordEncoder encoder = PasswordEncoderFixtures.cheapArgon2id();
    private final InMemoryScimPasswordHistoryRepository history =
            new InMemoryScimPasswordHistoryRepository();
    private final PasswordAcceptance acceptance =
            new ScimPasswordAcceptanceConfig().passwordAcceptance(history, encoder);

    @Test
    void theBeanEncodesThroughTheEncoderAndRemembersInTheHistoryItWasGiven() {
        ScimUser ada = ScimIdentities.userWithLoginState(
                "ada", new ScimLoginState(encoder.encode("the-current-password"), 0, null, null, null));

        PasswordAcceptance.Accepted accepted = (PasswordAcceptance.Accepted)
                acceptance.acceptFor(ada, "a-brand-new-passphrase", "ada");
        acceptance.remember(ada.id(), accepted, ScimIdentities.NOW);

        assertThat(encoder.matches("a-brand-new-passphrase", accepted.passwordHash())).isTrue();
        assertThat(history.findRecentHashes(ada.id())).containsExactly(accepted.passwordHash());
        assertThat(acceptance.acceptFor(ada, "the-current-password", "ada"))
                .as("the current credential is compared through the same encoder")
                .isEqualTo(new PasswordAcceptance.Refused(PasswordPolicy.Rule.REUSED));
    }
}
