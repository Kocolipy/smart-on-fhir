package com.example.backend.auth.epic;

import java.io.Serial;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * Epic's verified identity within the one callback request: the validated {@code id_token}, with
 * no user-info call and no authorities of Epic's, plus the token response's {@link LaunchContext}.
 *
 * <p>Spring Security's login filter hands the token response's extra members to the user service
 * alone, and the success handler only the principal it returned, so this is how the launch
 * context reaches the success handler — on the request's own {@link Authentication}, which the
 * filter saves nowhere (its security-context repository saves nothing, and the session it sees is
 * the pre-login one). The launch context is {@code transient}: should this ever be serialized, it
 * is not carried along, so it cannot reach the session store by that path either (ADR 0012, D8).
 */
public final class EpicIdentity extends DefaultOidcUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient LaunchContext launchContext;

    public EpicIdentity(OidcIdToken idToken, LaunchContext launchContext) {
        super(List.of(), idToken);
        this.launchContext = launchContext;
    }

    /**
     * The launch context {@code epic}'s token response named, or {@linkplain LaunchContext#none
     * none} when {@code epic} is not an Epic identity.
     */
    public static LaunchContext launchContextOf(Authentication epic) {
        return epic.getPrincipal() instanceof EpicIdentity identity
                && identity.launchContext != null
                ? identity.launchContext : LaunchContext.none();
    }
}
