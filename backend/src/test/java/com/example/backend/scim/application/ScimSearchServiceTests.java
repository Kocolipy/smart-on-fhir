package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.TokenPermissions;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.InMemoryScimExternalIdRepository;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimPasswordHistoryRepository;
import com.example.backend.scim.InMemoryScimQueryRepository;
import com.example.backend.scim.InMemoryScimTombstoneRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.config.ScimPasswordAcceptanceConfig;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.PasswordAcceptance;
import com.example.backend.scim.domain.ScimFilterParser;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimQuery;
import com.example.backend.scim.domain.ScimQueryRepository;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUser;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The base search use case: one query across both types, resources assembled by each type's own
 * service, returned in the query's interleaved order, audited once.
 *
 * <p>The order and the selection are the query port's; the port here is a scripted one, so the
 * test is about what the use case does with an answer, including one that names a resource that
 * has since gone.
 */
class ScimSearchServiceTests {

    private static final AuthenticatedConnector CONNECTOR = new AuthenticatedConnector(
            UUID.randomUUID(), UUID.randomUUID(), TokenPermissions.of(TokenPermissions.READ));

    private static final Set<ScimResourceType> BOTH =
            Set.of(ScimResourceType.USER, ScimResourceType.GROUP);

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final InMemoryScimExternalIdRepository aliases = new InMemoryScimExternalIdRepository();

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final Clock clock = Clock.fixed(ScimIdentities.NOW, ZoneOffset.UTC);

    private final InMemoryScimQueryRepository defaultQueries =
            new InMemoryScimQueryRepository(users, groups);

    /** Searching sets no password, so acceptance is wired without an encoder behind it. */
    private static final PasswordAcceptance PASSWORDS_UNUSED = new PasswordAcceptance(
            new InMemoryScimPasswordHistoryRepository(), ScimPasswordAcceptanceConfig.hasher(null));

    private final ScimUserService userService = new ScimUserService(
            users, new InMemoryScimWriteLocks(), groups, aliases, PASSWORDS_UNUSED,
            (connectorId, userId, causes) -> { }, LoginStateModules.over(users, clock),
            new InMemoryScimTombstoneRepository(), audit, clock, defaultQueries,
            com.example.backend.authorization.TestGroupPermissions.superuserOnly(), () -> { });

    private final ScimGroupService groupService = new ScimGroupService(
            groups, new InMemoryScimWriteLocks(), users, aliases,
            (connectorId, userId, causes) -> { },
            new InMemoryScimTombstoneRepository(), audit, clock,
            defaultQueries, com.example.backend.authorization.TestGroupPermissions.superuserOnly(),
            () -> { });

    private ScimSearchService search(ScimQueryRepository queries) {
        return new ScimSearchService(queries, userService, groupService, audit);
    }

    @Test
    void the_page_interleaves_both_types_in_the_query_order_and_skips_a_vanished_one() {
        ScimUser ada = users.create(ScimIdentities.user("ada"));
        ScimGroup eng = groups.create(ScimIdentities.group("Engineering", ada));
        ScimUser bea = users.create(ScimIdentities.user("bea"));
        UUID gone = UUID.randomUUID();
        List<ScimQuery.Hit> hits = List.of(
                new ScimQuery.Hit(ScimResourceType.GROUP, eng.id()),
                new ScimQuery.Hit(ScimResourceType.USER, bea.id()),
                new ScimQuery.Hit(ScimResourceType.USER, gone),
                new ScimQuery.Hit(ScimResourceType.USER, ada.id()));
        List<String> baseUris = new ArrayList<>();
        ScimQueryRepository scripted = (query, connectorId, baseUri) -> {
            assertThat(connectorId).isEqualTo(CONNECTOR.connectorId());
            baseUris.add(baseUri);
            return new ScimQuery.Result(12, hits);
        };
        ScimQuery query = new ScimQuery(
                BOTH, ScimFilterParser.parse("displayName pr or userName pr", BOTH), null,
                new PageRequest(3, 4));

        ScimSearchListing listing = search(scripted).search(CONNECTOR, query, "https://x/scim/v2");

        assertThat(listing.resources()).extracting(ScimListedResource::id)
                .containsExactly(eng.id(), bea.id(), ada.id());
        assertThat(listing.resources().get(0)).isInstanceOf(ScimGroupResource.class);
        assertThat(listing.resources().get(1)).isInstanceOf(ScimUserResource.class);
        assertThat(listing.totalResults()).isEqualTo(12);
        assertThat(listing.page()).isEqualTo(new PageRequest(3, 4));
        assertThat(baseUris).containsExactly("https://x/scim/v2");
        assertThat(audit.of(AuditOperation.SCIM_RESOURCE_LIST))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(CONNECTOR.connectorId());
                    assertThat(event.detail()).isEqualTo("3 (displayName pr or userName pr)");
                });
        assertThat(audit.recorded()).hasSize(1);
    }

    /**
     * A token that may read Users alone searches Users alone (ADR 0015): the query reaching the
     * port names only the type it may read, so the total and the page count Users and nothing else.
     */
    @Test
    void a_search_is_narrowed_to_the_types_the_token_may_read() {
        ScimUser ada = users.create(ScimIdentities.user("ada"));
        groups.create(ScimIdentities.group("Engineering", ada));
        List<ScimQuery> asked = new ArrayList<>();
        ScimQueryRepository recording = (query, connectorId, baseUri) -> {
            asked.add(query);
            return defaultQueries.query(query, connectorId, baseUri);
        };
        AuthenticatedConnector userReader = new AuthenticatedConnector(UUID.randomUUID(),
                UUID.randomUUID(), TokenPermissions.of(Set.of(Permission.USER_READ)));
        ScimQuery query = new ScimQuery(BOTH, null, null, new PageRequest(1, 10));

        ScimSearchListing listing = search(recording).search(userReader, query, "u");

        assertThat(asked).singleElement().satisfies(narrowed -> {
            assertThat(narrowed.types()).containsExactly(ScimResourceType.USER);
            assertThat(narrowed.page()).isEqualTo(query.page());
        });
        assertThat(listing.resources()).extracting(ScimListedResource::id).containsExactly(ada.id());
        assertThat(listing.totalResults()).isEqualTo(1);
    }

    /** An empty result is still one bulk read, with no filter shape when there was no filter. */
    @Test
    void an_empty_search_is_audited_once() {
        ScimSearchListing listing = search(defaultQueries).search(
                CONNECTOR, new ScimQuery(BOTH, null, null, new PageRequest(1, 0)), "u");

        assertThat(listing.resources()).isEmpty();
        assertThat(listing.totalResults()).isZero();
        assertThat(audit.of(AuditOperation.SCIM_RESOURCE_LIST))
                .singleElement()
                .extracting(RecordingAuditTrail.Recorded::detail)
                .isEqualTo("0");
    }

    /** The User query audits the count it returned, and the filter's shape. */
    @Test
    void a_user_query_audits_its_returned_count_and_shape() {
        users.create(ScimIdentities.user("ada"));
        users.create(ScimIdentities.user("bea"));
        Set<ScimResourceType> usersOnly = Set.of(ScimResourceType.USER);
        ScimQueryRepository scripted = (query, connectorId, baseUri) -> new ScimQuery.Result(
                5, List.of(new ScimQuery.Hit(ScimResourceType.USER,
                        users.require("bea").id())));

        ScimUserService service = new ScimUserService(
                users, new InMemoryScimWriteLocks(), groups, aliases, PASSWORDS_UNUSED,
                (connectorId, userId, causes) -> { }, LoginStateModules.over(users, clock),
                new InMemoryScimTombstoneRepository(), audit, clock, scripted,
                com.example.backend.authorization.TestGroupPermissions.superuserOnly(), () -> { });
        ScimUserListing listing = service.query(CONNECTOR, new ScimQuery(
                usersOnly, ScimFilterParser.parse("userName eq \"bea\"", usersOnly), null, null),
                "u");

        assertThat(listing.resources()).extracting(ScimUserResource::id)
                .containsExactly(users.require("bea").id());
        assertThat(listing.totalResults()).isEqualTo(5);
        assertThat(audit.of(AuditOperation.SCIM_USER_LIST))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isEqualTo(CONNECTOR.connectorId());
                    assertThat(event.detail()).isEqualTo("1 userName eq ?");
                });
    }
}
