package com.example.backend.auth.epic.config;

import com.example.backend.auth.application.EpicSignInRefusedException;
import com.example.backend.auth.domain.EpicInputBounds;
import com.example.backend.auth.domain.EpicInputField;
import com.example.backend.auth.domain.EpicInputRule;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.auth.epic.EpicRoutes;
import com.example.backend.auth.epic.EpicSignInFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The first thing an Epic callback meets (flow step 3, D18, D27), ahead of Spring Security's
 * login filter.
 *
 * <ol>
 *   <li>The pending authorization request is <b>taken</b> — removed from the store atomically —
 *       before anything about the callback is looked at, so whatever follows, it cannot be used
 *       again: a replayed callback, or the slower of two concurrent ones, finds none.
 *   <li>With none, or a {@code state} that is not the pending one, compared in constant time, the
 *       callback is refused as {@code INVALID_STATE}.
 *   <li>An OAuth {@code error} from Epic is refused as {@code IDP_ERROR}.
 *   <li>A {@code code} outside D18's bounds is refused as {@code INVALID_CODE}.
 * </ol>
 *
 * <p>Each refusal goes to the one {@link EpicSignInFailure}, and Epic is never called. A callback
 * that passes goes on to the login filter with the request it took, which redeems the code once.
 */
final class EpicCallbackFilter extends OncePerRequestFilter {

    private static final RequestMatcher CALLBACK =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, EpicRoutes.CALLBACK);

    private final EpicAuthorizationRequests authorizationRequests;

    private final EpicSignInFailure signInFailure;

    EpicCallbackFilter(
            EpicAuthorizationRequests authorizationRequests, EpicSignInFailure signInFailure) {
        this.authorizationRequests = authorizationRequests;
        this.signInFailure = signInFailure;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CALLBACK.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Optional<OAuth2AuthorizationRequest> pending = authorizationRequests.take(request);
        if (pending.isEmpty()
                || !ConstantTime.equals(request.getParameter("state"), pending.get().getState())) {
            refuse(new EpicSignInRefusedException(EpicLoginFailureReason.INVALID_STATE),
                    request, response);
            return;
        }
        if (request.getParameter("error") != null) {
            refuse(new EpicSignInRefusedException(EpicLoginFailureReason.IDP_ERROR),
                    request, response);
            return;
        }
        Optional<EpicInputRule> codeBroken =
                EpicInputBounds.brokenBy(request.getParameter(EpicInputField.CODE.value()));
        if (codeBroken.isPresent()) {
            refuse(new EpicSignInRefusedException(
                    EpicLoginFailureReason.INVALID_CODE, EpicInputField.CODE, codeBroken.get()),
                    request, response);
            return;
        }
        request.setAttribute(EpicAuthorizationRequests.TAKEN, pending.get());
        // The nonce sent, for this request's id_token check only, and gone when it ends.
        Object nonce = pending.get().getAdditionalParameters().get(EpicIdTokenChecks.NONCE);
        try {
            ScopedValue.where(EpicIdTokenChecks.PENDING_NONCE,
                    nonce instanceof String sent ? sent : "").call(() -> {
                        try {
                            chain.doFilter(request, response);
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                        return null;
                    });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    private void refuse(EpicSignInRefusedException refused, HttpServletRequest request,
            HttpServletResponse response) throws IOException, ServletException {
        signInFailure.onAuthenticationFailure(request, response, refused);
    }
}
