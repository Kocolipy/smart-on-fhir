package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.authorization.domain.Role;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The apply step's own refusal. What it carries out for each writer is observed through the use
 * cases that call it — {@code ScimUserServiceTests}, {@code ScimGroupServiceTests} and
 * {@code DormancyServiceTests} — since every one of them goes through it.
 */
class ScimWriteEffectsApplierTests {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final List<UUID> revoked = new ArrayList<>();

    private final ScimWriteEffectsApplier applier = new ScimWriteEffectsApplier(
            users,
            new InMemoryScimGroupRepository(users),
            (connectorId, userId, causes) -> revoked.add(userId),
            audit);

    /**
     * A connector's write names its connector: the dormancy job is the one writer with no actor,
     * and it is its own writer rather than a connector with none.
     */
    @Test
    void a_connector_write_names_its_connector() {
        assertThatThrownBy(() -> ScimWriteEffectsApplier.Writer.connector(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("connectorId");
    }

    /**
     * The dormancy job only takes Roles away, so effects that grant one are not the job's to carry
     * out: refused before anything is recorded or revoked, rather than audited as a revocation.
     */
    @Test
    void the_dormancy_job_carries_out_no_role_grant() {
        UUID group = UUID.randomUUID();
        UUID ada = UUID.randomUUID();
        var granting = ScimWriteEffects.ofGroupWrite(group,
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(), null),
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(ada), null),
                Optional.of(new Role("Helpdesk", Set.of())));

        assertThatThrownBy(() -> applier.apply(
                        ScimWriteEffectsApplier.Writer.DORMANCY_JOB, granting, ScimIdentities.NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(audit.recorded()).isEmpty();
        assertThat(revoked).isEmpty();
    }
}
