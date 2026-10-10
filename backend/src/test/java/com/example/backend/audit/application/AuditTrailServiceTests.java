package com.example.backend.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditFilterShape;
import com.example.backend.audit.domain.AuditEventRepository;
import com.example.backend.audit.domain.AuditGroupAttribute;
import com.example.backend.audit.domain.AuditLockCause;
import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditRequest;
import com.example.backend.audit.domain.AuditRequestContext;
import com.example.backend.audit.domain.AuditScimRefusal;
import com.example.backend.audit.domain.AuditSessionRevocationCause;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.OperationalAlerts;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * What the trail records, and what it does when it cannot record.
 *
 * <p>The two failure semantics are the point of this class. A fail-closed append
 * must propagate — that is the whole mechanism by which a mutation it could not
 * record is rolled back — and a fail-open one must not, having raised an alert
 * instead. Both are asserted against the same forced failure, so the difference
 * cannot be an accident of which exception was thrown.
 */
class AuditTrailServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-25T07:00:00Z");

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID SUBJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID GROUP = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final RecordingRepository events = new RecordingRepository();
    private final RecordingAlerts alerts = new RecordingAlerts();
    private final AuditRequestContext requests =
            () -> new AuditRequest("POST", "/api/admin/accounts/{id}/unlock", "req-1");

    private final AuditTrail trail = new AuditTrailService(
            events,
            requests,
            alerts,
            Clock.fixed(NOW, ZoneOffset.UTC),
            NO_TRANSACTION_MANAGER);

    // The shape of what is recorded

    /**
     * A bulk read carries its count and its filter's rendered shape, names no subject, and is a
     * success; every other event leaves both fields null.
     */
    @Test
    void aBulkReadRecordsItsCountAndFilterShapeAndNoSubject() {
        AuditFilterShape shape = new AuditFilterShape.And(
                new AuditFilterShape.Comparison(
                        AuditFilterShape.Attribute.USER_NAME, AuditFilterShape.Operator.EQ, false),
                new AuditFilterShape.ValuePath(
                        AuditFilterShape.Attribute.EMAILS,
                        new AuditFilterShape.Not(new AuditFilterShape.Presence(
                                AuditFilterShape.Attribute.EMAILS_TYPE, true))));

        trail.recordScimUsersQueried(ACTOR, 7, shape);
        trail.recordScimGroupsQueried(ACTOR, 0, null);
        trail.recordScimResourcesQueried(ACTOR, 2, new AuditFilterShape.Or(
                new AuditFilterShape.Presence(AuditFilterShape.Attribute.MEMBERS, false),
                new AuditFilterShape.Comparison(
                        AuditFilterShape.Attribute.META_CREATED, AuditFilterShape.Operator.GT, false)));
        trail.recordLoginSuccess(SUBJECT, AuditLoginMethod.PASSWORD);

        assertThat(events.appended).extracting(AuditEvent::operation).containsExactly(
                AuditOperation.SCIM_USER_LIST, AuditOperation.SCIM_GROUP_LIST,
                AuditOperation.SCIM_RESOURCE_LIST, AuditOperation.LOGIN_SUCCESS);
        assertThat(events.appended).extracting(AuditEvent::resourceType)
                .containsExactly("User", "Group", "User,Group", "User");
        assertThat(events.appended).extracting(AuditEvent::resultCount)
                .containsExactly(7, 0, 2, null);
        assertThat(events.appended).extracting(AuditEvent::filterShape).containsExactly(
                "(userName eq ? and emails[not (type pr)])",
                null,
                "(members pr or meta.created gt ?)",
                null);
        assertThat(events.appended.subList(0, 3)).allSatisfy(event -> {
            assertThat(event.actorId()).isEqualTo(ACTOR);
            assertThat(event.subjectId()).isNull();
            assertThat(event.resourceId()).isNull();
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(event.statusClass()).isEqualTo("ok");
            assertThat(event.errorCode()).isNull();
            assertThat(event.changedPaths()).isEmpty();
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.httpMethod()).isEqualTo("POST");
            assertThat(event.httpPath()).isEqualTo("/api/admin/accounts/{id}/unlock");
            assertThat(event.requestId()).isEqualTo("req-1");
            assertThat(event.id()).isNotNull();
        });
    }

    /** Every operator renders as the RFC spells it, and every attribute as its canonical path. */
    @Test
    void aFilterShapeRendersEveryOperatorAndAttributeCanonically() {
        for (AuditFilterShape.Operator operator : AuditFilterShape.Operator.values()) {
            assertThat(new AuditFilterShape.Comparison(
                            AuditFilterShape.Attribute.LOCALE, operator, false).render())
                    .isEqualTo("locale " + operator.name().toLowerCase(java.util.Locale.ROOT) + " ?");
        }
        assertThat(new AuditFilterShape.Comparison(
                        AuditFilterShape.Attribute.GROUPS_REF, AuditFilterShape.Operator.EQ, true)
                .render()).isEqualTo("$ref eq ?");
        assertThat(new AuditFilterShape.Presence(AuditFilterShape.Attribute.USER_NAME, true).render())
                .as("a top-level attribute has no shorter in-value-path name")
                .isEqualTo("userName pr");
    }
    @Test
    void anAcceptedLoginIsRecordedAgainstTheAccountsStableId() {
        trail.recordLoginSuccess(SUBJECT, AuditLoginMethod.PASSWORD);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.LOGIN_SUCCESS);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.actorId()).isEqualTo(SUBJECT);
        assertThat(event.subjectId()).isEqualTo(SUBJECT);
        assertThat(event.resourceId()).isEqualTo(SUBJECT);
        assertThat(event.resourceType()).isEqualTo("User");
        assertThat(event.statusClass()).isEqualTo("ok");
        assertThat(event.errorCode()).isNull();
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.changedPaths()).isEmpty();
        assertThat(event.permissions()).as("a login names no Permissions").isEmpty();
        assertThat(event.httpMethod()).isEqualTo("POST");
        assertThat(event.requestId()).isEqualTo("req-1");
        assertThat(event.id()).isNotNull();
    }

    /** D15: each login event carries how it was attempted, in the recorded spelling. */
    @Test
    void anAcceptedLoginRecordsItsLoginMethod() {
        trail.recordLoginSuccess(SUBJECT, AuditLoginMethod.PASSWORD);
        trail.recordLoginSuccess(SUBJECT, AuditLoginMethod.SSO);

        assertThat(events.appended).extracting(AuditEvent::loginMethod)
                .containsExactly("password", "sso");
    }

    @Test
    void aRefusedLoginRecordsItsLoginMethod() {
        trail.recordLoginFailure(SUBJECT, AuditRefusalReason.BAD_CREDENTIALS,
                AuditLoginMethod.PASSWORD);
        trail.recordLoginFailure(null, AuditRefusalReason.UNKNOWN_ACCOUNT, AuditLoginMethod.SSO);

        assertThat(events.appended).extracting(AuditEvent::loginMethod)
                .containsExactly("password", "sso");
    }

    /** Only a login names a method; a logout, like every other event, carries none. */
    @Test
    void anEventOtherThanALoginCarriesNoLoginMethod() {
        trail.recordLogout(SUBJECT);

        assertThat(events.only().loginMethod()).isNull();
    }

    @Test
    void aLogoutIsRecordedWithTheRequestThatEndedTheSession() {
        trail.recordLogout(SUBJECT);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.LOGOUT);
        assertThat(event.actorId()).isEqualTo(SUBJECT);
        assertThat(event.subjectId()).isEqualTo(SUBJECT);
        assertThat(event.changedPaths()).isEmpty();
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.httpMethod()).isEqualTo("POST");
        assertThat(event.httpPath()).isEqualTo("/api/admin/accounts/{id}/unlock");
        assertThat(event.requestId()).isEqualTo("req-1");
    }

    /** Two events recorded in one turn get distinct identities. */
    @Test
    void everyEventCarriesItsOwnIdentity() {
        trail.recordLogout(SUBJECT);
        trail.recordLoginSuccess(SUBJECT, AuditLoginMethod.PASSWORD);

        assertThat(events.appended).extracting(AuditEvent::id).doesNotContainNull();
        assertThat(events.appended.get(0).id()).isNotEqualTo(events.appended.get(1).id());
    }

    @Test
    void aRefusedLoginNamesTheReasonAndNoActor() {
        trail.recordLoginFailure(SUBJECT, AuditRefusalReason.BAD_CREDENTIALS, AuditLoginMethod.PASSWORD);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.LOGIN_FAILURE);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.actorId()).isNull();
        assertThat(event.subjectId()).isEqualTo(SUBJECT);
        assertThat(event.errorCode()).isEqualTo("BAD_CREDENTIALS");
        assertThat(event.statusClass()).isEqualTo("client_error");
        assertThat(event.changedPaths()).containsExactly("failedLoginAttempts");
    }

    /**
     * A refusal that counted toward no failure run (an Epic Login's, D12) is the same
     * {@code LOGIN_FAILURE}, and does not claim a failure-run change that never happened.
     */
    @Test
    void aRefusalThatCountsTowardNoFailureRunNamesNoChangedPath() {
        trail.recordLoginRefusal(SUBJECT, AuditRefusalReason.ACCOUNT_LOCKED, AuditLoginMethod.SSO);

        AuditEvent event = events.only();
        assertThat(event.changedPaths()).isEmpty();
    }

    @Test
    void aRefusalThatCountsTowardNoFailureRunIsALoginFailureNamingItsReasonAndMethod() {
        trail.recordLoginRefusal(SUBJECT, AuditRefusalReason.ACCOUNT_LOCKED, AuditLoginMethod.SSO);

        AuditEvent event = events.only();
        assertThat(List.<Object>of(event.operation(), event.outcome(), event.subjectId(),
                        event.errorCode(), event.loginMethod()))
                .containsExactly(AuditOperation.LOGIN_FAILURE, AuditOutcome.FAILURE, SUBJECT,
                        "ACCOUNT_LOCKED", "sso");
    }

    /**
     * The case that must not record the submitted username: no account carries it,
     * so there is nothing to name and the subject stays absent.
     */
    @Test
    void aRefusedLoginAgainstAnUnknownNameRecordsNoSubjectAtAll() {
        trail.recordLoginFailure(null, AuditRefusalReason.UNKNOWN_ACCOUNT, AuditLoginMethod.PASSWORD);

        AuditEvent event = events.only();
        assertThat(event.subjectId()).isNull();
        assertThat(event.resourceId()).isNull();
        assertThat(event.errorCode()).isEqualTo("UNKNOWN_ACCOUNT");
    }

    /**
     * An unlock is the only way a lockout ends, so the event names the administrator who
     * performed it — and carries, as its {@code errorCode}, the cause of the lock it lifted, or
     * nothing when none stood.
     */
    @Test
    void theOnlyLockoutLiftNamesItsAdministratorAndTheCauseItLifted() {
        trail.recordLockoutLiftedByUnlock(ACTOR, SUBJECT, AuditLockCause.DORMANCY);
        trail.recordLockoutLiftedByUnlock(ACTOR, SUBJECT, AuditLockCause.FAILURES);
        trail.recordLockoutLiftedByUnlock(ACTOR, SUBJECT, null);

        assertThat(events.appended).extracting(AuditEvent::errorCode)
                .containsExactly("DORMANCY", "FAILURES", null);
        AuditEvent event = events.appended.getFirst();
        assertThat(event.operation()).isEqualTo(AuditOperation.LOCKOUT_LIFT);
        assertThat(event.actorId()).isEqualTo(ACTOR);
        assertThat(event.subjectId()).isEqualTo(SUBJECT);
        assertThat(event.changedPaths())
                .containsExactly("failedLoginAttempts", "lockedAt", "lockCause");
    }

    /**
     * Authentication, lockout, administrative standing and provisioning all name one
     * resource type now, because they act on one resource: the account aggregate is gone and
     * a SCIM User owns the profile and the authentication state together. That collapse is
     * the assertion — an administrator reading the trail groups a person's whole history
     * under one type and one id.
     */
    @Test
    void everyEventAboutTheLoginIdentityNamesTheScimUserResourceType() {
        trail.recordLoginSuccess(SUBJECT, AuditLoginMethod.PASSWORD);
        trail.recordLoginFailure(SUBJECT, AuditRefusalReason.BAD_CREDENTIALS, AuditLoginMethod.PASSWORD);
        trail.recordLogout(SUBJECT);
        trail.recordLockoutSet(SUBJECT);
        trail.recordLockoutLiftedByUnlock(ACTOR, SUBJECT, AuditLockCause.FAILURES);
        trail.recordPasswordChangeRequired(ACTOR, SUBJECT);
        trail.recordScimUserCreated(ACTOR, SUBJECT);
        trail.recordScimUsersQueried(ACTOR, 3, null);

        assertThat(events.appended)
                .extracting(AuditEvent::resourceType)
                .containsOnly(AuditEvent.USER_RESOURCE_TYPE);
    }

    /**
     * The route template reaches the event and the resolved path never does — the
     * context this test supplies returns a template, and the recorded value is it
     * verbatim. What guarantees the context cannot return a resolved path is
     * {@code HttpAuditRequestContextAdapterTests}.
     */
    @Test
    void theRequestIsRecordedAsItsRouteTemplate() {
        trail.recordLockoutLiftedByUnlock(ACTOR, SUBJECT, AuditLockCause.FAILURES);

        AuditEvent event = events.only();
        assertThat(event.httpMethod()).isEqualTo("POST");
        assertThat(event.httpPath()).isEqualTo("/api/admin/accounts/{id}/unlock");
        assertThat(event.requestId()).isEqualTo("req-1");
    }

    // The Group and seeding vocabulary

    /**
     * A Group event names the Group's own id as both subject and resource, and the connector
     * as the actor. Which User was added or removed is deliberately absent: a membership has
     * no id of its own, and naming the other party would put a second identity's history
     * inside this event.
     */
    @Test
    void aGroupEventNamesTheGroupAndTheActingConnector() {
        trail.recordScimGroupCreated(ACTOR, GROUP);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.SCIM_GROUP_CREATE);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.resourceType()).isEqualTo(AuditEvent.GROUP_RESOURCE_TYPE);
        assertThat(event.actorId()).isEqualTo(ACTOR);
        assertThat(event.subjectId()).isEqualTo(GROUP);
        assertThat(event.resourceId()).isEqualTo(GROUP);
        assertThat(event.statusClass()).isEqualTo("ok");
        assertThat(event.errorCode()).isNull();
    }

    /**
     * A refused User create names its reason and no subject, whichever refusal it was — a held
     * {@code userName} or a password the policy does not accept.
     */
    @Test
    void aRefusedUserCreateNamesTheReasonAndNoSubject() {
        trail.recordScimUserCreateRejected(ACTOR, AuditScimRefusal.INVALID_VALUE);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.SCIM_USER_CREATE);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.subjectId()).isNull();
        assertThat(event.resourceId()).isNull();
        assertThat(event.errorCode()).isEqualTo("INVALID_VALUE");
        assertThat(event.statusClass()).isEqualTo("client_error");
    }

    /**
     * A refused create names no subject: the Group was not created, and the existing resource
     * that caused the refusal is not what the event is about.
     */
    @Test
    void aRefusedGroupCreateNamesTheReasonAndNoSubject() {
        trail.recordScimGroupCreateRejected(ACTOR, AuditScimRefusal.UNIQUENESS);

        AuditEvent event = events.only();
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.subjectId()).isNull();
        assertThat(event.resourceId()).isNull();
        assertThat(event.errorCode()).isEqualTo("UNIQUENESS");
        assertThat(event.statusClass()).isEqualTo("client_error");
    }

    /**
     * A refused WRITE does name the Group, unlike a refused create: the resource exists, the
     * write was aimed at it, and an attempt to provision the recovery authority away is
     * exactly what an administrator searches for by that Group's id.
     */
    @Test
    void aRefusedGroupWriteNamesTheGroupItWasAimedAt() {
        trail.recordScimGroupWriteRejected(ACTOR, GROUP, AuditScimRefusal.MUTABILITY);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.SCIM_GROUP_REPLACE);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.subjectId()).isEqualTo(GROUP);
        assertThat(event.errorCode()).isEqualTo("MUTABILITY");
    }

    /**
     * The changed attributes become this slice's own path names, in a fixed order — so a
     * caller cannot assemble the recorded list and a reader can filter on it.
     */
    @Test
    void aGroupReplacementRecordsWhichAttributesMovedAsThisSlicesOwnPaths() {
        trail.recordScimGroupReplaced(
                ACTOR,
                GROUP,
                Set.of(AuditGroupAttribute.MEMBERS, AuditGroupAttribute.DISPLAY_NAME));

        assertThat(events.only().changedPaths()).containsExactly("displayName", "members");
    }

    /**
     * A change to the calling connector's alias is recorded as the path {@code externalId} on
     * both resource types — the path only: no method on the trail takes the alias value.
     */
    @Test
    void aGroupAliasChangeIsRecordedAsTheExternalIdPath() {
        trail.recordScimGroupReplaced(ACTOR, GROUP,
                Set.of(AuditGroupAttribute.EXTERNAL_ID, AuditGroupAttribute.DISPLAY_NAME));

        assertThat(events.only().changedPaths()).containsExactly("displayName", "externalId");
    }

    /** The User counterpart of {@link #aGroupAliasChangeIsRecordedAsTheExternalIdPath}. */
    @Test
    void aUserAliasChangeIsRecordedAsTheExternalIdPath() {
        trail.recordScimUserReplaced(ACTOR, GROUP,
                Set.of(AuditUserAttribute.EXTERNAL_ID, AuditUserAttribute.EMAILS));

        assertThat(events.only().changedPaths()).containsExactly("emails", "externalId");
    }

    /**
     * A User deletion names the deleted User and changes no attribute path: the whole
     * resource went, which the operation says on its own.
     */
    @Test
    void aUserDeletionNamesTheDeletedUserAndRecordsNoPaths() {
        trail.recordScimUserDeleted(ACTOR, SUBJECT);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.SCIM_USER_DELETE);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.actorId()).isEqualTo(ACTOR);
        assertThat(event.subjectId()).isEqualTo(SUBJECT);
        assertThat(event.statusClass()).isEqualTo("ok");
        assertThat(event.errorCode()).isNull();
        assertThat(event.changedPaths()).isEmpty();
    }

    /** A refused deletion names the User it was aimed at and why it was refused. */
    @Test
    void aRefusedUserDeletionNamesTheUserAndTheRefusal() {
        trail.recordScimUserDeleteRejected(ACTOR, SUBJECT, AuditScimRefusal.MUTABILITY);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.SCIM_USER_DELETE);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.subjectId()).isEqualTo(SUBJECT);
        assertThat(event.statusClass()).isEqualTo("client_error");
        assertThat(event.errorCode()).isEqualTo("MUTABILITY");
        assertThat(event.changedPaths()).isEmpty();
    }

    /**
     * The deletion's append is fail-closed, so a deletion the trail cannot record rolls
     * back; its refusal's append is fail-open, as every refused write's is.
     */
    @Test
    void aUserDeletionFailsClosedAndItsRefusalFailsOpen() {
        events.failWith(new IllegalStateException("insert refused"));

        assertThatThrownBy(() -> trail.recordScimUserDeleted(ACTOR, SUBJECT))
                .isInstanceOf(IllegalStateException.class);
        trail.recordScimUserDeleteRejected(ACTOR, SUBJECT, AuditScimRefusal.MUTABILITY);

        assertThat(alerts.raised).containsExactly(AuditOperation.SCIM_USER_DELETE);
    }

    @Test
    void aGroupReplacementThatMovedNothingRecordsNoChangedPath() {
        trail.recordScimGroupReplaced(ACTOR, GROUP, Set.of());

        assertThat(events.only().changedPaths()).isEmpty();
    }

    /**
     * Seeding has no actor, because nobody acted: it is the deployment establishing its own
     * recovery path at startup, and inventing an actor would make the trail claim somebody
     * did this. The seeded resource is the subject, so the event reads as "this identity came
     * into existence".
     */
    @Test
    void aSeededReservedResourceNamesNoActorAndTypesItselfByWhatWasSeeded() {
        trail.recordReservedResourceSeeded(SUBJECT, false);
        trail.recordReservedResourceSeeded(GROUP, true);

        assertThat(events.appended).allSatisfy(event -> {
            assertThat(event.operation()).isEqualTo(AuditOperation.SCIM_RESOURCE_SEED);
            assertThat(event.actorId()).isNull();
        });
        assertThat(events.appended.get(0).resourceType())
                .isEqualTo(AuditEvent.USER_RESOURCE_TYPE);
        assertThat(events.appended.get(0).subjectId()).isEqualTo(SUBJECT);
        assertThat(events.appended.get(1).resourceType())
                .isEqualTo(AuditEvent.GROUP_RESOURCE_TYPE);
        assertThat(events.appended.get(1).subjectId()).isEqualTo(GROUP);
    }

    /**
     * A restored Admin-group membership is a seeding event about the Group, with no actor, and
     * records its changed path in the same vocabulary a connector's membership change uses — so a
     * reader filtering the trail on {@code members} finds the restore beside the writes.
     */
    @Test
    void aRestoredReservedMembershipIsASeedEventOnTheGroupNamingTheMembersPath() {
        trail.recordReservedMembershipRestored(GROUP, SUBJECT);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.SCIM_RESOURCE_SEED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.actorId()).isNull();
        assertThat(event.subjectId()).isEqualTo(GROUP);
        assertThat(event.resourceType()).isEqualTo(AuditEvent.GROUP_RESOURCE_TYPE);
        assertThat(event.changedPaths()).containsExactly("members");
    }

    /**
     * A refused administrative change is recorded as a failure naming the reason, and names
     * both parties: the administrator who asked and the identity it was aimed at.
     */
    @Test
    void aRefusedAdministrativeChangeNamesBothPartiesAndTheReason() {
        trail.recordUnlockRefused(ACTOR, SUBJECT, AuditAdministrativeRefusal.SELF_TARGET);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.LOCKOUT_LIFT);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.actorId()).isEqualTo(ACTOR);
        assertThat(event.subjectId()).isEqualTo(SUBJECT);
        assertThat(event.errorCode()).isEqualTo("SELF_TARGET");
        assertThat(event.statusClass()).isEqualTo("client_error");
        assertThat(event.changedPaths()).isEmpty();
    }

    /**
     * An authorization refusal names the refused User as actor and subject, the operation as the
     * method, route template and correlation id the CALLER passed — not the request context's,
     * which a chain-level refusal predates — and the one generic reason.
     */
    @Test
    void anAuthorizationRefusalNamesTheCallerTheOperationAndTheGenericReason() {
        trail.recordAccessDenied(
                ACTOR, new AuditRequest("GET", "/api/admin/audit-events", "req-refused"));

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.ACCESS_DENIED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.actorId()).isEqualTo(ACTOR);
        assertThat(event.subjectId()).isEqualTo(ACTOR);
        assertThat(event.resourceId()).isEqualTo(ACTOR);
        assertThat(event.resourceType()).isEqualTo(AuditEvent.USER_RESOURCE_TYPE);
        assertThat(event.errorCode()).isEqualTo("INSUFFICIENT_PERMISSIONS");
        assertThat(event.statusClass()).isEqualTo("client_error");
        assertThat(event.changedPaths()).isEmpty();
        assertThat(event.httpMethod()).isEqualTo("GET");
        assertThat(event.httpPath()).isEqualTo("/api/admin/audit-events");
        assertThat(event.requestId()).isEqualTo("req-refused");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.id()).isNotNull();
        assertThat(event.resultCount()).isNull();
        assertThat(event.filterShape()).isNull();
    }

    /** A refusal the trail cannot record is still the caller's 403: fail-open, with an alert. */
    @Test
    void anAuthorizationRefusalFailsOpenWithAnAlert() {
        events.failWith(new IllegalStateException("insert refused"));

        trail.recordAccessDenied(ACTOR, new AuditRequest("GET", "/api/count", null));

        assertThat(alerts.raised).containsExactly(AuditOperation.ACCESS_DENIED);
    }

    /** On the SCIM chain the refused caller is a connector, named as actor, subject and resource. */
    @Test
    void aScimAuthorizationRefusalNamesTheConnectorTheOperationAndTheGenericReason() {
        trail.recordConnectorAccessDenied(
                GROUP, new AuditRequest("POST", "/scim/v2/Users", "req-scim-refused"));

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.ACCESS_DENIED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.actorId()).isEqualTo(GROUP);
        assertThat(event.subjectId()).isEqualTo(GROUP);
        assertThat(event.resourceId()).isEqualTo(GROUP);
        assertThat(event.resourceType()).isEqualTo(AuditEvent.CONNECTOR_RESOURCE_TYPE);
        assertThat(event.errorCode()).isEqualTo("INSUFFICIENT_PERMISSIONS");
        assertThat(event.statusClass()).isEqualTo("client_error");
        assertThat(event.httpMethod()).isEqualTo("POST");
        assertThat(event.httpPath()).isEqualTo("/scim/v2/Users");
        assertThat(event.requestId()).isEqualTo("req-scim-refused");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.id()).isNotNull();
        assertThat(event.permissions()).as("never the missing Permission").isEmpty();
        assertThat(event.changedPaths()).isEmpty();
    }

    @Test
    void aScimAuthorizationRefusalFailsOpenWithAnAlert() {
        events.failWith(new IllegalStateException("insert refused"));

        trail.recordConnectorAccessDenied(GROUP, new AuditRequest("GET", "/scim/v2/Users", null));

        assertThat(alerts.raised).containsExactly(AuditOperation.ACCESS_DENIED);
    }

    /** Issue and rotation name the Permissions granted, sorted by their wire spelling. */
    @Test
    void aTokenIssueAndRotationRecordThePermissionsGranted() {
        // An EnumSet iterates in declaration order (user:write first), not name order.
        trail.recordConnectorTokenIssued(ACTOR, GROUP,
                EnumSet.of(Permission.USER_WRITE, Permission.GROUP_READ));
        trail.recordConnectorTokenRotated(ACTOR, GROUP, Set.of(Permission.GROUP_WRITE));

        assertThat(events.appended).hasSize(2);
        AuditEvent issued = events.appended.get(0);
        assertThat(issued.operation()).isEqualTo(AuditOperation.CONNECTOR_TOKEN_ISSUE);
        assertThat(issued.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(issued.actorId()).isEqualTo(ACTOR);
        assertThat(issued.resourceId()).isEqualTo(GROUP);
        assertThat(issued.resourceType()).isEqualTo(AuditEvent.CONNECTOR_RESOURCE_TYPE);
        assertThat(issued.permissions()).containsExactly("group:read", "user:write");
        assertThat(issued.changedPaths()).containsExactly("permissions", "expiresAt");
        assertThat(issued.statusClass()).isEqualTo("ok");
        assertThat(issued.errorCode()).isNull();
        // Everything but the Permissions is the connector event's own: identity, time, request.
        assertThat(issued.id()).isNotNull();
        assertThat(issued.occurredAt()).isEqualTo(NOW);
        assertThat(issued.httpMethod()).isEqualTo("POST");
        assertThat(issued.httpPath()).isEqualTo("/api/admin/accounts/{id}/unlock");
        assertThat(issued.requestId()).isEqualTo("req-1");
        AuditEvent rotated = events.appended.get(1);
        assertThat(rotated.operation()).isEqualTo(AuditOperation.CONNECTOR_TOKEN_ROTATE);
        assertThat(rotated.permissions()).containsExactly("group:write");
        assertThat(rotated.changedPaths()).containsExactly("expiresAt", "replacedByTokenId");
    }

    /** A refused escalation names the connector and the Permissions requested, and changes nothing. */
    @Test
    void aRefusedEscalationRecordsThePermissionsRequested() {
        trail.recordConnectorTokenIssueRefused(ACTOR, GROUP,
                EnumSet.of(Permission.USER_WRITE, Permission.GROUP_READ));
        trail.recordConnectorTokenRotateRefused(ACTOR, GROUP, Set.of(Permission.USER_READ));

        assertThat(events.appended).hasSize(2);
        for (AuditEvent refused : events.appended) {
            assertThat(refused.outcome()).isEqualTo(AuditOutcome.FAILURE);
            assertThat(refused.actorId()).isEqualTo(ACTOR);
            assertThat(refused.subjectId()).isEqualTo(GROUP);
            assertThat(refused.resourceId()).isEqualTo(GROUP);
            assertThat(refused.resourceType()).isEqualTo(AuditEvent.CONNECTOR_RESOURCE_TYPE);
            assertThat(refused.errorCode()).isEqualTo("PERMISSION_ESCALATION");
            assertThat(refused.statusClass()).isEqualTo("client_error");
            assertThat(refused.changedPaths()).isEmpty();
        }
        assertThat(events.appended.get(0).operation())
                .isEqualTo(AuditOperation.CONNECTOR_TOKEN_ISSUE);
        assertThat(events.appended.get(0).permissions())
                .containsExactly("group:read", "user:write");
        assertThat(events.appended.get(1).operation())
                .isEqualTo(AuditOperation.CONNECTOR_TOKEN_ROTATE);
        assertThat(events.appended.get(1).permissions()).containsExactly("user:read");
    }

    /** The refusal is already the caller's 403: fail-open, with an alert, like every refusal. */
    @Test
    void aRefusedEscalationFailsOpenAndAGrantFailsClosed() {
        events.failWith(new IllegalStateException("insert refused"));

        trail.recordConnectorTokenIssueRefused(ACTOR, GROUP, Set.of(Permission.USER_READ));
        trail.recordConnectorTokenRotateRefused(ACTOR, GROUP, Set.of(Permission.USER_READ));
        assertThatThrownBy(() -> trail.recordConnectorTokenIssued(
                ACTOR, GROUP, Set.of(Permission.USER_READ)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(alerts.raised).containsExactly(
                AuditOperation.CONNECTOR_TOKEN_ISSUE, AuditOperation.CONNECTOR_TOKEN_ROTATE);
    }

    // Every recorder, field by field: an append removed or a field built wrong fails here

    /** The connector lifecycle events name the connector, the actor and what changed. */
    @Test
    void theConnectorLifecycleEventsNameTheConnectorAndWhatChanged() {
        trail.recordConnectorCreated(ACTOR, GROUP);
        trail.recordConnectorDeleted(ACTOR, GROUP);
        trail.recordConnectorTokenRevoked(null, GROUP);

        assertThat(events.appended).extracting(AuditEvent::operation).containsExactly(
                AuditOperation.CONNECTOR_CREATE, AuditOperation.CONNECTOR_DELETE,
                AuditOperation.CONNECTOR_TOKEN_REVOKE);
        assertThat(events.appended).extracting(AuditEvent::changedPaths).containsExactly(
                List.of(), List.of("deletedAt"), List.of("revokedAt"));
        assertThat(events.appended).extracting(AuditEvent::actorId)
                .containsExactly(ACTOR, ACTOR, null);
        assertThat(events.appended).allSatisfy(event -> {
            assertThat(event.subjectId()).isEqualTo(GROUP);
            assertThat(event.resourceId()).isEqualTo(GROUP);
            assertThat(event.resourceType()).isEqualTo(AuditEvent.CONNECTOR_RESOURCE_TYPE);
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(event.statusClass()).isEqualTo(AuditEvent.STATUS_OK);
            assertThat(event.errorCode()).isNull();
        });
    }

    @Test
    void aCreatedUserAndADeletedGroupAreEachOneSuccess() {
        trail.recordScimUserCreated(ACTOR, SUBJECT);
        trail.recordScimGroupDeleted(ACTOR, GROUP);

        assertThat(events.appended).hasSize(2);
        AuditEvent created = events.appended.get(0);
        assertThat(created.operation()).isEqualTo(AuditOperation.SCIM_USER_CREATE);
        assertThat(created.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(created.actorId()).isEqualTo(ACTOR);
        assertThat(created.resourceId()).isEqualTo(SUBJECT);
        assertThat(created.resourceType()).isEqualTo(AuditEvent.USER_RESOURCE_TYPE);
        AuditEvent deleted = events.appended.get(1);
        assertThat(deleted.operation()).isEqualTo(AuditOperation.SCIM_GROUP_DELETE);
        assertThat(deleted.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(deleted.resourceId()).isEqualTo(GROUP);
        assertThat(deleted.resourceType()).isEqualTo(AuditEvent.GROUP_RESOURCE_TYPE);
        assertThat(deleted.changedPaths()).containsExactly("members");
    }

    @Test
    void aRefusedUserWriteNamesTheUserAndTheRefusal() {
        trail.recordScimUserWriteRejected(ACTOR, SUBJECT, AuditScimRefusal.MUTABILITY);

        AuditEvent event = events.only();
        assertThat(event.operation()).isEqualTo(AuditOperation.SCIM_USER_REPLACE);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.actorId()).isEqualTo(ACTOR);
        assertThat(event.subjectId()).isEqualTo(SUBJECT);
        assertThat(event.statusClass()).isEqualTo(AuditEvent.STATUS_CLIENT_ERROR);
        assertThat(event.errorCode()).isEqualTo("MUTABILITY");
    }

    /**
     * A revocation names its causes in a stable order whatever order the set iterates in, and a
     * failed one is a server-side failure rather than a refusal of the caller.
     */
    @Test
    void aSessionRevocationNamesItsCausesInOrderAndClassifiesItsOutcome() {
        Set<AuditUserAttribute> causes = new LinkedHashSet<>(List.of(
                AuditUserAttribute.PASSWORD, AuditUserAttribute.ACTIVE, AuditUserAttribute.USER_NAME));
        trail.recordUserSessionsRevoked(ACTOR, SUBJECT, causes,
                Set.of(AuditSessionRevocationCause.PASSWORD_CHANGED), true);
        trail.recordUserSessionsRevoked(null, SUBJECT, Set.of(AuditUserAttribute.GROUPS),
                Set.of(AuditSessionRevocationCause.ROLE_REVOKED), false);

        AuditEvent revoked = events.appended.get(0);
        assertThat(revoked.operation()).isEqualTo(AuditOperation.USER_SESSIONS_REVOKE);
        assertThat(revoked.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(revoked.statusClass()).isEqualTo(AuditEvent.STATUS_OK);
        assertThat(revoked.actorId()).isEqualTo(ACTOR);
        assertThat(revoked.subjectId()).isEqualTo(SUBJECT);
        assertThat(revoked.changedPaths()).containsExactly("userName", "active", "password");
        AuditEvent failed = events.appended.get(1);
        assertThat(failed.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(failed.statusClass()).isEqualTo(AuditEvent.STATUS_SERVER_ERROR);
        assertThat(failed.actorId()).isNull();
        assertThat(failed.changedPaths()).containsExactly("groups");
    }

    /**
     * A revocation's reason is its causes, by their names and in a stable order whatever order
     * the set iterates in — whether it changed an attribute or not.
     */
    @Test
    void aSessionRevocationRecordsItsCausesAsItsReason() {
        trail.recordUserSessionsRevoked(ACTOR, SUBJECT,
                Set.of(AuditUserAttribute.USER_NAME, AuditUserAttribute.ACTIVE),
                new LinkedHashSet<>(List.of(AuditSessionRevocationCause.USER_NAME_CHANGED,
                        AuditSessionRevocationCause.DEACTIVATED)),
                true);
        trail.recordUserSessionsRevoked(null, SUBJECT, Set.of(),
                Set.of(AuditSessionRevocationCause.FAILURE_RUN_LOCKOUT), false);

        assertThat(events.appended).extracting(AuditEvent::errorCode)
                .containsExactly("DEACTIVATED,USER_NAME_CHANGED", "FAILURE_RUN_LOCKOUT");
    }

    /** D17: an Epic success records its MFA factor by its own spelling. */
    @Test
    void anEpicLoginSuccessRecordsItsMfaFactor() {
        trail.recordLoginSuccess(SUBJECT, AuditLoginMethod.SSO, AuditMfaFactor.OTP);

        assertThat(events.only().mfaFactor()).isEqualTo("otp");
    }

    /** A Group replacement names its moved attributes in a stable order, too. */
    @Test
    void aGroupReplacementNamesItsChangedPathsInOrderWhateverTheSetsOrder() {
        Set<AuditGroupAttribute> changed = new LinkedHashSet<>(List.of(
                AuditGroupAttribute.EXTERNAL_ID, AuditGroupAttribute.MEMBERS,
                AuditGroupAttribute.DISPLAY_NAME));

        trail.recordScimGroupReplaced(ACTOR, GROUP, changed);

        assertThat(events.only().changedPaths())
                .containsExactly("displayName", "members", "externalId");
    }

    /**
     * The dormancy job acts as nobody: no actor, the User as subject and resource, what it
     * changed — and the role revocation names every Role lost, sorted, distinct and comma-joined.
     */
    @Test
    void theDormancyJobRecordsNoActor() {
        trail.recordDormancyLockout(SUBJECT);
        trail.recordDormancyRoleRevocation(SUBJECT, List.of(
                new Role("Superuser", Set.of()), new Role("Account admin", Set.of()),
                new Role("Superuser", Set.of())));

        assertThat(events.appended).extracting(AuditEvent::operation).containsExactly(
                AuditOperation.DORMANCY_LOCKOUT, AuditOperation.DORMANCY_ROLE_REVOCATION);
        assertThat(events.appended).extracting(AuditEvent::changedPaths).containsExactly(
                List.of("lockedAt", "lockCause"), List.of("groups"));
        assertThat(events.appended).extracting(AuditEvent::role)
                .containsExactly(null, "Account admin,Superuser");
        assertThat(events.appended).allSatisfy(event -> {
            assertThat(event.id()).isNotNull();
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.actorId()).isNull();
            assertThat(event.subjectId()).isEqualTo(SUBJECT);
            assertThat(event.resourceId()).isEqualTo(SUBJECT);
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(event.resourceType()).isEqualTo(AuditEvent.USER_RESOURCE_TYPE);
            assertThat(event.statusClass()).isEqualTo(AuditEvent.STATUS_OK);
            assertThat(event.errorCode()).isNull();
            // Whatever request context is current is carried, as for every other event.
            assertThat(event.httpMethod()).isEqualTo("POST");
            assertThat(event.httpPath()).isEqualTo("/api/admin/accounts/{id}/unlock");
            assertThat(event.requestId()).isEqualTo("req-1");
            assertThat(event.resultCount()).isNull();
            assertThat(event.filterShape()).isNull();
        });
        assertThat(events.appended.get(0).id()).isNotEqualTo(events.appended.get(1).id());
    }

    @Test
    void aDormancyRoleRevocationNamingNoRoleIsRefused() {
        assertThatThrownBy(() -> trail.recordDormancyRoleRevocation(SUBJECT, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events.appended).isEmpty();
    }

    /** Both dormancy events are fail-closed: the job's change rolls back with a lost append. */
    @Test
    void theDormancyEventsAreFailClosed() {
        events.failWith(new IllegalStateException("insert refused"));

        assertThatThrownBy(() -> trail.recordDormancyLockout(SUBJECT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordDormancyRoleRevocation(
                        SUBJECT, List.of(new Role("Superuser", Set.of()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passwordEventsNameTheCredentialPathsOrTheRefusal() {
        trail.recordPasswordChanged(SUBJECT);
        trail.recordPasswordChangeRefused(SUBJECT, AuditPasswordChangeRefusal.TOO_SHORT);
        trail.recordPasswordChangeRequirementRefused(
                ACTOR, SUBJECT, AuditAdministrativeRefusal.SELF_TARGET);

        AuditEvent changed = events.appended.get(0);
        assertThat(changed.operation()).isEqualTo(AuditOperation.PASSWORD_CHANGE);
        assertThat(changed.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(changed.actorId()).isEqualTo(SUBJECT);
        assertThat(changed.subjectId()).isEqualTo(SUBJECT);
        assertThat(changed.changedPaths())
                .containsExactly("password", "passwordChangeRequiredSince");
        AuditEvent refused = events.appended.get(1);
        assertThat(refused.operation()).isEqualTo(AuditOperation.PASSWORD_CHANGE);
        assertThat(refused.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(refused.actorId()).isEqualTo(SUBJECT);
        assertThat(refused.errorCode()).isEqualTo("TOO_SHORT");
        assertThat(refused.statusClass()).isEqualTo(AuditEvent.STATUS_CLIENT_ERROR);
        AuditEvent requirement = events.appended.get(2);
        assertThat(requirement.operation()).isEqualTo(AuditOperation.PASSWORD_CHANGE_REQUIRE);
        assertThat(requirement.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(requirement.actorId()).isEqualTo(ACTOR);
        assertThat(requirement.subjectId()).isEqualTo(SUBJECT);
        assertThat(requirement.errorCode()).isEqualTo("SELF_TARGET");
    }

    /** A Role change names the User as subject, the Group as resource, and the Role by name. */
    @Test
    void aRoleChangeNamesTheUserTheGroupAndTheRole() {
        Role role = new Role("Account admin", Set.of(Permission.USER_READ));
        trail.recordRoleGranted(ACTOR, SUBJECT, GROUP, role);
        trail.recordRoleRevoked(ACTOR, SUBJECT, GROUP, role);

        assertThat(events.appended).extracting(AuditEvent::operation)
                .containsExactly(AuditOperation.ROLE_GRANT, AuditOperation.ROLE_REVOKE);
        assertThat(events.appended).allSatisfy(event -> {
            assertThat(event.id()).isNotNull();
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(event.actorId()).isEqualTo(ACTOR);
            assertThat(event.subjectId()).isEqualTo(SUBJECT);
            assertThat(event.resourceId()).isEqualTo(GROUP);
            assertThat(event.resourceType()).isEqualTo(AuditEvent.GROUP_RESOURCE_TYPE);
            assertThat(event.changedPaths()).containsExactly("members");
            assertThat(event.role()).isEqualTo("Account admin");
            assertThat(event.httpMethod()).isEqualTo("POST");
            assertThat(event.httpPath()).isEqualTo("/api/admin/accounts/{id}/unlock");
            assertThat(event.requestId()).isEqualTo("req-1");
        });
    }

    // The two failure semantics

    @Test
    void aFailClosedAppendPropagatesSoTheMutationRollsBack() {
        events.failWith(new IllegalStateException("insert refused"));

        assertThatThrownBy(() -> trail.recordPasswordChangeRequired(ACTOR, SUBJECT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordLoginSuccess(SUBJECT, AuditLoginMethod.PASSWORD))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordLogout(SUBJECT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordLockoutLiftedByUnlock(ACTOR, SUBJECT, AuditLockCause.FAILURES))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordScimGroupCreated(ACTOR, GROUP))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordScimGroupReplaced(
                        ACTOR, GROUP, Set.of(AuditGroupAttribute.MEMBERS)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordScimGroupDeleted(ACTOR, GROUP))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordScimGroupsQueried(ACTOR, 0, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordScimUsersQueried(ACTOR, 0, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordScimResourcesQueried(ACTOR, 0, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> trail.recordReservedResourceSeeded(SUBJECT, false))
                .isInstanceOf(IllegalStateException.class);

        assertThat(alerts.raised).isEmpty();
    }

    @Test
    void aFailOpenAppendRaisesAnAlertAndDoesNotPropagate() {
        events.failWith(new IllegalStateException("insert refused"));

        trail.recordLoginFailure(SUBJECT, AuditRefusalReason.BAD_CREDENTIALS, AuditLoginMethod.PASSWORD);
        trail.recordLockoutSet(SUBJECT);
        trail.recordScimUserCreateRejected(ACTOR, AuditScimRefusal.UNIQUENESS);
        trail.recordUnlockRefused(ACTOR, SUBJECT, AuditAdministrativeRefusal.SELF_TARGET);
        trail.recordScimGroupCreateRejected(ACTOR, AuditScimRefusal.UNIQUENESS);
        trail.recordScimGroupWriteRejected(ACTOR, GROUP, AuditScimRefusal.MUTABILITY);

        assertThat(alerts.raised).containsExactly(
                AuditOperation.LOGIN_FAILURE,
                AuditOperation.LOCKOUT_SET,
                AuditOperation.SCIM_USER_CREATE,
                AuditOperation.LOCKOUT_LIFT,
                AuditOperation.SCIM_GROUP_CREATE,
                AuditOperation.SCIM_GROUP_REPLACE);
    }

    @Test
    void anAlertNamesTheFailuresOwnTypeAndNothingElse() {
        events.failWith(new IllegalArgumentException("would name the submitted value"));

        trail.recordLoginFailure(SUBJECT, AuditRefusalReason.BAD_CREDENTIALS, AuditLoginMethod.PASSWORD);

        assertThat(alerts.failures).containsExactly(IllegalArgumentException.class);
    }

    private static final class RecordingRepository implements AuditEventRepository {

        private final List<AuditEvent> appended = new ArrayList<>();
        private RuntimeException failure;

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        AuditEvent only() {
            assertThat(appended).hasSize(1);
            return appended.get(0);
        }

        @Override
        public void append(AuditEvent event) {
            if (failure != null) {
                throw failure;
            }
            appended.add(event);
        }
    }

    private static final class RecordingAlerts implements OperationalAlerts {

        private final List<AuditOperation> raised = new ArrayList<>();
        private final List<Class<? extends Throwable>> failures = new ArrayList<>();

        @Override
        public void auditAppendFailed(
                AuditOperation operation, Class<? extends Throwable> failure) {
            raised.add(operation);
            failures.add(failure);
        }

        @Override
        public void sessionRevocationFailed(Class<? extends Throwable> failure) {
            throw new UnsupportedOperationException("the audit trail revokes no session");
        }
    }

    /**
     * A transaction manager that starts and ends nothing. The service uses a
     * {@link org.springframework.transaction.support.TransactionTemplate} to run a
     * fail-open append in its own transaction; what this test is about is whether
     * the exception escapes, and a real manager would only add a database.
     */
    /**
     * A fail-open append runs in a transaction of its own, not the caller's.
     *
     * <p>The behavioural claim — the row outlives a caller that rolls back — is
     * asserted against real Postgres in
     * {@code AuditAppendOnlyIntegrationTests.aFailOpenAppendCommitsEvenWhenTheCallersTransactionRollsBack}.
     * That test cannot reach this constructor under mutation testing: the service is
     * a singleton built once while the Spring context boots, so PIT attributes the
     * constructor's coverage to whichever test method happened to trigger the boot
     * and runs only that one. Hence this unit-level assertion on the propagation the
     * template actually asks for — the only form in which the wiring is visible to a
     * mutation of the constructor.
     */
    @Test
    void aFailOpenAppendAsksForATransactionOfItsOwn() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        AuditTrail isolated = new AuditTrailService(
                events, requests, alerts, Clock.fixed(NOW, ZoneOffset.UTC), transactions);

        isolated.recordLoginFailure(SUBJECT, AuditRefusalReason.BAD_CREDENTIALS, AuditLoginMethod.PASSWORD);

        assertThat(transactions.definitions)
                .as("the fail-open append's transaction definitions")
                .singleElement()
                .satisfies(definition -> assertThat(definition.getPropagationBehavior())
                        .as("PROPAGATION_REQUIRES_NEW, so the caller's rollback cannot "
                                + "take the audit row with it")
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    /** Records the transaction definitions it is asked for, and does nothing else. */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final List<TransactionDefinition> definitions = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            definitions.add(definition);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private static final PlatformTransactionManager NO_TRANSACTION_MANAGER =
            new PlatformTransactionManager() {

                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                }

                @Override
                public void rollback(TransactionStatus status) {
                }
            };
}
