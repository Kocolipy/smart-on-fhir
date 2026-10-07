package com.example.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.example.backend.ContainerTestConfiguration;
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
import org.springframework.test.context.TestPropertySource;
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
 * application enforces (ADR 0010).
 *
 * <p>Every operation under {@code /api} and {@code /actuator} declares its own requirement — public,
 * self-service (authenticated, no Permission) or exactly one Permission — and then, over the real
 * filter chain:
 *
 * <ul>
 *   <li>a caller holding every Permission EXCEPT the declared one is refused {@code 403}, and a
 *       caller holding ONLY it is refused neither {@code 401} nor {@code 403};
 *   <li>self-service needs no Permission, and public needs no session;
 *   <li>a session confined by a required password change reaches only the self-service trio;
 *   <li>a session — holding every Permission — cannot reach {@code /scim/**}, and a connector token
 *       cannot reach the application chain;
 *   <li>an undeclared {@code /api/} route, or a method a declared route does not serve, is refused;
 *   <li>Unlock and the forced password change on the caller's own account are refused whatever the
 *       caller holds;
 *   <li>and each declared Permission is ALSO held by the handler itself, with method security, so
 *       the chain's matching rule is a backstop rather than the only line.
 * </ul>
 *
 * <p>{@link RouteContractTests} keeps every mapped route documented, so a new route cannot escape
 * this contract by being left out of the document.
 *
 * <p>The caller's session is built directly — its security context and principal index set on a
 * {@link MockHttpSession}, which the chain reads exactly as it reads one a login wrote — so each
 * case can hold precisely the Permissions it needs without a Role per combination. The principal
 * is the seeded {@code test-user}, a real User, so handlers that resolve the caller find it.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@AutoConfigureMetrics
@TestPropertySource(properties = "app.scim.enabled=true")
class AuthorizationContractTests {

    private static final String CALLER = "test-user";

    private static final String ADMIN = "test-admin";

    private static final String BASELINE = "ROLE_USER";

    /** The namespaces the application chain serves and this contract covers. */
    private static final List<String> APPLICATION_NAMESPACES = List.of("/api/", "/actuator/");

    /** The only operations a session confined by a required password change may reach. */
    private static final Set<String> CONFINED_REACH = Set.of(
            "GET /api/auth/me", "POST /api/auth/change-password", "DELETE /api/auth/logout");

    private static final List<String> EVERY_PERMISSION =
            Arrays.stream(Permission.values()).map(Permission::value).toList();

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
    void every_application_operation_declares_public_self_service_or_one_known_permission() {
        List<Operation> operations = applicationOperations();
        assertThat(operations).as("the document lists application operations").hasSizeGreaterThan(20);
        for (Operation operation : operations) {
            OpenApiContract.Requirement requirement = operation.requirement();
            assertThat(requirement.access())
                    .as("%s declares its own security requirement", operation)
                    .isIn(Access.PUBLIC, Access.SELF_SERVICE, Access.PERMISSION);
            if (requirement.access() == Access.PERMISSION) {
                assertThat(Permission.fromValue(requirement.permission()))
                        .as("%s names a Permission that exists", operation)
                        .isPresent();
            }
        }
    }

    // ---- per declaration ----------------------------------------------------------------------

    @Test
    void each_declared_permission_is_required_and_is_sufficient() throws Exception {
        List<Operation> protectedOperations = withAccess(Access.PERMISSION);
        assertThat(protectedOperations).hasSizeGreaterThan(10);
        List<String> failures = new ArrayList<>();
        for (Operation operation : protectedOperations) {
            String permission = operation.requirement().permission();
            List<String> allBut = new ArrayList<>(EVERY_PERMISSION);
            allBut.remove(permission);

            int lacking = call(operation, session(allBut));
            if (lacking != 403) {
                failures.add(operation + " without " + permission + " -> " + lacking);
            }
            int holding = call(operation, session(List.of(permission)));
            if (holding == 401 || holding == 403) {
                failures.add(operation + " holding only " + permission + " -> " + holding);
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void self_service_needs_no_permission() throws Exception {
        List<Operation> selfService = withAccess(Access.SELF_SERVICE);
        assertThat(selfService).extracting(Operation::toString).contains(
                "GET /api/auth/me", "POST /api/auth/change-password", "DELETE /api/auth/logout",
                "GET /api/self", "GET /api/session", "PUT /api/session", "DELETE /api/session");
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
                "GET /actuator/health");
        for (Operation operation : open) {
            assertThat(call(operation, null)).as("%s with no session", operation)
                    .isNotIn(401, 403);
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
            assertThat(call(operation, session(EVERY_PERMISSION)))
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
        UUID connector = connectors.create("authorization-contract", ADMIN).id();
        String token = connectors.issueToken(connector, TokenPermissions.ALL, null, ADMIN, TokenPermissions.ALL)
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
    void an_undeclared_route_or_method_is_refused_even_holding_every_permission()
            throws Exception {
        for (String[] undeclared : List.of(
                new String[] {"GET", "/api/undeclared"},
                new String[] {"POST", "/api/admin/undeclared"},
                new String[] {"POST", "/api/admin/accounts"},
                new String[] {"PUT", "/api/admin/accounts/" + UUID.randomUUID()},
                new String[] {"DELETE", "/api/admin/groups"},
                // Roles and the mapping are configuration: nothing writes one at runtime.
                new String[] {"POST", "/api/admin/roles"},
                new String[] {"PUT", "/api/admin/roles"},
                new String[] {"PATCH", "/api/admin/roles"},
                new String[] {"DELETE", "/api/admin/roles"},
                new String[] {"PUT", "/api/admin/roles/Superuser"},
                new String[] {"DELETE", "/api/admin/roles/Superuser"},
                new String[] {"PATCH", "/api/count"},
                new String[] {"GET", "/api/admin"})) {
            MockHttpServletRequestBuilder request =
                    request(HttpMethod.valueOf(undeclared[0]), undeclared[1])
                            .session(session(EVERY_PERMISSION));
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
    void unlock_and_forced_password_change_on_the_callers_own_account_are_refused()
            throws Exception {
        for (String action : List.of("unlock", "force-password-change")) {
            MockHttpServletRequestBuilder request = request(HttpMethod.POST,
                    "/api/admin/accounts/" + callerId + "/" + action)
                    .session(session(EVERY_PERMISSION));
            SessionCsrf.withCsrf(mvc, request);
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .as("%s on the caller's own account, holding every Permission", action)
                    .isEqualTo(403);
        }
    }

    // ---- the handler's own declaration ------------------------------------------------------

    /**
     * Past the chain: each documented operation's handler, invoked through its Spring proxy with
     * no filter in front of it, refuses a caller lacking the declared Permission and admits one
     * holding only it. This is what makes method security the authority and the URL rule the
     * backstop — remove a handler's declaration and the chain alone would still pass the tests
     * above. Arguments are placeholders: an admitted call may fail on them, a refused one never
     * reaches them. Actuator endpoints have no controller handler; the chain is their declaration.
     */
    @Test
    void each_handler_holds_its_declared_permission_itself() throws Exception {
        List<Operation> handled = withAccess(Access.PERMISSION).stream()
                .filter(operation -> operation.template().startsWith("/api/"))
                .toList();
        assertThat(handled).hasSizeGreaterThan(10);
        List<String> failures = new ArrayList<>();
        for (Operation operation : handled) {
            HandlerMethod handler = handlerOf(operation);
            Object bean = handler.getBean() instanceof String name
                    ? context.getBean(name) : handler.getBean();
            Method method = handler.getMethod();
            String permission = operation.requirement().permission();
            List<String> allBut = new ArrayList<>(EVERY_PERMISSION);
            allBut.remove(permission);

            if (!(invoke(bean, method, allBut) instanceof AccessDeniedException)) {
                failures.add(operation + " handler admitted a caller without " + permission);
            }
            if (invoke(bean, method, List.of(permission)) instanceof AccessDeniedException) {
                failures.add(operation + " handler refused a caller holding " + permission);
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
                scimConnector = connectors.create("authorization-contract-scim", ADMIN).id();
            }
            if (!wanted.isEmpty()) {
                return connectors.issueToken(
                        scimConnector, wanted, null, ADMIN, TokenPermissions.ALL).presentedValue();
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
