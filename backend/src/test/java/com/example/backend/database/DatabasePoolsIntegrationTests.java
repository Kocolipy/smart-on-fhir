package com.example.backend.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The service's two database pools as an operator sees them: the main pool, {@code app}, and the
 * audit retention job's, {@code retention}, each in the {@code db} health indicator — under the
 * bean that declares it, {@code dataSource} and {@code auditRetentionDataSource} — and in
 * Micrometer's Hikari series under its pool name (ADR 0015, lm-16; #66).
 *
 * <p>The retention pool starts on its first use. A health check is one — the {@code db} indicator
 * borrows a connection from every pool — so each test checks health first, as a deployment's
 * load balancer does from its first seconds, and then reads what that left behind.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class DatabasePoolsIntegrationTests {

    @Autowired
    private HealthContributorRegistry health;

    @Autowired
    private MeterRegistry meters;

    private Map<String, Status> db;

    @BeforeEach
    void checkHealth() {
        db = ((CompositeHealthContributor) health.getContributor("db")).stream()
                .collect(Collectors.toMap(HealthContributors.Entry::name,
                        entry -> ((HealthIndicator) entry.contributor()).health(false).getStatus()));
    }

    @Test
    void theDbHealthIndicatorFindsTheAppAndRetentionPoolsUp() {
        assertThat(db)
                .containsEntry("dataSource", Status.UP)
                .containsEntry("auditRetentionDataSource", Status.UP);
    }

    @Test
    void bothPoolsPublishTheirSizeUnderTheirOwnName() {
        assertThat(List.of("app", "retention").stream()
                        .map(pool -> meters.find("hikaricp.connections.max").tag("pool", pool).gauge())
                        .map(gauge -> gauge == null ? null : gauge.value())
                        .toList())
                .containsExactly(10.0, 2.0);
    }
}
