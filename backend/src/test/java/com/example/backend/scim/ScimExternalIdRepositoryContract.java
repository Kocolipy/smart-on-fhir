package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scim.domain.ScimExternalIdRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ScimExternalIdRepository} does, whatever stores the aliases: the SQL Server
 * adapter, and the in-memory store the SCIM write and connector administration unit tests run
 * against. Each runs it.
 *
 * <p>An alias is keyed by its connector and its resource, each of which the SQL Server table holds
 * to by a foreign key, so every case first arranges both through {@link #givenConnector()} and
 * {@link #givenResource()}. The in-memory store holds no connector or resource and checks neither:
 * every write path arranges both before it writes an alias.
 */
public abstract class ScimExternalIdRepositoryContract {

    /** The alias port under test. */
    protected abstract ScimExternalIdRepository aliases();

    /** A connector the store holds, whose namespace an alias can be written in. */
    protected abstract UUID givenConnector();

    /** A User or Group the store holds, which an alias can name. */
    protected abstract UUID givenResource();

    @Test
    void a_put_alias_is_found_by_its_connector_and_resource() {
        UUID connector = givenConnector();
        UUID resource = givenResource();

        aliases().put(connector, resource, "ext-1");

        assertThat(aliases().find(connector, resource)).contains("ext-1");
    }

    @Test
    void a_resource_with_no_alias_finds_none() {
        assertThat(aliases().find(givenConnector(), givenResource())).isEmpty();
    }

    /** One connector has one alias per resource: putting another replaces it. */
    @Test
    void putting_an_alias_again_replaces_it() {
        UUID connector = givenConnector();
        UUID resource = givenResource();
        aliases().put(connector, resource, "ext-1");

        aliases().put(connector, resource, "ext-2");

        assertThat(aliases().find(connector, resource)).contains("ext-2");
    }

    /** The value is case-exact, as RFC 7643 has it: no store folds it. */
    @Test
    void an_alias_is_read_back_case_exact() {
        UUID connector = givenConnector();
        UUID resource = givenResource();

        aliases().put(connector, resource, "Ext-MiXeD");

        assertThat(aliases().find(connector, resource)).contains("Ext-MiXeD");
    }

    /** Another connector's alias for the same resource is in its own namespace. */
    @Test
    void each_connectors_alias_for_one_resource_is_its_own() {
        UUID okta = givenConnector();
        UUID entra = givenConnector();
        UUID resource = givenResource();

        aliases().put(okta, resource, "okta-1");
        aliases().put(entra, resource, "entra-1");

        assertThat(aliases().find(okta, resource)).contains("okta-1");
    }

    /** No value is unique within a connector: two resources may carry the same alias. */
    @Test
    void two_resources_may_carry_the_same_alias_in_one_connector() {
        UUID connector = givenConnector();
        UUID first = givenResource();
        UUID second = givenResource();

        aliases().put(connector, first, "shared");
        aliases().put(connector, second, "shared");

        assertThat(aliases().find(connector, second)).contains("shared");
    }

    @Test
    void a_removed_alias_is_gone() {
        UUID connector = givenConnector();
        UUID resource = givenResource();
        aliases().put(connector, resource, "ext-1");

        aliases().remove(connector, resource);

        assertThat(aliases().find(connector, resource)).isEmpty();
    }

    @Test
    void removing_an_alias_leaves_another_connectors_for_the_same_resource() {
        UUID okta = givenConnector();
        UUID entra = givenConnector();
        UUID resource = givenResource();
        aliases().put(okta, resource, "okta-1");
        aliases().put(entra, resource, "entra-1");

        aliases().remove(okta, resource);

        assertThat(aliases().find(entra, resource)).contains("entra-1");
    }

    /** Removing an alias nobody set is no error and changes nothing. */
    @Test
    void removing_an_alias_nobody_set_leaves_the_resources_others() {
        UUID okta = givenConnector();
        UUID entra = givenConnector();
        UUID resource = givenResource();
        aliases().put(entra, resource, "entra-1");

        aliases().remove(okta, resource);

        assertThat(aliases().find(entra, resource)).contains("entra-1");
    }

    @Test
    void deleting_a_connectors_aliases_counts_them() {
        UUID connector = givenConnector();
        aliases().put(connector, givenResource(), "ext-1");
        aliases().put(connector, givenResource(), "ext-2");

        assertThat(aliases().deleteAllForConnector(connector)).isEqualTo(2);
    }

    @Test
    void deleting_a_connectors_aliases_removes_each_of_them() {
        UUID connector = givenConnector();
        UUID resource = givenResource();
        aliases().put(connector, resource, "ext-1");

        aliases().deleteAllForConnector(connector);

        assertThat(aliases().find(connector, resource)).isEmpty();
    }

    @Test
    void deleting_a_connectors_aliases_leaves_another_connectors() {
        UUID okta = givenConnector();
        UUID entra = givenConnector();
        UUID resource = givenResource();
        aliases().put(okta, resource, "okta-1");
        aliases().put(entra, resource, "entra-1");

        aliases().deleteAllForConnector(okta);

        assertThat(aliases().find(entra, resource)).isEqualTo(Optional.of("entra-1"));
    }

    @Test
    void deleting_the_aliases_of_a_connector_holding_none_counts_none() {
        assertThat(aliases().deleteAllForConnector(givenConnector())).isZero();
    }
}
