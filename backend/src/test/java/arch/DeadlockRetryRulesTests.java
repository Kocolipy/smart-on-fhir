package arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.RetriedOnDeadlock;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deadlock retry's rules in {@link ArchitectureTest}, each shown reporting the shape it
 * forbids and passing the one it allows, on planted classes rather than on the service — whose
 * code complies, so the rules alone would never be seen to fail.
 */
class DeadlockRetryRulesTests {

    private static final UUID SOMEONE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // ---- coverage ------------------------------------------------------------------------------

    @Test
    void a_transactional_write_that_is_not_retried_is_reported() {
        assertThat(violations(ArchitectureTest.every_transactional_write_is_retried_on_deadlock,
                        Unretried.class))
                .contains("Unretried.write");
    }

    @Test
    void a_retried_write_without_a_transaction_of_its_own_is_reported() {
        assertThat(violations(ArchitectureTest.a_retried_write_opens_its_own_transaction,
                        RetriedWithoutATransaction.class, RetriedJoiningOnly.class))
                .contains("RetriedWithoutATransaction.write")
                .contains("RetriedJoiningOnly.write");
    }

    // ---- outermost placement ------------------------------------------------------------------

    @Test
    void a_retried_write_called_from_a_transaction_is_reported() {
        assertThat(violations(ArchitectureTest.a_retried_write_is_called_only_by_a_web_adapter,
                        NestingCaller.class, RefusesThenThrows.class))
                .contains("NestingCaller.writeBoth");
    }

    @Test
    void a_retried_write_that_implements_a_port_is_reported() {
        assertThat(violations(ArchitectureTest.a_retried_write_overrides_nothing,
                        ImplementsAPort.class))
                .contains("ImplementsAPort.write");
    }

    // ---- fail-open audit ordering -------------------------------------------------------------

    @Test
    void a_write_after_a_fail_open_event_is_reported() {
        assertThat(violations(ArchitectureTest.a_retried_write_ends_at_its_fail_open_audit_event,
                        RefusesThenWrites.class))
                .contains("RefusesThenWrites.write");
    }

    @Test
    void a_write_after_a_helper_that_appended_a_fail_open_event_is_reported() {
        assertThat(violations(ArchitectureTest.a_retried_write_ends_at_its_fail_open_audit_event,
                        RefusesInAHelperThenWrites.class))
                .contains("RefusesInAHelperThenWrites.write");
    }

    @Test
    void a_write_after_a_collaborator_that_appended_a_fail_open_event_is_reported() {
        assertThat(violations(ArchitectureTest.a_retried_write_ends_at_its_fail_open_audit_event,
                        CollaboratorRefusesThenWrites.class, RefusingCollaborator.class))
                .contains("CollaboratorRefusesThenWrites.write");
    }

    @Test
    void a_fail_open_event_the_body_ends_at_is_allowed() {
        assertThat(ArchitectureTest.a_retried_write_ends_at_its_fail_open_audit_event
                        .evaluate(new ClassFileImporter().importClasses(
                                RefusesThenThrows.class, RefusesThroughAHelperThenThrows.class))
                        .hasViolation())
                .isFalse();
    }

    // ---- harness ------------------------------------------------------------------------------

    /** The rule's violation report for {@code planted}, or empty when it reports none. */
    private static String violations(ArchRule rule, Class<?>... planted) {
        EvaluationResult result = rule.evaluate(new ClassFileImporter().importClasses(planted));
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

    // ---- planted classes ----------------------------------------------------------------------

    @Service
    static class Unretried {
        @Transactional
        public void write() {
        }
    }

    @Service
    static class RetriedWithoutATransaction {
        @RetriedOnDeadlock(Operation.UNLOCK)
        public void write() {
        }
    }

    @Service
    static class RetriedJoiningOnly {
        @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
        @RetriedOnDeadlock(Operation.UNLOCK)
        public void write() {
        }
    }

    interface Port {
        void write();
    }

    @Service
    static class ImplementsAPort implements Port {
        @Override
        @Transactional
        @RetriedOnDeadlock(Operation.UNLOCK)
        public void write() {
        }
    }

    /** The compliant shape: a refusal's fail-open event, then the throw that ends the body. */
    @Service
    static class RefusesThenThrows {
        AuditTrail audit;
        ScimConnectorRepository connectors;

        @Transactional
        @RetriedOnDeadlock(Operation.CONNECTOR_CREATE)
        public void write(ScimConnector connector, boolean refuse) {
            if (refuse) {
                audit.append(new Audited.UnlockRefused(
                    SOMEONE, SOMEONE, AuditAdministrativeRefusal.SELF_TARGET));
                throw new IllegalStateException("refused");
            }
            connectors.save(connector);
            audit.append(new Audited.ConnectorCreated(SOMEONE, connector.id()));
        }
    }

    /** The compliant shape with the event in a helper that hands back the refusal to throw. */
    @Service
    static class RefusesThroughAHelperThenThrows {
        AuditTrail audit;
        ScimConnectorRepository connectors;

        @Transactional
        @RetriedOnDeadlock(Operation.CONNECTOR_CREATE)
        public void write(ScimConnector connector, boolean refuse) {
            if (refuse) {
                throw refusal();
            }
            connectors.save(connector);
        }

        private RuntimeException refusal() {
            audit.append(new Audited.UnlockRefused(
                    SOMEONE, SOMEONE, AuditAdministrativeRefusal.SELF_TARGET));
            return new IllegalStateException("refused");
        }
    }

    @Service
    static class RefusesThenWrites {
        AuditTrail audit;
        ScimConnectorRepository connectors;

        @Transactional
        @RetriedOnDeadlock(Operation.CONNECTOR_CREATE)
        public void write(ScimConnector connector) {
            audit.append(new Audited.UnlockRefused(
                    SOMEONE, SOMEONE, AuditAdministrativeRefusal.SELF_TARGET));
            connectors.save(connector);
        }
    }

    @Service
    static class RefusesInAHelperThenWrites {
        AuditTrail audit;
        ScimConnectorRepository connectors;

        @Transactional
        @RetriedOnDeadlock(Operation.CONNECTOR_CREATE)
        public void write(ScimConnector connector) {
            recordRefusal();
            connectors.save(connector);
        }

        private void recordRefusal() {
            audit.append(new Audited.UnlockRefused(
                    SOMEONE, SOMEONE, AuditAdministrativeRefusal.SELF_TARGET));
        }
    }

    @Service
    static class RefusingCollaborator {
        AuditTrail audit;

        public void recordRefusal() {
            audit.append(new Audited.UnlockRefused(
                    SOMEONE, SOMEONE, AuditAdministrativeRefusal.SELF_TARGET));
        }
    }

    @Service
    static class CollaboratorRefusesThenWrites {
        RefusingCollaborator collaborator;
        ScimConnectorRepository connectors;

        @Transactional
        @RetriedOnDeadlock(Operation.CONNECTOR_CREATE)
        public void write(ScimConnector connector) {
            collaborator.recordRefusal();
            connectors.save(connector);
        }
    }

    @Service
    static class NestingCaller {
        RefusesThenThrows writes;

        @Transactional
        public void writeBoth(ScimConnector connector) {
            writes.write(connector, false);
        }
    }
}
