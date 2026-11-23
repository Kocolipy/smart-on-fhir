package com.example.backend;

import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A User of one suite's own holding {@code patient:read} as a Medical Officer does: the one direct
 * member of a Group of the suite's own that carries it, stored as a Group's Permissions are
 * (ADR 0015). A login ends the User's other sessions, so a suite signing in as its own Medical
 * Officer shares no session with a suite running beside it. {@link #delete} removes the User and
 * the Group, its Permissions with it.
 *
 * @param userName the User's userName, unique to the suite that made it
 * @param userId   the User's stable id
 * @param groupId  the Group's stable id
 */
public record TestMedicalOfficer(String userName, UUID userId, UUID groupId) {

    /** The Medical Officer's password. */
    public static final String PASSWORD = "medical-officer-password";

    /** A new Medical Officer, named from {@code prefix}, in the database {@code context} uses. */
    public static TestMedicalOfficer create(ApplicationContext context, String prefix) {
        ScimGroupRepository groups = context.getBean(ScimGroupRepository.class);
        TransactionTemplate transaction =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        String userName = ScimTestRows.unique(prefix);
        UUID userId = UUID.randomUUID();
        ScimTestRows.createUser(context, userId, userName, PASSWORD);
        UUID groupId = transaction.execute(status -> groups.create(ScimGroup.created(
                UUID.randomUUID(), ScimTestRows.unique("Medical Officer"),
                List.of(ScimGroupMember.reference(userId)), Instant.now())).id());
        context.getBean(JdbcTemplate.class).update(
                "INSERT INTO group_permission (group_id, permission) VALUES (?, ?)",
                groupId, "patient:read");
        return new TestMedicalOfficer(userName, userId, groupId);
    }

    /** Removes the Group, with its Permissions and membership, and then the User. */
    public void delete(JdbcTemplate jdbc) {
        ScimTestRows.delete(jdbc, groupId);
        ScimTestRows.delete(jdbc, userId);
    }
}
