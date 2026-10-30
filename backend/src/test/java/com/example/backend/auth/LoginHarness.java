package com.example.backend.auth;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.auth.application.LoginIdentityService;
import com.example.backend.auth.application.LoginOutcomeService;
import com.example.backend.auth.application.LoginService;
import com.example.backend.auth.application.LoginStateService;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.auth.config.SecurityConfig;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.domain.LockoutPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.function.UnaryOperator;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The login decision ({@link LoginService}) assembled once, for every test that drives a Login by
 * hand rather than through a Spring context: the real authentication chain that
 * {@link SecurityConfig} builds, the real state, identity and outcome modules, over the in-memory
 * directory, write locks, session registry and transactions.
 *
 * <p>A test varies only what its claim needs — the clock, the User store (one that compares names
 * as SQL Server does, say), the password encoder, the audit trail, the lockout threshold, or the
 * authentication step itself, wrapped to land a write once the credentials are accepted — and
 * reads every collaborator back through this harness. The window between the authentication's
 * lookup by name and its comparison is the store's own
 * ({@link InMemoryScimUserRepository#afterNextNameLookup}); the one between the comparison and the
 * User's lock is the locks' ({@link InMemoryScimWriteLocks#beforeNextGrant}).
 *
 * <p>Built with {@link #builder()}; {@link #create()} takes every default.
 */
public final class LoginHarness {

    /** The instant the default clock stands at. */
    public static final Instant NOW = Instant.parse("2026-09-24T07:00:00Z");

    /** The failure run that imposes a Lockout unless a test sets another. */
    public static final int DEFAULT_LOCKOUT_THRESHOLD = 5;

    private final SecurityConfig config;
    private final InMemoryScimUserRepository users;
    private final InMemoryScimGroupRepository groups;
    private final InMemoryScimWriteLocks locks = new InMemoryScimWriteLocks();
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final RecordingLoginCounts counts = new RecordingLoginCounts();
    private final RecordingAuditTrail audit;
    private final PasswordEncoder passwordEncoder;
    private final LoginIdentityService identities;
    private final SessionRevocationService revocation;
    private final LoginStateService loginState;
    private final LoginOutcomeService outcomes;
    private final LoginService login;

    private LoginHarness(Builder builder) {
        config = new SecurityConfig();
        users = builder.users != null ? builder.users : new InMemoryScimUserRepository();
        groups = new InMemoryScimGroupRepository(users);
        audit = builder.audit != null ? builder.audit : new RecordingAuditTrail();
        Clock clock = builder.clock != null ? builder.clock : new MutableClock(NOW);
        passwordEncoder = builder.passwordEncoder != null
                ? builder.passwordEncoder : config.passwordEncoder();
        identities = new LoginIdentityService(users, groups, TestGroupPermissions.superuserOnly());
        revocation = new SessionRevocationService(
                sessions, transaction, audit, new RecordingOperationalAlerts());
        loginState = new LoginStateService(users, users, locks, revocation,
                new LockoutPolicy(builder.lockoutThreshold), audit, clock);
        outcomes = new LoginOutcomeService(audit, counts, counts);
        AuthenticationManager real = config.authenticationManager(identities, passwordEncoder);
        login = new LoginService(builder.authentication.apply(real), identities, loginState,
                revocation, audit, outcomes, transactions);
    }

    /** A harness with every default. */
    public static LoginHarness create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The login decision under test. */
    public LoginService login() {
        return login;
    }

    /** The directory's Users, which the authentication reads and the login state is written to. */
    public InMemoryScimUserRepository users() {
        return users;
    }

    /** The directory's Groups, over {@link #users()}. */
    public InMemoryScimGroupRepository groups() {
        return groups;
    }

    /** The per-User write locks the decision is taken under. */
    public InMemoryScimWriteLocks locks() {
        return locks;
    }

    /** The session registry a Login's One session per User sweep ends sessions through. */
    public InMemoryAccountSessions sessions() {
        return sessions;
    }

    /** The transaction the after-commit work — the sweep, a Lockout's revocation — waits on. */
    public PendingCommit transaction() {
        return transaction;
    }

    /** The transactions the decision opened, and how each ended. */
    public RecordingTransactions transactions() {
        return transactions;
    }

    /** Every audit event the Login and the modules beneath it recorded. */
    public RecordingAuditTrail audit() {
        return audit;
    }

    /** Every Login count moved, password and Epic alike. */
    public RecordingLoginCounts counts() {
        return counts;
    }

    /** The encoder the authentication compares with; a test hashes its fixtures' passwords here. */
    public PasswordEncoder passwordEncoder() {
        return passwordEncoder;
    }

    /** The {@link SecurityConfig} the authentication chain was built from. */
    public SecurityConfig config() {
        return config;
    }

    public LoginIdentityService identities() {
        return identities;
    }

    public SessionRevocationService revocation() {
        return revocation;
    }

    public LoginStateService loginState() {
        return loginState;
    }

    public LoginOutcomeService outcomes() {
        return outcomes;
    }

    /** The parts a test may vary; anything left unset takes the harness's default. */
    public static final class Builder {

        private InMemoryScimUserRepository users;
        private RecordingAuditTrail audit;
        private Clock clock;
        private PasswordEncoder passwordEncoder;
        private int lockoutThreshold = DEFAULT_LOCKOUT_THRESHOLD;
        private UnaryOperator<AuthenticationManager> authentication = UnaryOperator.identity();

        private Builder() {
        }

        /** The User store, for a test that configures how it looks names up. */
        public Builder users(InMemoryScimUserRepository users) {
            this.users = users;
            return this;
        }

        public Builder audit(RecordingAuditTrail audit) {
            this.audit = audit;
            return this;
        }

        /** The clock the login state decides by; a {@link MutableClock} at {@link #NOW} unless set. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** The encoder the authentication compares with; the shipped Argon2id one unless set. */
        public Builder passwordEncoder(PasswordEncoder passwordEncoder) {
            this.passwordEncoder = passwordEncoder;
            return this;
        }

        /** The failure run that imposes a Lockout. */
        public Builder lockoutThreshold(int lockoutThreshold) {
            this.lockoutThreshold = lockoutThreshold;
            return this;
        }

        /**
         * Wraps the real authentication step, which {@code wrapper} is handed: for a test that
         * lands a write once the credentials are accepted, or answers in the real one's place.
         */
        public Builder authentication(UnaryOperator<AuthenticationManager> wrapper) {
            this.authentication = wrapper;
            return this;
        }

        public LoginHarness build() {
            return new LoginHarness(this);
        }
    }
}
