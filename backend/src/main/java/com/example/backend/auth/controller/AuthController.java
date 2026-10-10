package com.example.backend.auth.controller;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.application.CurrentPasswordRejectedException;
import com.example.backend.auth.application.LoginIdentityService;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.application.PasswordChangeService;
import com.example.backend.auth.application.PasswordPolicyViolationException;
import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * The session attribute a login records the role mapping's hash under: the mapping the
     * session's Permissions were resolved under. Defined by the port that revokes on it, so the
     * writer and the reader cannot disagree about the name.
     */
    public static final String ROLE_MAPPING_HASH_ATTRIBUTE = RoleMappingSessions.HASH_ATTRIBUTE;

    /** The response header a logout asks the browser to clear the origin's data with. */
    static final String CLEAR_SITE_DATA_HEADER = "Clear-Site-Data";

    /** Every data type a signed-out session may have left in the browser. */
    static final String CLEAR_SITE_DATA_ON_LOGOUT = "\"cache\",\"cookies\",\"storage\"";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final LoginService login;
    private final PasswordChangeService passwordChanges;
    private final AuditTrail audit;
    private final LoginCompletion loginCompletion;
    private final CookieSerializer cookieSerializer;

    public AuthController(
            LoginService login,
            PasswordChangeService passwordChanges,
            AuditTrail audit,
            LoginCompletion loginCompletion,
            CookieSerializer cookieSerializer) {
        this.login = login;
        this.passwordChanges = passwordChanges;
        this.audit = audit;
        this.loginCompletion = loginCompletion;
        this.cookieSerializer = cookieSerializer;
    }

    /**
     * Turns submitted credentials into a session. What counts as a successful
     * login — including the failure run a refusal lengthens, and every record a
     * refusal is — is {@link LoginService}'s; signing the session in, recording the
     * ending, and ending the browser's session on a refusal are
     * {@link LoginCompletion}'s, the steps every Login ends in. This adapter only
     * shapes the answer: the signed-in account, or the bare {@code 401}.
     *
     * <p>A refused Login has ended whatever session the browser held, whoever it
     * belonged to, before its {@code 401}: the password analogue of an Epic Login's
     * refusal (ADR 0013, D24). The CSRF token ends with that session, and the SPA
     * fetches the next one's.
     */
    @PostMapping("/login")
    public UserResponse login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        LoginCompletion.SignedInSession signedIn = loginCompletion.complete(
                        retained -> login.logIn(body.username(), body.password(), retained),
                        request, response)
                .orElseThrow(LoginRefusedException::new);
        return userResponse(signedIn.authentication(), signedIn.session());
    }

    /**
     * The CSRF token bound to the caller's session, in the body and never in a
     * cookie: the SPA keeps it in memory and echoes it in the header named here.
     *
     * <p>Public, so a guest can obtain the token its login submission needs; the
     * call creates the session the token belongs to when there is none yet. The
     * value is XOR-masked afresh on every call, so two responses never repeat each
     * other, and {@code no-store} keeps it out of every cache between here and the
     * page that asked.
     */
    @GetMapping("/csrf")
    public ResponseEntity<CsrfTokenResponse> csrfToken(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(token.getHeaderName(), token.getToken()));
    }

    /**
     * The signed-in account, plus the idle bound its session is held to. Reading the session also
     * renews that bound, so the SPA's "stay signed in" is this request.
     */
    @GetMapping("/me")
    public UserResponse currentUser(Authentication authentication, HttpSession session) {
        return userResponse(authentication, session);
    }

    @DeleteMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            // Recorded before the session is invalidated, and from the session
            // itself: the principal index holds the account's stable id, which is
            // what an event may name, while the security context names it by
            // username, which is what an event may not. Fail-closed, so a logout
            // this service cannot account for leaves the session standing rather
            // than ending it silently.
            recordLogout(session);
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        // Invalidating the session server-side leaves the browser holding a
        // cookie that now names nothing. Expire it so a later request arrives
        // without a session id at all.
        cookieSerializer.writeCookieValue(new CookieValue(request, response, ""));

        // Tells the browser to drop what the signed-out session left behind — cached responses,
        // cookies and storage — whether or not a live session arrived with the request, so a
        // caller whose session already expired is cleaned up the same way.
        response.setHeader(CLEAR_SITE_DATA_HEADER, CLEAR_SITE_DATA_ON_LOGOUT);

        // No CSRF work: the token lived in the session just invalidated, so it is
        // already gone, and the next login fetches one for the session it creates.
    }

    /**
     * Records the logout against the account the session belongs to.
     *
     * <p>A session carrying no principal index is one minted before it was signed
     * in to — there is no account to name, and nothing was logged out — so nothing
     * is recorded rather than an event with an invented subject.
     */
    private void recordLogout(HttpSession session) {
        if (session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME)
                instanceof String indexed) {
            UUID userId = UUID.fromString(indexed);
            audit.recordLogout(userId);
            // Beside the audit append, after it succeeded: the trail is the record of who
            // logged out, and this is the operational stream's line for the same moment.
            try (LogContext.Scope scope = LogContext.userId(userId)) {
                LogEvent.success(log, Operation.LOGOUT, Category.PROCESS, Type.USER, Type.END)
                        .log();
            }
        }
    }

    /**
     * The signed-in account as the SPA is told it: its name, the Permissions its session was
     * issued with, whether it is confined to the password change, and its idle bound.
     *
     * <p>No role is reported. The SPA decides what to show by Permission, as the server decides
     * what to allow, and a confined session holds none, so it reports none.
     *
     * <p>The idle timeout is read off the session itself rather than out of configuration, so the
     * figure the SPA signs out by is the one this session actually expires by and cannot drift
     * from it.
     */
    private UserResponse userResponse(Authentication authentication, HttpSession session) {
        boolean changeRequired = authentication.getAuthorities().stream()
                .anyMatch(authority -> LoginIdentityService.PASSWORD_CHANGE_REQUIRED_AUTHORITY
                        .equals(authority.getAuthority()));
        return new UserResponse(
                authentication.getName(),
                permissionsOf(authentication),
                changeRequired,
                session.getMaxInactiveInterval());
    }

    /**
     * The Permissions the session was issued with, by name and sorted by it: read off the
     * authorities the login resolved, keeping only those that spell a {@link Permission}, so a role
     * or the confinement marker is never reported as one.
     */
    private static List<String> permissionsOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> Permission.fromValue(authority.getAuthority()))
                .flatMap(Optional::stream)
                .sorted(Permission.BY_VALUE)
                .map(Permission::value)
                .toList();
    }

    /**
     * The self-service password change: the one capability besides logging out that a session
     * confined by a required change holds, and open to every authenticated session.
     *
     * <p>The User is the one the SESSION belongs to, read from the stable id its principal index
     * holds — never from the request — so there is no identifier to tamper with. On success every
     * session of that User has been revoked after the commit, this one included; it is also
     * invalidated here, directly, so the servlet container's copy cannot be written back when the
     * request completes, and the cookie is expired so the next request arrives as a stranger.
     */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME)
                        instanceof String userId)) {
            throw new CurrentPasswordRejectedException();
        }
        passwordChanges.changePassword(
                UUID.fromString(userId), body.currentPassword(), body.newPassword());

        session.invalidate();
        SecurityContextHolder.clearContext();
        cookieSerializer.writeCookieValue(new CookieValue(request, response, ""));
    }

    /** Wrong current password, lockout, inactive: the same bare {@code 401} Login gives. */
    @ExceptionHandler(CurrentPasswordRejectedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public void passwordChangeRejected() {
    }

    /** The unmet rule, by name and description; never either submitted value. */
    @ExceptionHandler(PasswordPolicyViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public PasswordRuleViolation passwordPolicyViolated(PasswordPolicyViolationException violation) {
        return new PasswordRuleViolation(violation.ruleName(), violation.getMessage());
    }

    /** A refused Login, its session already ended: the bare {@code 401}, the same for every one. */
    @ExceptionHandler(LoginRefusedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public void loginRefused() {
        // Deliberately omit details so callers cannot distinguish unknown users.
    }

    /**
     * A refused Login, whatever refused it: one type for every refusal — wrong password, unknown
     * name, locked or deactivated account — so the one handler above answers each with the same
     * bare {@code 401}, and nothing about the refusal reaches the answer. It carries no message
     * and no stack trace: it is how a decided refusal leaves the handler, not a fault.
     */
    static final class LoginRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        LoginRefusedException() {
            super(null, null, false, false);
        }
    }

    /**
     * Both fields are bounded before anything reads them — the name by the {@code userName}
     * column's limit, the password by the password policy's — so an over-length body is the same
     * bare {@code 400} as a blank one and never reaches a failure run, the audit trail or the
     * password hash.
     */
    public record LoginRequest(
            @NotBlank @MaxUserNameLength String username,
            @NotBlank @MaxPasswordLength String password) {
    }

    /**
     * Current and new password, each bounded like Login's. {@link #toString()} is overridden
     * because a record's generated one would print both, and a request body is exactly what reaches
     * a log through a debugger or a validation message.
     */
    public record ChangePasswordRequest(
            @NotBlank @MaxPasswordLength String currentPassword,
            @NotBlank @MaxPasswordLength String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[redacted]";
        }
    }

    /**
     * @param headerName the request header an unsafe request carries the token in
     * @param token      the masked token value to send in it
     */
    public record CsrfTokenResponse(String headerName, String token) {
    }

    /** A {@code 400} for a new password breaking a policy rule. */
    public record PasswordRuleViolation(String rule, String message) {
    }

    /**
     * @param permissions            the Permissions the session was issued with, by name, sorted
     *                               by name; empty for a User in no mapped Group and while a
     *                               password change is required
     * @param passwordChangeRequired whether the session is confined to the change flow
     * @param idleTimeoutSeconds     the session's idle bound: how long it survives without a
     *                               request ({@code server.servlet.session.timeout}). The SPA signs
     *                               an inactive user out by this figure, so it never outlasts it
     */
    public record UserResponse(
            String username,
            List<String> permissions,
            boolean passwordChangeRequired,
            int idleTimeoutSeconds) {
    }
}
