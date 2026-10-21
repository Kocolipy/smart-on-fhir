package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.authorization.TestRoleMappings;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
            audit,
            TestRoleMappings.superuserOnly(),
            () -> {
            });

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
     * The dormancy job only takes memberships away, so effects that grant one are not the job's to
     * carry out: refused before anything is recorded or revoked, rather than audited as a
     * revocation.
     */
    @Test
    void the_dormancy_job_carries_out_no_membership_grant() {
        UUID group = UUID.randomUUID();
        UUID ada = UUID.randomUUID();
        var granting = ScimWriteEffects.ofGroupWrite(group,
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(), null),
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(ada), null),
                true);

        assertThatThrownBy(() -> applier.apply(
                        ScimWriteEffectsApplier.Writer.DORMANCY_JOB, granting, ScimIdentities.NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(audit.recorded()).isEmpty();
        assertThat(revoked).isEmpty();
    }

    /**
     * A User write moves no membership, so its apply step is given nothing to ask what a Group
     * confers or to count a grant with. Effects that move one anyway are refused as the User
     * write's mistake, before anything is recorded, rather than failing on a missing collaborator.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("membershipChanges")
    void a_user_write_carries_out_no_membership_change(String change,
            ScimWriteEffectsApplier.Writer writer, ScimWriteEffects<?> effects) {
        ScimWriteEffectsApplier userWrites = new ScimWriteEffectsApplier(
                users,
                new InMemoryScimGroupRepository(users),
                (connectorId, userId, causes) -> revoked.add(userId),
                audit);

        assertThatThrownBy(() -> userWrites.apply(writer, effects, ScimIdentities.NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a User write moves no Group membership");
        assertThat(audit.recorded()).isEmpty();
    }

    /** One of each way a membership can move: a connector's grant and revocation, and dormancy's. */
    static Stream<Arguments> membershipChanges() {
        UUID group = UUID.randomUUID();
        UUID ada = UUID.randomUUID();
        var without = new ScimWriteEffects.GroupState("Helpdesk", Set.of(), null);
        var with = new ScimWriteEffects.GroupState("Helpdesk", Set.of(ada), null);
        var connector = ScimWriteEffectsApplier.Writer.connector(UUID.randomUUID());
        return Stream.of(
                Arguments.of("a connector's grant", connector,
                        ScimWriteEffects.ofGroupWrite(group, without, with, true)),
                Arguments.of("a connector's revocation", connector,
                        ScimWriteEffects.ofGroupWrite(group, with, without, true)),
                Arguments.of("the dormancy job's revocation",
                        ScimWriteEffectsApplier.Writer.DORMANCY_JOB,
                        ScimWriteEffects.ofMembershipRevocation(ada, List.of(group))));
    }
}
