package com.example.backend.auth.config;

import com.example.backend.auth.application.DormancyRun;
import com.example.backend.auth.application.DormancyService;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scheduling.domain.ScheduledJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * One run of the dormancy job at startup, in the development profile only — the profile that
 * enables the development fixtures ({@code APP_DEV_FIXTURES_ENABLED}).
 *
 * <p>The job's schedule is daily at 04:00 and it has no HTTP surface, so without this a local run
 * or the e2e suite could only see a dormancy lock by waiting for the night. Seeding backdates the
 * dormant fixture's basis past the lockout window; this run, on {@link ApplicationReadyEvent} —
 * after every {@code ApplicationRunner}, seeding included — locks it through the real job, its
 * lock row, its audit events and its after-commit revocations. It applies to every User like the
 * nightly run would, so a long-unused local account is locked at startup rather than at 04:00.
 *
 * <p>Never registered in a deployment that has the fixtures off, which every non-development one
 * does: its schedule stays exactly the nightly one.
 */
@Configuration
@ConditionalOnBooleanProperty("app.dev-fixtures.enabled")
public class DevDormancyStartupConfig {

    private static final Logger log = LoggerFactory.getLogger(DevDormancyStartupConfig.class);

    private final DormancyService dormancy;

    public DevDormancyStartupConfig(DormancyService dormancy) {
        this.dormancy = dormancy;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnce() {
        DormancyRun run = dormancy.run();
        LoggingEventBuilder record =
                LogEvent.success(
                        log, ScheduledJob.DORMANCY.operation(), Category.BATCH, Type.INFO);
        if (run.skipped()) {
            record.addKeyValue(LogEvent.REASON, LogEvent.REASON_LOCK_HELD);
        } else {
            run.counts().forEach(record::addKeyValue);
        }
        record.log();
    }
}
