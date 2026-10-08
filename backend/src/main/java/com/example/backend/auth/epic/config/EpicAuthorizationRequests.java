package com.example.backend.auth.epic.config;

import com.example.backend.auth.domain.PendingAuthorizations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where an Epic Login's pending authorization request — {@code state}, the nonce and the PKCE
 * verifier — waits between the authorize hop and the callback (flow step 2, D27): in
 * {@link PendingAuthorizations}, keyed by the browser's session, so it is session-scoped and
 * shared by every node, and taken from there <em>once</em>, atomically.
 *
 * <p>The authorize hop saves it here through Spring Security's authorization-request filter. The
 * callback takes it before anything else happens ({@link EpicCallbackFilter}), validated or not,
 * so a replayed or concurrent callback finds none; what was taken is then handed to Spring
 * Security's login filter, for this request only, as the one it "removes".
 *
 * <p>A request with no session holds none: the launch is what made the session.
 */
final class EpicAuthorizationRequests
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    /** The request attribute the callback's taken request is handed on under. */
    static final String TAKEN = EpicAuthorizationRequests.class.getName() + ".TAKEN";

    /** How long a pending request waits when its session reports no idle bound. */
    static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(15);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PendingAuthorizations pending;

    EpicAuthorizationRequests(PendingAuthorizations pending) {
        this.pending = pending;
    }

    /**
     * Takes the request the browser's session holds, removing it in the same step, or empty when
     * the session holds none or there is no session.
     */
    Optional<OAuth2AuthorizationRequest> take(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        return pending.take(session.getId()).map(EpicAuthorizationRequests::fromJson);
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return request.getAttribute(TAKEN) instanceof OAuth2AuthorizationRequest taken
                ? taken : null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            take(request);
            return;
        }
        HttpSession session = request.getSession();
        pending.hold(session.getId(), toJson(authorizationRequest), lifetimeOf(session));
    }

    /** The request this callback took, which no later request can have. */
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest taken = loadAuthorizationRequest(request);
        request.removeAttribute(TAKEN);
        return taken;
    }

    /** As long as the session it belongs to may stay idle: it is no use past that. */
    private static Duration lifetimeOf(HttpSession session) {
        int idle = session.getMaxInactiveInterval();
        return idle > 0 ? Duration.ofSeconds(idle) : DEFAULT_LIFETIME;
    }

    /**
     * The request as JSON: every member is a string, a list of strings or a map of them, so it
     * round-trips through the builder with no Java serialization.
     */
    private static String toJson(OAuth2AuthorizationRequest request) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("authorizationUri", request.getAuthorizationUri());
        members.put("clientId", request.getClientId());
        members.put("redirectUri", request.getRedirectUri());
        members.put("scopes", new ArrayList<>(request.getScopes()));
        members.put("state", request.getState());
        members.put("additionalParameters", request.getAdditionalParameters());
        members.put("attributes", request.getAttributes());
        members.put("authorizationRequestUri", request.getAuthorizationRequestUri());
        return JSON.writeValueAsString(members);
    }

    @SuppressWarnings("unchecked")
    private static OAuth2AuthorizationRequest fromJson(String json) {
        Map<String, Object> members = JSON.readValue(json, new TypeReference<>() { });
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri((String) members.get("authorizationUri"))
                .clientId((String) members.get("clientId"))
                .redirectUri((String) members.get("redirectUri"))
                .scopes(new LinkedHashSet<>((List<String>) members.get("scopes")))
                .state((String) members.get("state"))
                .additionalParameters((Map<String, Object>) members.get("additionalParameters"))
                .attributes((Map<String, Object>) members.get("attributes"))
                .authorizationRequestUri((String) members.get("authorizationRequestUri"))
                .build();
    }
}
