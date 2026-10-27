package com.example.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.epic.EpicLoginOnContext;
import com.example.backend.SessionCsrf;
import com.example.backend.TokenPermissions;
import com.example.backend.auth.application.LoginIdentityService;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.contract.OpenApiContract.Access;
import com.example.backend.contract.OpenApiContract.Operation;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.domain.ConnectorTokenSecret;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ScimConnectorToken;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import jakarta.servlet.Filter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The authorization contract, driven by the API document: for every documented operation of the
 * application chain, what its {@code security} requirement declares is what the running
 * application enforces (ADR 0015).
 *
 * <p>Every operation under {@code /api} and {@code /actuator} declares its own requirement — public,
 * self-service (authenticated, no Permission), one or more Permissions, every one required, or the
 * Superuser — and then, over the real filter chain:
 *
 * <ul>
 *   <li>for each declared Permission, a caller holding every Permission EXCEPT that one is refused
 *       {@code 403} — so of an operation declaring two, a caller holding either alone is refused —
 *       and a caller holding ONLY the declared ones is refused neither {@code 401} nor
 *       {@code 403};
 *   <li>an operation declaring the Superuser refuses a caller holding every Permission but not
 *       {@code ROLE_SUPERUSER} with {@code 403}, and admits one holding only that role;
 *   <li>self-service needs no Permission, public needs no session, and every other operation
 *       refuses a caller without one {@code 401};
 *   <li>a session confined by a required password change reaches only the self-service trio;
 *   <li>a session — holding every Permission — cannot reach {@code /scim/**}, and a connector token
 *       cannot reach the application chain;
 *   <li>an undeclared {@code /api/} route, or a method a declared route does not serve, is refused;
 *   <li>Unlock on the caller's own account is refused whatever the caller holds;
 *   <li>and each declared Permission, and the Superuser, is ALSO held by the handler itself, with
 *       method security, so the chain's matching rule is a backstop rather than the only line.
 * </ul>
 *
 * <p>{@link RouteContractTests} keeps every mapped route documented, so a new route cannot escape
 * this contract by being left out of the document.
 *
 * <p>The caller's session is built directly — its security context and principal index set on a
 * {@link MockHttpSession}, which the chain reads exactly as it reads one a login wrote — so each
 * case can hold precisely the Permissions it needs without a Group per combination. The principal
 * is the seeded {@code test-user}, a real User, so handlers that resolve the caller find it.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
class AuthorizationContractTests extends EpicLoginOnContext {

    private static final String CALLER = "test-user";

    private static final String ADMIN = "test-admin";

    private static final String BASELINE = "ROLE_USER";

    /** The Superuser authority: not a Permission, so it is in no list of Permissions below. */
    private static final String SUPERUSER = "ROLE_SUPERUSER";

    /** The namespaces the application chain serves and this contract covers. */
    private static final List<String> APPLICATION_NAMESPACES = List.of("/api/", "/actuator/");

    /** The only operations a session confined by a required password change may reach. */
    private static final Set<String> CONFINED_REACH = Set.of(
            "GET /api/auth/me", "POST /api/auth/change-password", "DELETE /api/auth/logout");

    private static final List<String> EVERY_PERMISSION =
            Arrays.stream(Permission.values()).map(Permission::value).toList();

    /** Everything a session can hold: every Permission, and the Superuser beside them. */
    private static final List<String> EVERYTHING =
            Stream.concat(EVERY_PERMISSION.stream(), Stream.of(SUPERUSER)).toList();

    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScimConnectorTokenRepository tokenStore;

    /** Tokens minted so far by the Permissions they carry; each test class instance mints its own. */
    private final Map<Set<Permission>, String> tokensByPermissions = new HashMap<>();

    private UUID scimConnector;

    /** The directory Permissions' spellings, the only ones a SCIM operation may name. */
    private static final List<String> DIRECTORY =
            TokenPermissions.ALL.stream().map(Permission::value).toList();

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private MockMvc mvc;

    private UUID callerId;

    @BeforeEach
    void setUp() {
        // No session repository filter: the MockHttpSession each case builds is the session.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, springSecurityFilterChain)
                .build();
        callerId = users.findByNormalizedUserName(NormalizedUserName.of(CALLER))
                .orElseThrow().id();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- the document itself ----------------------------------------------------------------

    @Test
    void every_application_operation_declares_public_self_service_the_superuser_or_known_permissions() {
        List<Operation> operations = applicationOperations();
        assertThat(operations).as("the document lists application operations").hasSizeGreaterThan(20);
        for (Operation operation : operations) {
            OpenApiContract.Requirement requirement = operation.requirement();
            assertThat(requirement.access())
                    .as("%s declares its own security requirement", operation)
                    .isIn(Access.PUBLIC, Access.SELF_SERVICE, Access.PERMISSION, Access.SUPERUSER);
            if (requirement.access() == Access.PERMISSION) {
                assertThat(requirement.allOf())
                        .as("%s names Permissions that exist", operation)
                        .isNotEmpty()
                        .allSatisfy(permission -> assertThat(Permission.fromValue(permission))
                                .isPresent());
            }
            if (requirement.access() == Access.SUPERUSER) {
                assertThat(requirement.allOf())
                        .as("%s names the Superuser, which is no Permission", operation)
                        .isEmpty();
            }
        }
    }

    // ---- per declaration ----------------------------------------------------------------------

    /**
     * Each declared Permission is required — a caller holding every Permission but that one is
     * refused — and the declared ones together suffice. Of an operation declaring two, a caller
     * holding either alone is refused, since "every Permission but the other" includes it.
     */
    @Test
    void each_declared_permission_is_required_and_together_they_are_sufficient() throws Exception {
        List<Operation> protectedOperations = withAccess(Access.PERMISSION);
        assertThat(protectedOperations).hasSizeGreaterThan(7);
        List<String> failures = new ArrayList<>();
        for (Operation operation : protectedOperations) {
            List<String> declared = operation.requirement().allOf();
            for (String permission : declared) {
                List<String> allBut = new ArrayList<>(EVERY_PERMISSION);
                allBut.remove(permission);

                int lacking = call(operation, session(allBut));
                if (lacking != 403) {
                    failures.add(operation + " without " + permission + " -> " + lacking);
                }
            }
            int holding = call(operation, session(declared));
            if (holding == 401 || holding == 403) {
                failures.add(operation + " holding only " + declared + " -> " + holding);
            }
        }
        assertThat(failures).isEmpty();
    }

    /**
     * The one operation declaring two Permissions: reading a Group's members, which are
     * Users-projection data, needs {@code user:read} beside {@code group:read} (ADR 0015). Held
     * alone, either is refused — by the chain and by the handler — so {@code group:read} discloses
     * a Group's member count and never who its members are.
     */
    @Test
    void reading_a_groups_members_needs_both_group_read_and_user_read() throws Exception {
        List<Operation> twoPermissions = withAccess(Access.PERMISSION).stream()
                .filter(operation -> operation.requirement().allOf().size() > 1)
                .toList();
        assertThat(twoPermissions).extracting(Operation::toString)
                .containsExactly("GET /api/admin/groups/{id}/members");
        Operation members = twoPermissions.getFirst();
        assertThat(members.requirement().allOf()).containsExactly("group:read", "user:read");
        HandlerMethod handler = handlerOf(members);
        Object bean = handler.getBean() instanceof String name
                ? context.getBean(name) : handler.getBean();
        for (String alone : List.of("group:read", "user:read")) {
            assertThat(call(members, session(List.of(alone))))
                    .as("%s holding only %s", members, alone)
                    .isEqualTo(403);
            assertThat(invoke(bean, handler.getMethod(), List.of(alone)))
                    .as("%s handler holding only %s", members, alone)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    /**
     * The Superuser-only operations are exactly connector creation and deletion, the three token
     * operations and setting a Group's Permissions: the document names no Permission for them,
     * and a session holding every
     * Permission but not {@code ROLE_SUPERUSER} is refused {@code 403} where one holding that role
     * alone is refused neither {@code 401} nor {@code 403}.
     */
    @Test
    void each_superuser_only_operation_requires_the_superuser_and_no_permission_stands_in()
            throws Exception {
        List<Operation> superuserOnly = withAccess(Access.SUPERUSER);
        assertThat(superuserOnly).extracting(Operation::toString).containsExactlyInAnyOrder(
                "POST /api/admin/connectors", "DELETE /api/admin/connectors/{connectorId}",
                "POST /api/admin/connectors/{connectorId}/tokens",
                "POST /api/admin/connectors/{connectorId}/tokens/{tokenId}/rotate",
                "POST /api/admin/connectors/{connectorId}/tokens/{tokenId}/revoke",
                "PUT /api/admin/groups/{id}/permissions");
        List<String> failures = new ArrayList<>();
        for (Operation operation : superuserOnly) {
            int everyPermission = call(operation, session(EVERY_PERMISSION));
            if (everyPermission != 403) {
                failures.add(operation + " holding every Permission -> " + everyPermission);
            }
            int superuser = call(operation, session(List.of(SUPERUSER)));
            if (superuser == 401 || superuser == 403) {
                failures.add(operation + " holding only the Superuser -> " + superuser);
            }
        }
        assertThat(failures).isEmpty();
    }

    /**
     * The helpdesk power: Unlock, and nothing else, is declared under {@code account:unlock}, a
     * Permission a Group may carry and a token may not (ADR 0015).
     */
    @Test
    void only_unlock_is_declared_under_account_unlock() {
        assertThat(withAccess(Access.PERMISSION))
                .filteredOn(operation -> operation.requirement().allOf()
                        .contains("account:unlock"))
                .extracting(Operation::toString)
                .containsExactly("POST /api/admin/accounts/{id}/unlock");
    }

    /** No session holds a token-only Permission, so no session operation may require one. */
    @Test
    void no_session_operation_declares_a_token_only_permission() {
        List<String> tokenOnly = Arrays.stream(Permission.values())
                .filter(Permission::tokenOnly)
                .map(Permission::value)
                .toList();
        assertThat(withAccess(Access.PERMISSION))
                .flatExtracting(operation -> operation.requirement().allOf())
                .doesNotContainAnyElementsOf(tokenOnly);
    }

    @Test
    void self_service_needs_no_permission() throws Exception {
        List<Operation> selfService = withAccess(Access.SELF_SERVICE);
        assertThat(selfService).extracting(Operation::toString).contains(
                "GET /api/auth/me", "POST /api/auth/change-password", "DELETE /api/auth/logout",
                "GET /api/self");
        for (Operation operation : selfService) {
            assertThat(call(operation, session(List.of())))
                    .as("%s with baseline access alone", operation)
                    .isNotIn(401, 403);
        }
    }

    @Test
    void public_operations_need_no_session() throws Exception {
        List<Operation> open = withAccess(Access.PUBLIC);
        assertThat(open).extracting(Operation::toString).containsExactlyInAnyOrder(
                "GET /api/auth/csrf", "POST /api/auth/login", "GET /api/auth/epic/jwks.json",
                "GET /api/auth/epic/launch", "GET /api/auth/epic/authorize",
                "GET /api/auth/epic/callback", "GET /actuator/health");
        for (Operation operation : open) {
            assertThat(call(operation, null)).as("%s with no session", operation)
                    .isNotIn(401, 403);
        }
    }

    @Test
    void every_other_operation_refuses_a_caller_without_a_session() throws Exception {
        for (Operation operation : applicationOperations()) {
            if (operation.requirement().access() == Access.PUBLIC) {
                continue;
            }
            assertThat(call(operation, null)).as("%s with no session", operation)
                    .isEqualTo(401);
        }
    }

    @Test
    void a_session_confined_by_a_required_password_change_reaches_only_the_change_flow()
            throws Exception {
        for (Operation operation : applicationOperations()) {
            if (operation.requirement().access() == Access.PUBLIC) {
                continue;
            }
            int status = call(operation,
                    session(List.of(LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY), false));
            if (CONFINED_REACH.contains(operation.toString())) {
                assertThat(status).as("%s while confined", operation).isNotIn(401, 403);
            } else {
                assertThat(status).as("%s while confined", operation).isEqualTo(403);
            }
        }
    }

    // ---- the chains' boundaries ---------------------------------------------------------------

    @Test
    void a_session_holding_every_permission_cannot_reach_scim() throws Exception {
        // Every SCIM operation takes a token, discovery included, so none answers a session.
        List<Operation> scim = scimOperations();
        assertThat(scim).hasSizeGreaterThan(10);
        for (Operation operation : scim) {
            assertThat(call(operation, session(EVERYTHING)))
                    .as("%s with a session", operation)
                    .isEqualTo(401);
        }
    }

    // ---- the SCIM chain, per declaration ------------------------------------------------------

    @Test
    void every_scim_operation_declares_a_token_and_at_most_one_directory_permission() {
        List<Operation> scim = scimOperations();
        assertThat(scim).hasSizeGreaterThan(20);
        for (Operation operation : scim) {
            OpenApiContract.Requirement requirement = operation.requirement();
            assertThat(requirement.access())
                    .as("%s declares a connector token", operation)
                    .isIn(Access.BEARER, Access.BEARER_ANY_OF);
            List<String> named = requirement.access() == Access.BEARER_ANY_OF
                    ? requirement.anyOf()
                    : requirement.permission() == null ? List.of() : List.of(requirement.permission());
            assertThat(named).as("%s names directory Permissions only", operation)
                    .allSatisfy(permission -> assertThat(DIRECTORY).contains(permission));
        }
        assertThat(scim).filteredOn(operation -> operation.requirement().access() == Access.BEARER_ANY_OF)
                .extracting(Operation::toString)
                .containsExactly("POST /scim/v2/.search");
    }

    /**
     * For every SCIM operation naming a Permission: a token holding every OTHER directory
     * Permission is refused {@code 403}, and one holding ONLY the declared one is refused neither
     * {@code 401} nor {@code 403}. Write does not imply read, nor read write.
     */
    @Test
    void each_declared_token_permission_is_required_and_is_sufficient() throws Exception {
        List<Operation> declared = scimOperations().stream()
                .filter(operation -> operation.requirement().access() == Access.BEARER)
                .filter(operation -> operation.requirement().permission() != null)
                .toList();
        assertThat(declared).hasSizeGreaterThan(10);
        List<String> failures = new ArrayList<>();
        for (Operation operation : declared) {
            Permission permission = Permission.fromValue(operation.requirement().permission())
                    .orElseThrow();
            Set<Permission> allBut = EnumSet.copyOf(TokenPermissions.ALL);
            allBut.remove(permission);

            int lacking = callScim(operation, token(allBut));
            if (lacking != 403) {
                failures.add(operation + " without " + permission.value() + " -> " + lacking);
            }
            int holding = callScim(operation, token(Set.of(permission)));
            if (holding == 401 || holding == 403) {
                failures.add(operation + " holding only " + permission.value() + " -> " + holding);
            }
        }
        assertThat(failures).isEmpty();
    }

    /** Discovery and {@code /Me} take a valid token holding no Permission at all. */
    @Test
    void a_token_holding_no_permission_reaches_discovery_and_me() throws Exception {
        List<Operation> tokenOnly = scimOperations().stream()
                .filter(operation -> operation.requirement().access() == Access.BEARER)
                .filter(operation -> operation.requirement().permission() == null)
                .toList();
        assertThat(tokenOnly).extracting(Operation::template).contains(
                "/scim/v2/ServiceProviderConfig", "/scim/v2/ResourceTypes", "/scim/v2/Schemas",
                "/scim/v2/Me");
        String none = token(Set.of());
        for (Operation operation : tokenOnly) {
            assertThat(callScim(operation, none)).as("%s with an empty token", operation)
                    .isNotIn(401, 403);
            assertThat(callScim(operation, null)).as("%s with no token", operation)
                    .isEqualTo(401);
        }
    }

    /** The base {@code /.search}: either read Permission suffices, the writes alone do not. */
    @Test
    void the_base_search_needs_one_read_permission() throws Exception {
        Operation search = scimOperations().stream()
                .filter(operation -> operation.requirement().access() == Access.BEARER_ANY_OF)
                .findFirst().orElseThrow();
        for (String alternative : search.requirement().anyOf()) {
            assertThat(callScim(search,
                    token(Set.of(Permission.fromValue(alternative).orElseThrow()))))
                    .as("%s holding only %s", search, alternative)
                    .isNotIn(401, 403);
        }
        assertThat(callScim(search, token(Set.of(Permission.USER_WRITE, Permission.GROUP_WRITE))))
                .as("%s holding only the write Permissions", search)
                .isEqualTo(403);
    }

    /**
     * A path no operation declares is not found — not refused — for a token holding no Permission
     * at all: a {@code 403} would claim the endpoint exists. The contract tests above are what keep
     * a real endpoint from hiding here: every mapped route is documented, and every documented
     * operation's Permission is proved required.
     */
    @Test
    void an_undeclared_scim_path_is_not_found_even_holding_no_permission() throws Exception {
        String none = token(Set.of());
        for (String path : List.of("/scim/v2/Devices", "/scim/v2/Bulk", "/scim/v2/users")) {
            assertThat(mvc.perform(request(HttpMethod.GET, path)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + none))
                    .andReturn().getResponse().getStatus())
                    .as("GET %s", path)
                    .isEqualTo(404);
        }
    }

    @Test
    void a_connector_token_cannot_reach_the_application_chain() throws Exception {
        UUID connector = connectors.create("authorization-contract", null).id();
        String token = connectors.issueToken(connector, TokenPermissions.ALL, null, null)
                .presentedValue();
        for (Operation operation : applicationOperations()) {
            if (operation.requirement().access() == Access.PUBLIC) {
                continue;
            }
            MockHttpServletRequestBuilder request = build(operation)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            if (unsafe(operation)) {
                // A guest session's CSRF token, so the refusal is the authorization one and not
                // the CSRF filter's.
                SessionCsrf.withCsrf(mvc, request);
            }
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .as("%s with a bearer token", operation)
                    .isEqualTo(401);
        }
    }

    @Test
    void an_undeclared_route_or_method_is_refused_even_to_the_superuser_holding_every_permission()
            throws Exception {
        for (String[] undeclared : List.of(
                new String[] {"GET", "/api/undeclared"},
                new String[] {"POST", "/api/admin/undeclared"},
                new String[] {"POST", "/api/admin/accounts"},
                new String[] {"PUT", "/api/admin/accounts/" + UUID.randomUUID()},
                new String[] {"DELETE", "/api/admin/groups"},
                // The vocabulary a Group may carry is code's: nothing writes it at runtime.
                new String[] {"POST", "/api/admin/permissions"},
                new String[] {"PUT", "/api/admin/permissions"},
                new String[] {"PATCH", "/api/admin/permissions"},
                new String[] {"DELETE", "/api/admin/permissions"},
                new String[] {"GET", "/api/admin"})) {
            MockHttpServletRequestBuilder request =
                    request(HttpMethod.valueOf(undeclared[0]), undeclared[1])
                            .session(session(EVERYTHING));
            if (!"GET".equals(undeclared[0])) {
                SessionCsrf.withCsrf(mvc, request.contentType(MediaType.APPLICATION_JSON)
                        .content("{}"));
            }
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .as("%s %s", undeclared[0], undeclared[1])
                    .isEqualTo(403);
        }
    }

    @Test
    void unlock_on_the_callers_own_account_is_refused() throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.POST,
                "/api/admin/accounts/" + callerId + "/unlock")
                .session(session(EVERYTHING));
        SessionCsrf.withCsrf(mvc, request);
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                .as("unlock on the caller's own account, holding every Permission")
                .isEqualTo(403);
    }

    // ---- the handler's own declaration ------------------------------------------------------

    /**
     * Past the chain: each documented operation's handler, invoked through its Spring proxy with
     * no filter in front of it, refuses a caller lacking any one declared Permission and admits
     * one holding only the declared ones. This is what makes method security the authority and the URL rule the
     * backstop — remove a handler's declaration and the chain alone would still pass the tests
     * above. Arguments are placeholders: an admitted call may fail on them, a refused one never
     * reaches them. Actuator endpoints have no controller handler; the chain is their declaration.
     */
    @Test
    void each_handler_holds_its_declared_permission_itself() throws Exception {
        List<Operation> handled = withAccess(Access.PERMISSION).stream()
                .filter(operation -> operation.template().startsWith("/api/"))
                .toList();
        assertThat(handled).hasSizeGreaterThan(6);
        List<String> failures = new ArrayList<>();
        for (Operation operation : handled) {
            HandlerMethod handler = handlerOf(operation);
            Object bean = handler.getBean() instanceof String name
                    ? context.getBean(name) : handler.getBean();
            Method method = handler.getMethod();
            List<String> declared = operation.requirement().allOf();
            for (String permission : declared) {
                List<String> allBut = new ArrayList<>(EVERY_PERMISSION);
                allBut.remove(permission);

                if (!(invoke(bean, method, allBut) instanceof AccessDeniedException)) {
                    failures.add(operation + " handler admitted a caller without " + permission);
                }
            }
            if (invoke(bean, method, declared) instanceof AccessDeniedException) {
                failures.add(operation + " handler refused a caller holding " + declared);
            }
        }
        assertThat(failures).isEmpty();
    }

    /**
     * And the Superuser's: each Superuser-only operation's handler, invoked through its Spring proxy
     * with no filter in front of it, refuses a caller holding every Permission without the
     * Superuser role and admits one holding that role alone.
     */
    @Test
    void each_superuser_only_handler_holds_the_superuser_requirement_itself() {
        List<Operation> handled = withAccess(Access.SUPERUSER);
        assertThat(handled).hasSize(6);
        List<String> failures = new ArrayList<>();
        for (Operation operation : handled) {
            HandlerMethod handler = handlerOf(operation);
            Object bean = handler.getBean() instanceof String name
                    ? context.getBean(name) : handler.getBean();
            Method method = handler.getMethod();

            if (!(invoke(bean, method, EVERY_PERMISSION) instanceof AccessDeniedException)) {
                failures.add(operation + " handler admitted a caller holding every Permission");
            }
            if (invoke(bean, method, List.of(SUPERUSER)) instanceof AccessDeniedException) {
                failures.add(operation + " handler refused the Superuser");
            }
        }
        assertThat(failures).isEmpty();
    }

    /**
     * The same, for the SCIM chain: each documented SCIM operation's handler, invoked through its
     * Spring proxy with no filter in front of it, holds the Permission the document declares — so
     * the bearer filter's request-line rule is a backstop, not the only line. A token-only
     * operation (discovery, {@code /Me}) admits a caller holding no Permission at all, and the
     * base {@code .search} admits either read Permission alone and refuses every other one.
     */
    @Test
    void each_scim_handler_holds_its_declared_permission_itself() {
        List<Operation> scim = scimOperations();
        assertThat(scim).hasSizeGreaterThan(20);
        List<String> failures = new ArrayList<>();
        for (Operation operation : scim) {
            HandlerMethod handler = handlerOf(operation);
            Object bean = handler.getBean() instanceof String name
                    ? context.getBean(name) : handler.getBean();
            Method method = handler.getMethod();
            OpenApiContract.Requirement requirement = operation.requirement();
            List<String> admitting = requirement.access() == Access.BEARER_ANY_OF
                    ? requirement.anyOf()
                    : requirement.permission() == null ? List.of() : List.of(requirement.permission());

            if (admitting.isEmpty()) {
                if (invoke(bean, method, List.of()) instanceof AccessDeniedException) {
                    failures.add(operation + " handler refused a token holding no Permission");
                }
                continue;
            }
            List<String> otherwise = new ArrayList<>(DIRECTORY);
            otherwise.removeAll(admitting);
            if (!(invoke(bean, method, otherwise) instanceof AccessDeniedException)) {
                failures.add(operation + " handler admitted a token holding only " + otherwise);
            }
            for (String permission : admitting) {
                if (invoke(bean, method, List.of(permission)) instanceof AccessDeniedException) {
                    failures.add(operation + " handler refused a token holding " + permission);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static List<Operation> scimOperations() {
        return CONTRACT.operations().stream()
                .filter(operation -> operation.template().startsWith("/scim/v2/"))
                .toList();
    }

    /**
     * A live token for one connector carrying exactly {@code permissions}, minted once per set.
     * An EMPTY set cannot be issued — the service refuses it — so it is stored directly, as a
     * token from before tokens carried Permissions would be.
     */
    private String token(Set<Permission> permissions) {
        return tokensByPermissions.computeIfAbsent(Set.copyOf(permissions), wanted -> {
            if (scimConnector == null) {
                scimConnector = connectors.create("authorization-contract-scim", null).id();
            }
            if (!wanted.isEmpty()) {
                return connectors.issueToken(
                        scimConnector, wanted, null, null).presentedValue();
            }
            ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(new SecureRandom());
            Instant now = Instant.now();
            tokenStore.save(ScimConnectorToken.issue(UUID.randomUUID(), scimConnector,
                    minted.lookupId(), minted.digest(), TokenPermissions.of(Set.of()), now,
                    now.plus(Duration.ofDays(1))));
            return minted.presentedValue();
        });
    }

    /** The SCIM operation's status with this token ({@code null}: none). */
    private int callScim(Operation operation, String token) throws Exception {
        MockHttpServletRequestBuilder request = build(operation);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
    private static List<Operation> applicationOperations() {
        return CONTRACT.operations().stream()
                .filter(operation -> APPLICATION_NAMESPACES.stream()
                        .anyMatch(operation.template()::startsWith))
                .toList();
    }

    private static List<Operation> withAccess(Access access) {
        return applicationOperations().stream()
                .filter(operation -> operation.requirement().access() == access)
                .toList();
    }

    /** A signed-in session holding baseline access and these Permissions. */
    private MockHttpSession session(Collection<String> permissions) {
        return session(permissions, true);
    }

    private MockHttpSession session(Collection<String> authorities, boolean baseline) {
        List<String> held = new ArrayList<>(authorities);
        if (baseline) {
            held.add(BASELINE);
        }
        UserDetails principal = User.withUsername(CALLER).password("unused")
                .authorities(held.toArray(String[]::new)).build();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, callerId.toString());
        return session;
    }

    /** The operation's status for this session ({@code null}: none), CSRF token included. */
    private int call(Operation operation, MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = build(operation);
        if (session != null) {
            request.session(session);
        }
        if (unsafe(operation)) {
            SessionCsrf.withCsrf(mvc, request);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /**
     * The operation as a request: its template with every path variable a fresh id — so an
     * admitted call names nothing and answers 404 rather than acting on anyone — and, on an unsafe
     * method, an empty JSON object, which an admitted call answers 400 or acts on harmlessly.
     */
    private static MockHttpServletRequestBuilder build(Operation operation) {
        String path = operation.template().replaceAll("\\{[^}]*}", UUID.randomUUID().toString());
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(operation.method()), path);
        if (unsafe(operation)) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return request;
    }

    private static boolean unsafe(Operation operation) {
        return !Set.of("GET", "HEAD", "OPTIONS").contains(operation.method());
    }

    private HandlerMethod handlerOf(Operation operation) {
        String route = operation.template().replaceAll("\\{[^}]*}", "{}");
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            boolean method = methods.stream().anyMatch(m -> m.name().equals(operation.method()));
            boolean path = entry.getKey().getPatternValues().stream()
                    .anyMatch(pattern -> pattern.replaceAll("\\{[^}]*}", "{}").equals(route));
            if (method && path) {
                return entry.getValue();
            }
        }
        throw new AssertionError("no handler maps " + operation);
    }

    /** What invoking the handler under these authorities threw, or {@code null}. */
    private static Throwable invoke(Object bean, Method method, List<String> permissions) {
        List<String> held = new ArrayList<>(permissions);
        held.add(BASELINE);
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                CALLER, null,
                held.stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toList()));
        SecurityContextHolder.setContext(securityContext);
        Object[] arguments = Stream.of(method.getParameterTypes())
                .map(AuthorizationContractTests::placeholder)
                .toArray();
        try {
            method.invoke(bean, arguments);
            return null;
        } catch (InvocationTargetException thrown) {
            return thrown.getCause();
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(unreachable);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Object placeholder(Class<?> type) {
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == boolean.class) {
            return false;
        }
        return null;
    }
}
