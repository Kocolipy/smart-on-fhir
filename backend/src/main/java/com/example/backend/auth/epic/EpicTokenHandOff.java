package com.example.backend.auth.epic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * How Epic's token response gets from Spring Security's login filter to the success handler, and
 * no further, within the one callback request (ADR 0013, D29).
 *
 * <p>The login filter redeems the code and then saves the authorized client — the access token and
 * any refresh token — through its {@link OAuth2AuthorizedClientRepository}, before the success
 * handler runs. {@link #REPOSITORY} holds that client on the request alone: not in the session,
 * because the session the filter sees is the pre-login one, and the tokens must be kept only under
 * the signed-in (rotated) id, and only if the login decision accepts the User. The success handler
 * then {@linkplain #take takes} it, once the session is signed in, and keeps what it needs of it on
 * that session. A refused Login never takes it, and it ends with the request.
 *
 * <p>Every value here is a D22 one: nothing in this class logs, and nothing it throws carries one.
 */
public final class EpicTokenHandOff {

    /** The request attribute the authorized client is held under until it is taken. */
    private static final String ATTRIBUTE = EpicTokenHandOff.class.getName() + ".authorizedClient";

    /**
     * The login filter's authorized-client repository: holds what it is given on the request, for
     * {@link #take} alone, and loads nothing — the kept tokens are reached through the
     * {@code EpicTokens} port.
     */
    public static final OAuth2AuthorizedClientRepository REPOSITORY =
            new OAuth2AuthorizedClientRepository() {
                @Override
                public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                        String clientRegistrationId, Authentication principal,
                        HttpServletRequest request) {
                    return null;
                }

                @Override
                public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                        Authentication principal, HttpServletRequest request,
                        HttpServletResponse response) {
                    request.setAttribute(ATTRIBUTE, authorizedClient);
                }

                @Override
                public void removeAuthorizedClient(String clientRegistrationId,
                        Authentication principal, HttpServletRequest request,
                        HttpServletResponse response) {
                    request.removeAttribute(ATTRIBUTE);
                }
            };

    private EpicTokenHandOff() {
    }

    /**
     * The authorized client the login filter saved on {@code request}, taken off it, so the token
     * response does not stay on the request once it is handed over.
     *
     * @return the client; empty when the filter saved none on this request
     */
    public static Optional<OAuth2AuthorizedClient> take(HttpServletRequest request) {
        Object saved = request.getAttribute(ATTRIBUTE);
        request.removeAttribute(ATTRIBUTE);
        return saved instanceof OAuth2AuthorizedClient client ? Optional.of(client) : Optional.empty();
    }
}
