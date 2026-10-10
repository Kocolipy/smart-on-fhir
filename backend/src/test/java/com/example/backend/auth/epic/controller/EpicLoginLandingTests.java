package com.example.backend.auth.epic.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.application.LoginOutcome;
import com.example.backend.auth.application.LoginOutcome.EpicRefused;
import com.example.backend.auth.application.LoginOutcome.FailedCall;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.application.LoginOutcome.Unavailable;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogEvent.ErrorCategory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link EpicLoginLanding} lands only an Epic Login: a password Login's outcome type-checks, being
 * one of the same {@link LoginOutcome}s, but its landing is {@code AuthController}'s bare
 * {@code 401}, so handing it here is a caller's bug, refused rather than redirected.
 */
class EpicLoginLandingTests {

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void anEpicSignInLandsAtTheRoot() throws Exception {
        EpicLoginLanding.after(SignedIn.epic(UUID.randomUUID(), AuditMfaFactor.OTP), request, response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void anEpicRefusalLandsAtTheRefusedPage() throws Exception {
        EpicLoginLanding.after(
                EpicRefused.because(EpicLoginFailureReason.INVALID_STATE), request, response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/?signin=refused");
    }

    @Test
    void anUnavailableEpicLandsAtTheUnavailablePage() throws Exception {
        EpicLoginLanding.after(
                new Unavailable(new FailedCall("token", ErrorCategory.SERVER, 502)),
                request, response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/?signin=unavailable");
    }

    @Test
    void noOutcomeIsNoLoginToLand() {
        assertThatThrownBy(() -> EpicLoginLanding.after(null, request, response))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPasswordSignInIsNoEpicLoginToLand() {
        assertThatThrownBy(() -> EpicLoginLanding.after(
                        SignedIn.password(UUID.randomUUID()), request, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not an Epic Login's outcome: a password Login lands as a 401");
    }

    @Test
    void aPasswordRefusalIsNoEpicLoginToLand() {
        assertThatThrownBy(() -> EpicLoginLanding.after(
                        new PasswordRefused("ada", AuditRefusalReason.BAD_CREDENTIALS),
                        request, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not an Epic Login's outcome: a password Login lands as a 401");
    }
}
