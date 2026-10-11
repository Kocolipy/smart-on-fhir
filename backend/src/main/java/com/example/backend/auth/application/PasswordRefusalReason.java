package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditRefusalReason;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;

/**
 * Why a password Login was refused: the closed list a password {@code LOGIN_FAILURE} carries
 * exactly one of, and the {@code reason} tag of the {@code login} counter's password series — the
 * password analogue of {@code EpicLoginFailureReason}.
 *
 * <p>Each reason owns every fact about itself, so a reason added here needs no other edit:
 *
 * <ul>
 *   <li>the Spring Security refusal it is reported for ({@link #reportedFor}) — or none, for
 *       {@link #UNKNOWN_ACCOUNT}, which Spring Security hides behind a wrong password so the two
 *       are indistinguishable to the caller;
 *   <li>whether recording the refusal settles it as {@link #UNKNOWN_ACCOUNT} instead, when the
 *       submitted name matched no User ({@link #settled});
 *   <li>the {@link AuditRefusalReason} its {@code LOGIN_FAILURE} is recorded under, always the one
 *       of the same name ({@link #audited});
 *   <li>the {@code login} counter series it is counted under, which exists from the start, at
 *       zero, for every reason here.
 * </ul>
 *
 * <p>Translated here, at the one place a Spring Security {@code AuthenticationException} is
 * mapped, so no other layer has to know the library's exception hierarchy and no exception object
 * — whose message may name the submitted username — travels further than the login decision.
 *
 * <p>The reasons are audit-only, as Epic Login's are: the caller is answered the same bare
 * {@code 401} for every one, and the refusal's {@code WARN} names none (Logging §2.2).
 */
public enum PasswordRefusalReason {

    /** The account exists and the submitted password did not match its hash. */
    BAD_CREDENTIALS(AuditRefusalReason.BAD_CREDENTIALS, BadCredentialsException.class),

    /** No account carries the submitted name: settled by the lookup, never reported. */
    UNKNOWN_ACCOUNT(AuditRefusalReason.UNKNOWN_ACCOUNT, null),

    /** A lockout was in force, so the Login was refused whatever the password. */
    ACCOUNT_LOCKED(AuditRefusalReason.ACCOUNT_LOCKED, LockedException.class),

    /** The account is deactivated. */
    ACCOUNT_DISABLED(AuditRefusalReason.ACCOUNT_DISABLED, DisabledException.class),

    /**
     * Any refusal no reason above names. Last, because it is reported for every
     * {@code AuthenticationException}, and {@link #reportedFor} takes the first reason that
     * matches.
     */
    OTHER(AuditRefusalReason.OTHER, AuthenticationException.class);

    private final AuditRefusalReason audited;

    private final Class<? extends AuthenticationException> reportedAs;

    PasswordRefusalReason(AuditRefusalReason audited,
            Class<? extends AuthenticationException> reportedAs) {
        this.audited = Objects.requireNonNull(audited, "audited");
        this.reportedAs = reportedAs;
    }

    /** The reason Spring Security's {@code refused} is reported under: the first that names it. */
    public static PasswordRefusalReason reportedFor(AuthenticationException refused) {
        return Arrays.stream(values())
                .filter(reason -> reason.reported() && reason.reportedAs.isInstance(refused))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The reason the refusal is recorded under once its name has been looked up: this one, or
     * {@link #UNKNOWN_ACCOUNT} when the name matched no User. Whether the name named an account is
     * settled by looking, not guessed from an exception type the library deliberately makes
     * ambiguous.
     */
    public PasswordRefusalReason settled(boolean accountFound) {
        return accountFound ? this : UNKNOWN_ACCOUNT;
    }

    /** Whether Spring Security reports a refusal under this reason; only the settlement is not. */
    public boolean reported() {
        return reportedAs != null;
    }

    /** The reason this refusal's {@code LOGIN_FAILURE} is recorded under in the audit trail. */
    public AuditRefusalReason audited() {
        return audited;
    }
}
