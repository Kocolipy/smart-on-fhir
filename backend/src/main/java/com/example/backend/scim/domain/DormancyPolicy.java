package com.example.backend.scim.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * How long a User may go without authenticating before each step of the dormancy job applies to
 * it (ADR 0007).
 *
 * <p>Two windows, both measured on the same basis — {@link #basis}, the last successful login,
 * completed password change, explicit reactivation or Unlock, falling back to creation — and
 * folded into one {@link DormancyVerdict} by {@link #verdict}:
 *
 * <ul>
 *   <li><strong>lockout</strong> — past it, the User is locked with {@link LockCause#DORMANCY}
 *       until an administrator's Unlock;
 *   <li><strong>role revocation</strong> — past it, the User's direct membership of every mapped
 *       Group is removed, so it holds no Role even if it is later unlocked.
 * </ul>
 *
 * <p>Here, beside the state it governs, for the reason {@link LockoutPolicy} is: the rule reads
 * the User, and the dependency between the slices runs from the login surface to the directory.
 * The defaults are the domain's, not a config file's, so a deployment that configures nothing
 * and a test that constructs the policy directly agree on what "90 days" means — the pattern
 * {@code AuditRetentionPolicy} set.
 *
 * <p>The windows are ordered: role revocation is the later step. A role-revocation window not
 * longer than the lockout window would strip the Roles of a User the same run locks — or before
 * it is locked at all — which is a configuration mistake rather than a stricter policy, so it
 * stops startup like a non-positive window does.
 *
 * @param lockoutWindow        how long without authenticating before the dormancy lockout
 * @param roleRevocationWindow how long without authenticating before every mapped Group
 *                             membership is removed
 */
public record DormancyPolicy(Duration lockoutWindow, Duration roleRevocationWindow) {

    /** Ninety days, applied when a deployment configures nothing. */
    public static final Duration DEFAULT_LOCKOUT_WINDOW = Duration.ofDays(90);

    /** One hundred and eighty days, applied when a deployment configures nothing. */
    public static final Duration DEFAULT_ROLE_REVOCATION_WINDOW = Duration.ofDays(180);

    public DormancyPolicy {
        lockoutWindow = lockoutWindow == null
                ? DEFAULT_LOCKOUT_WINDOW
                : requirePositive(lockoutWindow, "lockout");
        roleRevocationWindow = roleRevocationWindow == null
                ? DEFAULT_ROLE_REVOCATION_WINDOW
                : requirePositive(roleRevocationWindow, "role revocation");
        if (roleRevocationWindow.compareTo(lockoutWindow) <= 0) {
            throw new IllegalArgumentException(
                    "The dormancy role revocation window is configured at " + roleRevocationWindow
                            + " and the lockout window at " + lockoutWindow
                            + "; the role revocation window must be longer than the lockout"
                            + " window.");
        }
    }

    /** The policy a deployment that configures nothing runs with. */
    public static DormancyPolicy defaults() {
        return new DormancyPolicy(null, null);
    }

    /**
     * The dormancy verdict for this User at {@code now}: {@link DormancyVerdict#NOT_DUE} for the
     * Bootstrap Admin whatever its basis, and otherwise {@link #verdict(Instant, Instant, Instant)}
     * on the User's creation and last authentication.
     */
    public DormancyVerdict verdict(ScimUser user, Instant now) {
        if (user.isExemptFromDormancy()) {
            return DormancyVerdict.NOT_DUE;
        }
        return verdict(user.createdAt(), user.login().lastAuthenticatedAt(), now);
    }

    /**
     * The dormancy verdict at {@code now} for a User created at {@code createdAt} that last
     * authenticated — logged in, changed its password, was reactivated or was unlocked — at
     * {@code lastAuthenticatedAt}, or never ({@code null}).
     *
     * <p>The basis is {@link #basis}; a window applies once the basis lies strictly before its
     * cutoff, so a basis exactly one window old is not yet due for that step.
     */
    public DormancyVerdict verdict(Instant createdAt, Instant lastAuthenticatedAt, Instant now) {
        Instant basis = basis(createdAt, lastAuthenticatedAt);
        if (basis.isBefore(roleRevocationCutoff(now))) {
            return DormancyVerdict.LOCKOUT_AND_ROLE_REVOCATION;
        }
        if (basis.isBefore(lockoutCutoff(now))) {
            return DormancyVerdict.LOCKOUT;
        }
        return DormancyVerdict.NOT_DUE;
    }

    /**
     * The instant dormancy is measured from: the last authentication — a successful login, a
     * completed password change, a reactivation or an Unlock, all of which write it — or, for a
     * User that has had none of those, its creation.
     *
     * <p>The fallback is what keeps a User provisioned without a password from being dormant the
     * moment it exists: it has never authenticated, but it has also not had the chance to. The
     * candidate queries spell the same choice in SQL as
     * {@code coalesce(last_authenticated_at, created_at)}.
     */
    public static Instant basis(Instant createdAt, Instant lastAuthenticatedAt) {
        return lastAuthenticatedAt == null ? createdAt : lastAuthenticatedAt;
    }

    /**
     * The instant a User's dormancy basis must lie before for the lockout to apply at {@code now}.
     * The decision is {@link #verdict}'s; this is exposed for the candidate query, which narrows
     * the Users the job reads before it decides each one again on the locked read.
     */
    public Instant lockoutCutoff(Instant now) {
        return now.minus(lockoutWindow);
    }

    /**
     * The instant a User's dormancy basis must lie before for its mapped Group memberships to be
     * removed at {@code now}. Exposed, like {@link #lockoutCutoff}, for the candidate query only.
     */
    public Instant roleRevocationCutoff(Instant now) {
        return now.minus(roleRevocationWindow);
    }

    /**
     * Refuses a zero or negative window. Either would make every User dormant the moment the job
     * ran — a deployment-wide lockout from a typo — so it stops startup instead, with the value
     * in the message.
     */
    private static Duration requirePositive(Duration window, String step) {
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "The dormancy " + step + " window is configured at " + window
                            + "; it must be positive, or every User would be dormant at once.");
        }
        return window;
    }
}
