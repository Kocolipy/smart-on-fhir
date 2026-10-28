package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.TokenPermissions;
import com.example.backend.scim.InMemoryScimExternalIdRepository;
import com.example.backend.scim.domain.AuthenticatedConnector;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a SCIM write does with the calling connector's {@code externalId} — its alias for the
 * resource — whichever resource it writes: {@code ScimUserServiceTests} runs it over the User use
 * case and {@code ScimGroupServiceTests} over the Group's, each answering the hooks below with its
 * own resource.
 *
 * <p>An alias is the caller's own name for the resource (RFC 7643 §3.1, connector-scoped here): a
 * create records it, a PUT carrying another value re-keys it and a PUT omitting it removes it, a
 * PATCH sets and removes it, and a write that moves it changes the resource's version once. No
 * write touches another connector's alias for the same resource. What each resource adds — a
 * User's protected Bootstrap Admin, a Group's alias-only version bump — stays in its own tests.
 * Which connector sees which alias over HTTP is {@code ScimExternalIdReadWriteIntegrationTests}'.
 */
abstract class ScimAliasWriteContract {

    /** A second connector, holding its own alias for the resource under test. */
    protected static final AuthenticatedConnector OTHER_CONNECTOR = new AuthenticatedConnector(
            UUID.randomUUID(), UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));

    /** The alias {@link #OTHER_CONNECTOR} holds for the resource {@link #aliasedResource} answers. */
    protected static final String THEIRS = "theirs";

    /** The alias store the service under test writes through. */
    protected final InMemoryScimExternalIdRepository aliases =
            new InMemoryScimExternalIdRepository();

    /** A stored resource: its id, the calling connector's alias for it, and its version. */
    protected record Aliased(UUID id, String alias, long version) {
    }

    /** What a write answered the caller with: the alias it sees, and the resource's version. */
    protected record Answer(String externalId, long version) {
    }

    /** The connector the writes are made as. */
    protected abstract AuthenticatedConnector connector();

    /**
     * A stored resource {@link #connector()} holds an alias for, which {@link #OTHER_CONNECTOR}
     * holds {@link #THEIRS} for, with the audit trail cleared.
     */
    protected abstract Aliased aliasedResource();

    /** Creates a resource as {@link #connector()}, carrying {@code externalId}. */
    protected abstract Aliased create(String externalId);

    /**
     * A PUT restating every attribute of the resource {@link #aliasedResource} answered but its
     * alias, carrying {@code externalId} — or none, when it is {@code null}.
     */
    protected abstract Answer put(UUID id, String externalId);

    /** A PATCH setting {@code externalId}, made as {@code caller}. */
    protected abstract Answer setAlias(AuthenticatedConnector caller, UUID id, String externalId);

    /** A PATCH removing the caller's {@code externalId}. */
    protected abstract Answer removeAlias(UUID id);

    /** A PATCH changing one attribute of the resource that is not its alias. */
    protected abstract Answer changeAnotherAttribute(UUID id);

    /** The resource's stored version. */
    protected abstract long storedVersion(UUID id);

    /** The alias {@code caller} reads the resource back with. */
    protected abstract String readBy(AuthenticatedConnector caller, UUID id);

    /** The detail of each replace event the use case recorded, in order. */
    protected abstract List<String> recordedReplaceDetails();

    @Test
    void a_create_records_the_callers_alias_and_answers_it() {
        Aliased created = create("created-alias");

        assertThat(created.alias()).isEqualTo("created-alias");
        assertThat(aliases.find(connector().connectorId(), created.id())).contains("created-alias");
    }

    /**
     * {@code externalId} is read-write: a PUT carrying a different value re-keys the CALLING
     * connector's alias and advances the version like any other change, and leaves another
     * connector's alias for the same resource as it was.
     */
    @Test
    void a_put_with_a_different_alias_changes_only_the_callers_alias() {
        Aliased stored = aliasedResource();

        Answer written = put(stored.id(), "re-keyed");

        assertThat(written).isEqualTo(new Answer("re-keyed", stored.version() + 1));
        assertThat(storedVersion(stored.id())).isEqualTo(stored.version() + 1);
        assertThat(aliases.find(connector().connectorId(), stored.id())).contains("re-keyed");
        assertThat(aliases.find(OTHER_CONNECTOR.connectorId(), stored.id())).contains(THEIRS);
        assertThat(recordedReplaceDetails()).containsExactly("EXTERNAL_ID");
    }

    /**
     * RFC 7644 §3.5.1: an omitted read-write attribute is unassigned by a replacement, so a PUT
     * with no {@code externalId} removes the caller's alias — and only the caller's.
     */
    @Test
    void a_put_omitting_the_alias_removes_only_the_callers_alias() {
        Aliased stored = aliasedResource();

        Answer written = put(stored.id(), null);

        assertThat(written).isEqualTo(new Answer(null, stored.version() + 1));
        assertThat(aliases.find(connector().connectorId(), stored.id())).isEmpty();
        assertThat(aliases.find(OTHER_CONNECTOR.connectorId(), stored.id())).contains(THEIRS);
        assertThat(recordedReplaceDetails()).containsExactly("EXTERNAL_ID");
    }

    /** Restating the stored alias is the no-op any restated attribute is: nothing is written. */
    @Test
    void a_put_restating_the_alias_writes_nothing() {
        Aliased stored = aliasedResource();
        int aliasWrites = aliases.writes();

        Answer written = put(stored.id(), stored.alias());

        assertThat(written).isEqualTo(new Answer(stored.alias(), stored.version()));
        assertThat(storedVersion(stored.id())).isEqualTo(stored.version());
        assertThat(aliases.writes()).isEqualTo(aliasWrites);
    }

    /** PATCH sets, re-keys and removes the caller's alias, and only the caller's. */
    @Test
    void a_patch_sets_and_removes_only_the_callers_alias() {
        Aliased stored = aliasedResource();

        Answer set = setAlias(connector(), stored.id(), "re-keyed");

        assertThat(set).isEqualTo(new Answer("re-keyed", stored.version() + 1));
        assertThat(aliases.find(connector().connectorId(), stored.id())).contains("re-keyed");

        Answer removed = removeAlias(stored.id());

        assertThat(removed).isEqualTo(new Answer(null, stored.version() + 2));
        assertThat(aliases.find(connector().connectorId(), stored.id())).isEmpty();
        assertThat(readBy(OTHER_CONNECTOR, stored.id())).isEqualTo(THEIRS);
        assertThat(recordedReplaceDetails()).containsExactly("EXTERNAL_ID", "EXTERNAL_ID");
    }

    /** A write that moves no alias writes no alias — not even the stored value again. */
    @Test
    void a_write_that_leaves_the_alias_alone_does_not_write_it() {
        Aliased stored = aliasedResource();
        int aliasWrites = aliases.writes();

        Answer written = changeAnotherAttribute(stored.id());

        assertThat(written.externalId()).isEqualTo(stored.alias());
        assertThat(aliases.writes()).isEqualTo(aliasWrites);
        assertThat(aliases.find(connector().connectorId(), stored.id())).contains(stored.alias());
    }

    /**
     * Another connector's write reaches its own namespace: setting its alias leaves the calling
     * connector's alias, and what it reads back, unchanged.
     */
    @Test
    void another_connector_setting_its_alias_leaves_the_callers() {
        Aliased stored = aliasedResource();

        Answer theirs = setAlias(OTHER_CONNECTOR, stored.id(), "theirs-re-keyed");

        assertThat(theirs.externalId()).isEqualTo("theirs-re-keyed");
        assertThat(aliases.find(connector().connectorId(), stored.id())).contains(stored.alias());
        assertThat(readBy(connector(), stored.id())).isEqualTo(stored.alias());
    }
}
