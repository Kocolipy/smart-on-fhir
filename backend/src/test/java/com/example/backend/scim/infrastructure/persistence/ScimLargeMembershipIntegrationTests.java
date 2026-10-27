package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupHeadcount;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The directory against a real SQL Server when one write names more resources than a single
 * request can bind.
 *
 * <p>SQL Server refuses a request carrying more than 2,100 parameters (error 8003), and nothing
 * caps a Group's membership or the number of Groups a User belongs to: a PATCH add grows a Group
 * without limit, and one PUT can carry about twenty thousand members. Every write below advances
 * the version of each of {@link #MANY} resources, and every read binds that many ids, so each one
 * fails with a {@code 500} unless the store splits its {@code IN} lists.
 *
 * <p>The resources are seeded in bulk, straight into the tables, because creating
 * {@value #MANY} through the port would cost a round trip each; the behaviour under test is then
 * exercised and read back through the ports alone. Every seeded name carries this test's tag, and
 * the rows are removed by that tag afterwards.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimLargeMembershipIntegrationTests {

    /** More than one request can bind, and more than one chunk of {@link BoundIdChunks}. */
    private static final int MANY = 2_200;

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final String tag = "large-" + UUID.randomUUID();

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        String byTag = tag + "%";
        jdbc.update("""
                DELETE m FROM scim_group_members m
                  JOIN scim_users u ON u.resource_id = m.user_id
                 WHERE u.normalized_user_name LIKE ?""", byTag);
        jdbc.update("""
                DELETE m FROM scim_group_members m
                  JOIN scim_groups g ON g.resource_id = m.group_id
                 WHERE g.normalized_display_name LIKE ?""", byTag);
        jdbc.update("""
                DELETE r FROM scim_resources r
                  JOIN scim_users u ON u.resource_id = r.id
                 WHERE u.normalized_user_name LIKE ?""", byTag);
        jdbc.update("""
                DELETE r FROM scim_resources r
                  JOIN scim_groups g ON g.resource_id = r.id
                 WHERE g.normalized_display_name LIKE ?""", byTag);
    }

    /** A create with every member advances each member's version, as for a small Group. */
    @Test
    void aGroupCreatedWithMoreMembersThanOneRequestBindsAdvancesEveryMember() {
        List<UUID> members = seededUsers(MANY);

        inTransaction(() -> groups.create(groupOf(members)));

        assertThat(userVersions(members)).hasSize(MANY).containsOnly(2L);
    }

    /** A rename changes the label in every member's {@code groups}, so every member advances. */
    @Test
    void aGroupWithMoreMembersThanOneRequestBindsCanBeRenamed() {
        List<UUID> members = seededUsers(MANY);
        ScimGroup group = inTransaction(
                () -> groups.findById(seededGroupOf(members)).orElseThrow());

        inTransaction(() -> groups.replace(group.replacedWith(
                tag + "-renamed", group.members(), now())));

        assertThat(userVersions(members)).hasSize(MANY).containsOnly(2L);
    }

    /** A delete removes the Group from every member's {@code groups}, so every member advances. */
    @Test
    void aGroupWithMoreMembersThanOneRequestBindsCanBeDeleted() {
        List<UUID> members = seededUsers(MANY);
        UUID group = seededGroupOf(members);

        inTransaction(() -> groups.deleteById(group, now()));

        assertThat(userVersions(members)).hasSize(MANY).containsOnly(2L);
    }

    /** A User's deletion removes a member from every Group it was in, so every Group advances. */
    @Test
    void aUserInMoreGroupsThanOneRequestBindsCanBeDeleted() {
        UUID user = seededUsers(1).getFirst();
        List<UUID> groupIds = seededGroupsContaining(user, MANY);

        inTransaction(() -> {
            users.deleteById(user, now());
            return null;
        });

        assertThat(groupVersions(groupIds)).hasSize(MANY).containsOnly(2L);
    }

    /**
     * The Groups projection counts a Group's members where they are stored, so a Group with more
     * members than one request binds is counted exactly, without its members being read.
     */
    @Test
    void theCountedPageCountsAGroupWithMoreMembersThanOneRequestBinds() {
        UUID group = seededGroupOf(seededUsers(MANY));

        List<ScimGroupHeadcount> counted =
                inTransaction(() -> groups.findHeadcountsById(List.of(group)));

        assertThat(counted).extracting(ScimGroupHeadcount::memberCount).containsExactly(MANY);
    }

    // ---- helpers --------------------------------------------------------------------------

    private ScimGroup groupOf(List<UUID> members) {
        return ScimGroup.created(
                UUID.randomUUID(),
                tag + "-group",
                members.stream().map(ScimGroupMember::reference).toList(),
                now());
    }

    /**
     * {@code count} active Users at version 1. Set-based, from one bound list of ids, so seeding
     * costs two statements rather than a round trip per row — and binds one parameter for the ids
     * where a row-per-id {@code VALUES} list would bind more than the limit under test.
     */
    private List<UUID> seededUsers(int count) {
        List<UUID> ids = randomIds(count);
        String list = joined(ids);
        seedResources("User", list);
        jdbc.update("""
                INSERT INTO scim_users (resource_id, user_name, normalized_user_name, active)
                SELECT CAST(value AS UNIQUEIDENTIFIER), CONCAT(?, '-', value),
                       CONCAT(?, '-', value), 1
                  FROM STRING_SPLIT(?, ',')""", tag, tag, list);
        return ids;
    }

    /** {@code count} Groups at version 1, each with this User as its one member, set-based. */
    private List<UUID> seededGroupsContaining(UUID user, int count) {
        List<UUID> ids = randomIds(count);
        String list = joined(ids);
        seedResources("Group", list);
        jdbc.update("""
                INSERT INTO scim_groups (resource_id, display_name, normalized_display_name)
                SELECT CAST(value AS UNIQUEIDENTIFIER), CONCAT(?, '-group-', value),
                       CONCAT(?, '-group-', value)
                  FROM STRING_SPLIT(?, ',')""", tag, tag, list);
        seedMembers(list, user.toString());
        return ids;
    }

    /** One Group at version 1 with these Users as its members, set-based. */
    private UUID seededGroupOf(List<UUID> members) {
        UUID id = UUID.randomUUID();
        seedResources("Group", id.toString());
        jdbc.update("""
                INSERT INTO scim_groups (resource_id, display_name, normalized_display_name)
                VALUES (?, ?, ?)""", id, tag + "-group", tag + "-group");
        seedMembers(id.toString(), joined(members));
        return id;
    }

    /** Resources of this type at version 1, one per id in the comma-separated list. */
    private void seedResources(String type, String list) {
        jdbc.update("""
                INSERT INTO scim_resources (id, resource_type, version, created_at, last_modified_at)
                SELECT CAST(value AS UNIQUEIDENTIFIER), ?, 1,
                       SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET()
                  FROM STRING_SPLIT(?, ',')""", type, list);
    }

    /** Every User in the one comma-separated list a member of every Group in the other. */
    private void seedMembers(String groupList, String userList) {
        jdbc.update("""
                INSERT INTO scim_group_members (group_id, user_id)
                SELECT CAST(g.value AS UNIQUEIDENTIFIER), CAST(u.value AS UNIQUEIDENTIFIER)
                  FROM STRING_SPLIT(?, ',') AS g CROSS JOIN STRING_SPLIT(?, ',') AS u""",
                groupList, userList);
    }

    private static List<UUID> randomIds(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    /** The ids as one comma-separated value, for {@code STRING_SPLIT}. */
    private static String joined(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    /**
     * These Users' versions, read back through the port by id: one read per {@link BoundIdChunks}
     * chunk, where reading each by id would cost a round trip apiece.
     */
    private List<Long> userVersions(List<UUID> ids) {
        return inTransaction(() -> BoundIdChunks.of(ids).stream()
                .flatMap(chunk -> users.findAllById(chunk).stream())
                .map(ScimUser::version)
                .toList());
    }

    /** These Groups' versions, read back through the port by id, as for the Users. */
    private List<Long> groupVersions(List<UUID> ids) {
        return inTransaction(() -> BoundIdChunks.of(ids).stream()
                .flatMap(chunk -> groups.findAllById(chunk).stream())
                .map(ScimGroup::version)
                .toList());
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
