package com.example.backend.lifecycle.config;

import com.example.backend.audit.domain.AuditRetentionPolicy;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Severity;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.config.ScimReleaseGate;
import com.example.backend.scim.domain.DormancyPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The application's startup and shutdown records ({@code Log_Schema.md}
 * {@code application-startup} / {@code application-shutdown}).
 *
 * <p><strong>Startup</strong>, on {@link ApplicationReadyEvent} — the service is serving —
 * says which instance this is and how it is configured, so an operator can confirm a
 * deployment from its log without reading its artefacts: {@code host.name} and
 * {@code host.ip}, the active profiles, and the effective value of each non-secret setting
 * that changes what the service does — the Epic Login switch included, with the signing keys'
 * {@code kid}s while it is on, which Epic Login adds itself ({@link EpicLogin#addStartupFields}).
 * Effective, not configured: the windows and periods
 * whose defaults belong to a domain policy are read from that policy, so an unset setting is
 * logged as the default it resolved to rather than as absent. {@code service.*} is not added
 * here; the ECS formatter writes it on every record.
 *
 * <p>The session timeouts and the lockout threshold are deliberately left out: the logging
 * standard forbids logging timeout or retry values for authentication flows (ADR 0003).
 *
 * <p>Nothing that is a credential, a connection string or an identity is read at all — no
 * datasource or Redis setting, no password, no username — so none can reach the record. The
 * settings are the policies' own values, typed as numbers, booleans and durations.
 *
 * <p><strong>Shutdown</strong>, on {@link ContextClosedEvent}, which fires before any bean is
 * destroyed, carries the context's uptime as {@code event.duration_ms}.
 *
 * <p>Both answer only for the context this bean lives in. A child context — the management
 * server's, when it runs on its own port — publishes its own close event to its parent's
 * listeners as well, and without the check the service would log two shutdowns.
 *
 * <p>In {@code lifecycle} rather than {@code observability} because it reads the SCIM, auth
 * and audit slices' settings, and each of those already depends on {@code observability}:
 * the reverse dependency would close a package cycle.
 */
@Component
public class ApplicationLifecycleLog {

    static final String PROFILES = "spring.profiles.active";

    static final String SCIM_ENABLED = "app.scim.enabled";

    static final String DORMANCY_LOCKOUT_WINDOW = "app.dormancy.lockout.window";

    static final String DORMANCY_ROLE_REVOCATION_WINDOW =
            "app.dormancy.role_revocation.window";

    static final String AUDIT_RETENTION_PERIOD = "app.audit.retention.period";

    private static final Logger log = LoggerFactory.getLogger(ApplicationLifecycleLog.class);

    private final ApplicationContext context;
    private final Environment environment;
    private final ScimReleaseGate scimGate;
    private final EpicLogin epicLogin;
    private final DormancyPolicy dormancy;
    private final AuditRetentionPolicy retention;

    public ApplicationLifecycleLog(
            ApplicationContext context,
            Environment environment,
            ScimReleaseGate scimGate,
            EpicLogin epicLogin,
            DormancyPolicy dormancy,
            AuditRetentionPolicy retention) {
        this.context = context;
        this.environment = environment;
        this.scimGate = scimGate;
        this.epicLogin = epicLogin;
        this.dormancy = dormancy;
        this.retention = retention;
    }

    @EventListener
    public void started(ApplicationReadyEvent event) {
        if (event.getApplicationContext() != context) {
            return;
        }
        LoggingEventBuilder record = LogEvent.success(log,
                        Operation.APPLICATION_STARTUP, Category.PROCESS, Type.START)
                .addKeyValue(LogEvent.SEVERITY, Severity.LOW.value());
        record = withHost(record)
                .addKeyValue(PROFILES, List.of(environment.getActiveProfiles()))
                .addKeyValue(SCIM_ENABLED, scimGate.open())
                .addKeyValue(DORMANCY_LOCKOUT_WINDOW,
                        dormancy.lockoutWindow().toString())
                .addKeyValue(DORMANCY_ROLE_REVOCATION_WINDOW,
                        dormancy.roleRevocationWindow().toString())
                .addKeyValue(AUDIT_RETENTION_PERIOD, retention.period().toString());
        epicLogin.addStartupFields(record).log();
    }

    @EventListener
    public void stopping(ContextClosedEvent event) {
        if (event.getApplicationContext() != context) {
            return;
        }
        LogEvent.success(log, Operation.APPLICATION_SHUTDOWN,
                        System.currentTimeMillis() - context.getStartupDate(),
                        Category.PROCESS, Type.END)
                .addKeyValue(LogEvent.SEVERITY, Severity.LOW.value())
                .log();
    }

    /**
     * The machine's name and address, from its own resolver. Left off the record, rather than
     * filled with a placeholder, when the host cannot resolve its own name: an absent field is
     * searchable as absent, while a made-up value would read as an instance that does not exist.
     */
    private static LoggingEventBuilder withHost(LoggingEventBuilder record) {
        return withHost(record, InetAddress::getLocalHost);
    }

    static LoggingEventBuilder withHost(LoggingEventBuilder record, LocalHost localHost) {
        try {
            InetAddress host = localHost.resolve();
            return record
                    .addKeyValue(LogEvent.HOST_NAME, host.getHostName())
                    .addKeyValue(LogEvent.HOST_IP, host.getHostAddress());
        } catch (UnknownHostException unresolvable) {
            return record;
        }
    }

    /** {@link InetAddress#getLocalHost()}, as a seam a test can make fail. */
    @FunctionalInterface
    interface LocalHost {
        InetAddress resolve() throws UnknownHostException;
    }
}
