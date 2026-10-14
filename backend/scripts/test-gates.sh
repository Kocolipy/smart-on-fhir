#!/usr/bin/env bash
# Layered test gates for the backend. These are inner-loop checks: the baseline
# gate, ./scripts/verify.sh, still decides whether a change is complete.
# bash 3.2 compatible: no associative arrays, and the empty-array guard below
# is required under set -u.
#
#   scripts/test-gates.sh fast                 explicit class list, no Docker, zero known failures
#   scripts/test-gates.sh subsystem <name>     one test package, Spring contexts and containers included
#   scripts/test-gates.sh subsystems           every subsystem in turn
#   scripts/test-gates.sh broad                the whole suite (`test`, no clean, no Semgrep)
#   scripts/test-gates.sh deprecated           the parked-test archive, if one exists
#
# Live layers are documented in README.md ("Test layers") and never run here.
#
# Runs from anywhere; it cd's to the app root itself.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# The checked-in wrapper, never a bare `mvn` (see verify.sh).
MVN=${MVN:-./mvnw}

# Explicit list: every test class whose source names none of @SpringBootTest,
# ContainerTestConfiguration, Testcontainers, GenericContainer or
# PostgreSQLContainer, so none starts a Spring context or a container. Add a
# class only if it is that and needs no environment value; add it to nothing
# else, since the subsystem gates select by package.
FAST_GATE=(
  arch/ArchitectureTest
  arch/NoRoleSwitchTest
  arch/TestSourceArchitectureTest
  com/example/backend/audit/application/AuditEventListingServiceTests
  com/example/backend/audit/application/AuditRetentionServiceTests
  com/example/backend/audit/application/AuditTrailServiceTests
  com/example/backend/audit/config/AuditAppendPoolConfigTests
  com/example/backend/audit/config/AuditRetentionRunLogTests
  com/example/backend/audit/config/AuditRetentionStartupTests
  com/example/backend/audit/controller/AdminAuditControllerTests
  com/example/backend/audit/domain/AuditEventPageTests
  com/example/backend/audit/domain/AuditEventQueryTests
  com/example/backend/audit/domain/AuditRetentionPolicyTests
  com/example/backend/audit/infrastructure/alert/LoggingOperationalAlertsAdapterTests
  com/example/backend/audit/infrastructure/persistence/IsolatedAuditEventPersistenceAdapterTests
  com/example/backend/audit/infrastructure/request/HttpAuditRequestContextAdapterTests
  com/example/backend/auth/InMemoryAccountSessionsContractTests
  com/example/backend/auth/application/DormancyServiceTests
  com/example/backend/auth/application/EpicLoginDecisionTests
  com/example/backend/auth/application/IdentityAdministrationServiceTests
  com/example/backend/auth/application/LoginAttemptServiceTests
  com/example/backend/auth/application/LoginIdentityServiceTests
  com/example/backend/auth/application/LoginLockoutTests
  com/example/backend/auth/application/LoginOutcomeServiceTests
  com/example/backend/auth/application/LoginStateServiceTests
  com/example/backend/auth/application/PasswordChangeServiceTests
  com/example/backend/auth/application/PasswordLoginEdgeWhitespaceTests
  com/example/backend/auth/application/PasswordLoginRenameTests
  com/example/backend/auth/application/PasswordRefusalReasonTests
  com/example/backend/auth/application/RefusalTimingEquivalenceTests
  com/example/backend/auth/application/RoleListingServiceTests
  com/example/backend/auth/application/RoleMappingSessionServiceTests
  com/example/backend/auth/application/SelfReadServiceTests
  com/example/backend/auth/application/SessionRevocationServiceTests
  com/example/backend/auth/config/ApplicationChainRefusalTests
  com/example/backend/auth/config/DevDormancyStartupConfigTests
  com/example/backend/auth/config/DormancyPolicyStartupTests
  com/example/backend/auth/config/DormancyScheduleConfigTests
  com/example/backend/auth/config/LoginLockoutConfigTests
  com/example/backend/auth/config/LoginMetricsTests
  com/example/backend/auth/config/SecurityConfigPasswordEncoderTests
  com/example/backend/auth/controller/AbsoluteSessionLifetimeFilterTests
  com/example/backend/auth/controller/AdminAccountControllerTests
  com/example/backend/auth/controller/AuthControllerTests
  com/example/backend/auth/controller/HttpSessionAttributesTests
  com/example/backend/auth/controller/LoginCompletionTests
  com/example/backend/auth/controller/SelfControllerTests
  com/example/backend/auth/controller/SessionEndingTests
  com/example/backend/auth/controller/SessionEstablishmentTests
  com/example/backend/auth/controller/SessionUserLogContextFilterTests
  com/example/backend/auth/domain/AbsoluteSessionLifetimePolicyTests
  com/example/backend/auth/domain/EpicInputBoundsTests
  com/example/backend/auth/domain/EpicMfaEvidenceTests
  com/example/backend/auth/domain/EpicTokenSetTests
  com/example/backend/auth/domain/SignedInSessionTests
  com/example/backend/auth/epic/EcP384PrivateKeyPemTests
  com/example/backend/auth/epic/EnvironmentKeyClientAssertionSignerTests
  com/example/backend/auth/epic/EpicJwksTests
  com/example/backend/auth/epic/EpicLoginPropertiesTests
  com/example/backend/auth/epic/EpicLoginTests
  com/example/backend/auth/epic/EpicProviderMetadataTests
  com/example/backend/auth/epic/EpicSigningKeyTests
  com/example/backend/auth/epic/FhirUserReferenceTests
  com/example/backend/auth/epic/config/EpicCallMetricsTests
  com/example/backend/auth/epic/config/EpicIdTokenChecksTests
  com/example/backend/auth/epic/config/EpicIdentityClaimsTests
  com/example/backend/auth/epic/config/EpicLoginConfigTests
  com/example/backend/auth/epic/config/EpicReleaseGateFilterTests
  com/example/backend/auth/epic/controller/EpicTokenCaptureTests
  com/example/backend/auth/infrastructure/session/AccountSessionsAdapterTests
  com/example/backend/auth/infrastructure/session/EpicTokensAdapterTests
  com/example/backend/auth/infrastructure/session/SpringSessionAttributesTests
  com/example/backend/auth/infrastructure/transaction/AfterCommitAdapterTests
  com/example/backend/authorization/config/RoleMappingStartupTests
  com/example/backend/authorization/domain/RoleMappingTests
  com/example/backend/contract/OpenApiContractTests
  com/example/backend/counter/controller/UserCounterControllerTests
  com/example/backend/counter/domain/UserCounterTests
  com/example/backend/database/config/MigrationLoginNamesTests
  com/example/backend/database/config/MigrationProfilesTests
  com/example/backend/lifecycle/config/ApplicationLifecycleLogTests
  com/example/backend/observability/AccessRefusalLogTests
  com/example/backend/observability/DatabaseFailureCategoryTests
  com/example/backend/observability/EcsErrorFieldsCustomizerTests
  com/example/backend/observability/EcsTimestampCustomizerTests
  com/example/backend/observability/FeatureLogFieldsTests
  com/example/backend/observability/LogContextTests
  com/example/backend/observability/LogEventTests
  com/example/backend/observability/LogFileTests
  com/example/backend/observability/LogRecordGuardTests
  com/example/backend/observability/MetricTagTests
  com/example/backend/observability/OperationMessagesTests
  com/example/backend/observability/RequestIdFilterTests
  com/example/backend/observability/ScheduledJobMetricsTests
  com/example/backend/observability/ScheduledJobRegistrationTests
  com/example/backend/observability/ScimRequestObservationConventionTests
  com/example/backend/observability/SessionHashTests
  com/example/backend/observability/config/TraceLogCorrelationConfigTests
  com/example/backend/observability/infrastructure/transaction/DeadlockRetryAdapterTests
  com/example/backend/scheduling/application/ScheduledJobRunServiceTests
  com/example/backend/scim/application/ConnectorAdministrationServiceTests
  com/example/backend/scim/application/ConnectorAuthenticationServiceTests
  com/example/backend/scim/application/ScimAuditFilterShapesTests
  com/example/backend/scim/application/ScimGroupServiceTests
  com/example/backend/scim/application/ScimSearchServiceTests
  com/example/backend/scim/application/ScimSeedServiceTests
  com/example/backend/scim/application/ScimUserReplacementTests
  com/example/backend/scim/application/ScimUserServiceTests
  com/example/backend/scim/application/ScimWriteEffectsApplierTests
  com/example/backend/scim/application/ScimWriteEffectsTests
  com/example/backend/scim/config/ScimBearerAuthenticationFilterTests
  com/example/backend/scim/config/ScimDispatcherErrorFilterTests
  com/example/backend/scim/config/ScimErrorDocumentErrorFieldsTests
  com/example/backend/scim/config/ScimInsufficientPermissionsTests
  com/example/backend/scim/config/ScimPasswordAcceptanceConfigTests
  com/example/backend/scim/config/ScimRequestBodyLimitFilterTests
  com/example/backend/scim/config/ScimSeedConfigStartupTests
  com/example/backend/scim/controller/ScimAttributeProjectionTests
  com/example/backend/scim/controller/ScimDiscoveryTests
  com/example/backend/scim/controller/ScimExceptionHandlerBodyTests
  com/example/backend/scim/controller/ScimExceptionHandlerErrorFieldsTests
  com/example/backend/scim/controller/ScimExceptionHandlerLogTests
  com/example/backend/scim/controller/ScimExceptionHandlerMetricTests
  com/example/backend/scim/controller/ScimGroupRequestReaderTests
  com/example/backend/scim/controller/ScimIfMatchTests
  com/example/backend/scim/controller/ScimPatchPathGrammarTests
  com/example/backend/scim/controller/ScimQueryRequestTests
  com/example/backend/scim/controller/ScimQueryVocabularyTests
  com/example/backend/scim/controller/ScimRefusalAuditTests
  com/example/backend/scim/controller/ScimSchemaDocumentsTests
  com/example/backend/scim/controller/ScimUserPatchReaderTests
  com/example/backend/scim/controller/ScimUserRequestReaderTests
  com/example/backend/scim/domain/ConnectorTokenPermissionsTests
  com/example/backend/scim/domain/ConnectorTokenPolicyTests
  com/example/backend/scim/domain/ConnectorTokenSecretTests
  com/example/backend/scim/domain/DormancyPolicyTests
  com/example/backend/scim/domain/DormancyVerdictTests
  com/example/backend/scim/domain/EdgeWhitespaceTests
  com/example/backend/scim/domain/LockoutPolicyTests
  com/example/backend/scim/domain/LockoutTests
  com/example/backend/scim/domain/NormalizedDisplayNameTests
  com/example/backend/scim/domain/NormalizedUserNameTests
  com/example/backend/scim/domain/PasswordAcceptanceTests
  com/example/backend/scim/domain/PasswordNormalizationTests
  com/example/backend/scim/domain/PasswordPolicyTests
  com/example/backend/scim/domain/ReservedResourceNameTests
  com/example/backend/scim/domain/ScimAttributeLimitsTests
  com/example/backend/scim/domain/ScimConnectorTokenTests
  com/example/backend/scim/domain/ScimEmailTests
  com/example/backend/scim/domain/ScimFilterParserTests
  com/example/backend/scim/domain/ScimGroupTests
  com/example/backend/scim/domain/ScimLoginStateTests
  com/example/backend/scim/domain/ScimPageRequestTests
  com/example/backend/scim/domain/ScimPermissionRuleTests
  com/example/backend/scim/domain/ScimQueryTests
  com/example/backend/scim/domain/ScimResourceSchemaTests
  com/example/backend/scim/domain/ScimSortTests
  com/example/backend/scim/domain/ScimUserLoginStateCredentialTests
  com/example/backend/scim/domain/ScimUserPatchOperationTests
  com/example/backend/scim/domain/ScimUserProfileTests
  com/example/backend/scim/domain/ScimUserTests
  com/example/backend/scim/domain/ScimVersionPreconditionTests
  com/example/backend/scim/infrastructure/persistence/IntegrityViolationsTests
  com/example/backend/scim/infrastructure/persistence/RepresentationChangeTests
  com/example/backend/scim/infrastructure/persistence/ScimQuerySqlTests
  com/example/backend/scim/infrastructure/persistence/entity/CompositeKeyContractTests
  com/example/backend/web/ApiExceptionHandlerTests
  com/example/backend/web/ReadRequestsTests
  com/example/backend/web/SpaRoutesScimNamespaceTests
  com/example/backend/web/SpaRoutesTests
)

# One test package per owning boundary. Names become CLI arguments.
subsystem_pattern() {
  case "$1" in
    arch) echo 'arch/**' ;;
    auth|scim|audit|authorization|contract|observability|counter|database|lifecycle|scheduling|web)
      echo "com/example/backend/$1/**" ;;
    *) return 1 ;;
  esac
}
SUBSYSTEM_NAMES="arch auth scim audit authorization contract observability counter database lifecycle scheduling web"

# No archive exists today: nothing was ever parked. If one is created, fence it
# out of src/test so Surefire cannot collect it.
ARCHIVE_DIR="src/test-archive"

usage() {
  echo "usage: $0 fast | subsystem <${SUBSYSTEM_NAMES// /|}> | subsystems | broad | deprecated" >&2
  exit 2
}

run_tests() {
  "$MVN" -B test -Dtest="$1" -Dsurefire.failIfNoSpecifiedTests=false
}

[ $# -ge 1 ] || usage
mode="$1"; shift || true

case "$mode" in
  fast)
    echo "== fast gate (${#FAST_GATE[@]} classes) =="
    joined=$(printf '%s,' "${FAST_GATE[@]+"${FAST_GATE[@]}"}")
    joined="${joined%,}"
    log=$(mktemp "${TMPDIR:-/tmp}/fast-gate.XXXXXX")
    status=0
    run_tests "$joined" 2>&1 | tee "$log" || status=$?
    # The list's promise: no Spring context and no container. A class that
    # breaks it belongs in a subsystem gate, not here.
    if grep -qE 'Creating container|Started [A-Za-z]+ in .* seconds' "$log"; then
      echo "fast gate started a Spring context or a container; remove the offending class from FAST_GATE" >&2
      status=1
    fi
    rm -f "$log"
    exit "$status" ;;
  subsystem)
    [ $# -eq 1 ] || usage
    pattern="$(subsystem_pattern "$1")" || { echo "unknown subsystem: $1" >&2; usage; }
    echo "== subsystem gate: $1 =="
    run_tests "$pattern" ;;
  subsystems)
    for name in $SUBSYSTEM_NAMES; do
      echo "== subsystem gate: $name =="
      run_tests "$(subsystem_pattern "$name")"
    done ;;
  broad)
    echo "== broad gate =="
    "$MVN" -B test ;;
  deprecated)
    if [ -d "$ARCHIVE_DIR" ]; then
      echo "== deprecated archive: nothing runs it; read $ARCHIVE_DIR/README.md =="
    else
      echo "no $ARCHIVE_DIR directory; nothing to run"
    fi ;;
  *)
    usage ;;
esac
