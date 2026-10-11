package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.ScimUserLoginState.Credential;
import org.junit.jupiter.api.Test;

/** The credential a SCIM replacement reports: its hash, and whether the connector set it. */
class ScimUserLoginStateCredentialTests {

    @Test
    void anUnchangedCredentialKeepsItsHashAndWasNotSetByTheConnector() {
        assertThat(Credential.unchanged("stored-hash"))
                .extracting(Credential::passwordHash, Credential::connectorSet)
                .containsExactly("stored-hash", false);
    }

    @Test
    void aClearedCredentialHasNoHashAndWasNotSetByTheConnector() {
        assertThat(Credential.cleared())
                .extracting(Credential::passwordHash, Credential::connectorSet)
                .containsExactly(null, false);
    }

    @Test
    void aSetCredentialCarriesTheConnectorsHash() {
        assertThat(Credential.set("connector-hash"))
                .extracting(Credential::passwordHash, Credential::connectorSet)
                .containsExactly("connector-hash", true);
    }

    /** A password the connector set always has a hash; only an absent one is null. */
    @Test
    void aSetCredentialWithoutAHashIsRefused() {
        assertThatThrownBy(() -> Credential.set(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("a set password has a hash");
    }
}
