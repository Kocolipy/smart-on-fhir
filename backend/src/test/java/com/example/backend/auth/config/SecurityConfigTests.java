package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@Import(com.example.backend.ContainerTestConfiguration.class)
// Every assertion here reads immutable configuration beans (the filter chain and
// the CSRF / security-context repositories) and builds its own local MockMvc per
// method, so nothing here mutates the application context. The context is
// therefore shared with the other @SpringBootTest classes via the context cache
// rather than rebuilt per method — booting it once instead of eleven times.
class SecurityConfigTests {

    /**
     * The application chain by name. There are two {@link SecurityFilterChain}
     * beans in the context — the SCIM chain is ordered ahead of this one — so the
     * type alone names no bean. Which of the two comes first is
     * {@code ScimSecurityChainOrderTests}' subject; this class is about what the
     * application chain itself builds, so it asks for that chain explicitly rather
     * than for whichever one the container happens to hand over.
     */
    @Autowired
    @Qualifier("securityFilterChain")
    private SecurityFilterChain securityFilterChain;

    @Autowired
    private SecurityContextRepository securityContextRepository;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private CsrfTokenRepository csrfTokenRepository;

    private MockMvc mvc;

    /**
     * Drives requests through the configured chain alone. The probe controller
     * only exists to give the frontend path a handler, so the assertions can look
     * at what the chain adds to a normal response.
     */
    @BeforeEach
    void setUp() {
        FilterChainProxy proxy = new FilterChainProxy(securityFilterChain);
        proxy.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilter(proxy)
                .build();
    }

    /**
     * The two session filters run once each, inside the application chain, at their positions:
     * the absolute-lifetime check just ahead of the filter that loads the security context from
     * the session, so an expired session is gone before anything reads it, and the logging
     * context just after it, once there is a caller to name. Neither is also registered with the
     * servlet container. A container registration would run it outside the chain, and its
     * once-per-request marker would then skip it inside.
     */
    @Test
    void eachSessionFilterRunsOnceInsideTheChainBesideTheContextLoaderAndNotInTheContainer() {
        Filter lifetime = applicationContext.getBean("absoluteSessionLifetimeFilter", Filter.class);
        Filter logContext = applicationContext.getBean("sessionUserLogContextFilter", Filter.class);
        List<Filter> chain = securityFilterChain.getFilters();
        int loader = IntStream.range(0, chain.size())
                .filter(index -> chain.get(index) instanceof SecurityContextHolderFilter)
                .findFirst().orElseThrow();
        List<Filter> registeredWithTheContainer = new ServletContextInitializerBeans(
                        applicationContext.getBeanFactory()).stream()
                .filter(FilterRegistrationBean.class::isInstance)
                .map(FilterRegistrationBean.class::cast)
                .filter(FilterRegistrationBean::isEnabled)
                .map(registration -> (Filter) registration.getFilter())
                .toList();

        assertThat(List.of(
                        chain.stream().filter(filter -> filter == lifetime).count(),
                        chain.stream().filter(filter -> filter == logContext).count(),
                        (long) chain.indexOf(lifetime) - loader,
                        (long) chain.indexOf(logContext) - loader))
                .containsExactly(1L, 1L, -1L, 1L);
        assertThat(registeredWithTheContainer).doesNotContain(lifetime, logContext);
    }

    /**
     * The login path writes the authentication through the injected
     * {@link SecurityContextRepository} bean; every subsequent request reads it
     * back through whichever repository the filter chain holds. Those must be
     * the same instance, otherwise the two halves of authentication are only
     * coupled by the incidental fact that both happen to touch the HTTP session.
     */
    @Test
    void filterChainReadsTheSameSecurityContextRepositoryThatLoginWritesTo() {
        SecurityContextHolderFilter filter = securityFilterChain.getFilters().stream()
                .filter(SecurityContextHolderFilter.class::isInstance)
                .map(SecurityContextHolderFilter.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no SecurityContextHolderFilter in the chain: "
                                + securityFilterChain.getFilters().stream()
                                        .map(Filter::getClass)
                                        .map(Class::getSimpleName)
                                        .toList()));

        Object chainRepository =
                ReflectionTestUtils.getField(filter, "securityContextRepository");

        assertThat(chainRepository).isSameAs(securityContextRepository);
    }

    /**
     * The token lives in the session, behind the default XOR-masking handler —
     * asserted on the chain's own filter, because {@code csrf.spa()} would swap
     * both for a cookie repository and a raw-token handler while every request
     * {@code CsrfSynchronizerTokenIntegrationTests} sends still passed. The shared bean is the instance the chain holds, so the
     * login path drops the very token the chain would otherwise accept.
     */
    @Test
    void theChainKeepsItsCsrfTokenInTheSessionBehindTheMaskingHandler() {
        CsrfFilter filter = securityFilterChain.getFilters().stream()
                .filter(CsrfFilter.class::isInstance)
                .map(CsrfFilter.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CsrfFilter in the chain"));

        assertThat(csrfTokenRepository).isInstanceOf(HttpSessionCsrfTokenRepository.class);
        assertThat(ReflectionTestUtils.getField(filter, "tokenRepository"))
                .isSameAs(csrfTokenRepository);
        assertThat(ReflectionTestUtils.getField(filter, "requestHandler"))
                .isExactlyInstanceOf(XorCsrfTokenRequestAttributeHandler.class);
    }

    /**
     * A page view sets no cookie and opens no session: the CSRF token is created only when
     * the SPA asks {@code GET /api/auth/csrf} for one.
     */
    @Test
    void anonymousFrontendRequestSetsNoCookieAndOpensNoSession() throws Exception {
        MvcResult page = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(page.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(page.getRequest().getSession(false)).isNull();
    }

    /**
     * The frontend allowance is a GET-only, non-API rule, and each half of that is load-bearing:
     * an unsafe request to a frontend path (carrying its session's token, so CSRF is not what
     * refuses it) is not admitted, and an unlisted API path is not a frontend path, so a GET of
     * it still needs a session.
     */
    @Test
    void onlyAGetOfANonApiPathIsAdmittedAsAFrontendRequest() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String[] token = mvc.perform(get(ProbeController.TOKEN).session(session))
                .andReturn().getResponse().getContentAsString().split(":", 2);

        mvc.perform(post("/").session(session).header(token[0], token[1]))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/self"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The path is read relative to the context path, so a deployment under one classifies
     * {@code /app/api/...} as the API path it is rather than as a frontend route.
     */
    @Test
    void theFrontendAllowanceReadsThePathBeneathTheContextPath() throws Exception {
        mvc.perform(get("/app/api/self").contextPath("/app"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The frontend allowance is a deny-list over everything that is not an API or
     * actuator path, so the actuator base path itself has to stay behind
     * authentication rather than fall through to it.
     */
    @Test
    void actuatorBasePathIsNotTreatedAsAFrontendRoute() throws Exception {
        mvc.perform(get("/actuator"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Whether a path is reserved for the server is decided inside the context path: deployed
     * under {@code /app}, {@code /app/api/undeclared} is still an API path and stays behind
     * authentication, while {@code /app/} is still the frontend and stays public. Judged on the
     * raw URI instead, the first would read as a frontend route and be let through. An API path
     * that no earlier rule names is used on purpose, so the frontend allowance is what decides
     * it rather than a matcher ahead of it.
     */
    @Test
    void theFrontendAllowanceIsJudgedInsideTheContextPath() throws Exception {
        mvc.perform(get("/app/api/undeclared").contextPath("/app"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/app/").contextPath("/app"))
                .andExpect(status().isOk());
    }

    /**
     * Only a GET of a frontend path skips authentication. An unsafe request to the same path —
     * past CSRF, so the chain's authorization decision is what answers — is refused with a 401
     * rather than reaching a handler.
     */
    @Test
    void aFrontendPathIsPublicForGetOnly() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String[] token = mvc.perform(get(ProbeController.TOKEN).session(session))
                .andReturn().getResponse().getContentAsString().split(":", 2);

        mvc.perform(post("/").session(session).header(token[0], token[1]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void responsesCarryTheContentSecurityPolicy() throws Exception {
        mvc.perform(get("/"))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("object-src 'none'")));
    }

    /**
     * The SPA may connect to its own origin and to Epic's local FHIRcast hub on loopback, over the
     * four schemes the hub may use, on any port (ADR 0016) — and to no Epic origin or other host.
     */
    @Test
    void connectSrcAdmitsOnlyTheOriginAndTheLoopbackHub() throws Exception {
        String policy = mvc.perform(get("/")).andReturn().getResponse()
                .getHeader("Content-Security-Policy");

        assertThat(java.util.Arrays.stream(policy.split("; "))
                        .filter(directive -> directive.startsWith("connect-src "))
                        .toList())
                .containsExactly("connect-src 'self' http://127.0.0.1:* https://127.0.0.1:*"
                        + " ws://127.0.0.1:* wss://127.0.0.1:*");
    }

    /** Every other directive is as strict as before: the loopback hub is connect-src's alone. */
    @Test
    void noOtherDirectiveAdmitsAnOtherOrigin() throws Exception {
        String policy = mvc.perform(get("/")).andReturn().getResponse()
                .getHeader("Content-Security-Policy");

        assertThat(java.util.Arrays.stream(policy.split("; "))
                        .filter(directive -> !directive.startsWith("connect-src "))
                        .toList())
                .containsExactly("default-src 'self'", "script-src 'self'",
                        "style-src 'self' 'unsafe-inline'", "img-src 'self' data:",
                        "font-src 'self'", "object-src 'none'", "base-uri 'self'",
                        "form-action 'self'", "frame-ancestors 'none'");
    }

    @Test
    void responsesCarryTheReferrerPolicy() throws Exception {
        mvc.perform(get("/"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void responsesCarryThePermissionsPolicy() throws Exception {
        mvc.perform(get("/"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")));
    }

    /**
     * Spring Security sets these two by default. They are asserted so that a
     * later {@code defaultsDisabled()} or a hand-rolled header block cannot drop
     * them unnoticed.
     */
    @Test
    void responsesKeepTheDefaultFramingAndSniffingHeaders() throws Exception {
        mvc.perform(get("/"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    /**
     * Gives the frontend path and the administration endpoint a handler, so the
     * chain's own answer is what each assertion observes, and exposes the masked
     * token the chain publishes for the session as {@code header:token}.
     */
    @RestController
    static class ProbeController {

        static final String TOKEN = "/probe/csrf-token";

        @GetMapping({"/", "/api/admin/accounts"})
        String index() {
            return "index";
        }

        @GetMapping(TOKEN)
        String token(HttpServletRequest request) {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            return token.getHeaderName() + ":" + token.getToken();
        }
    }
}
