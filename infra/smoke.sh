#!/bin/bash
# Post-deploy smoke of a running stack. Exits non-zero on the first failed check:
#
#   1. health        /actuator/health through the ALB answers 200
#   2. TLS           a plaintext database session is refused for requiring TLS,
#                    and a verify-full session negotiates TLS 1.2 or higher
#   3. SCIM          as the Bootstrap Admin, a SCIM create, list and delete
#   4. alarms        the deadlock and blocked-session alarms exist and notify
#                    the stack's alarm topic
#   5. owner login   the running service's environment holds no part of the
#                    owner secret: only the migrate step is given it
#   6. secret leaks  no secret value appears in the rendered UserData (read back
#                    from EC2), the cloud-init logs, the service's and the migrate
#                    step's env files and journals, or the service's log file
#
# Runs on the operator's machine with the operator's AWS credentials, through
# the same SSH tunnel as db-bootstrap.sh. On a fresh stack the SCIM step
# replaces the Bootstrap Admin's seeded password and writes the new one back to
# its secret (AppBootstrapSecretArn), staged as AWSPENDING until the change
# has succeeded. See infra/README.md, "Post-deploy smoke".
#
# Usage: ./smoke.sh [--leaks-only] <stack-name> [region]
#   --leaks-only  step 6 alone, over the sources that exist before the JAR is
#                 deployed: the rendered UserData, the cloud-init logs and the
#                 env files. deploy.sh runs this when the JAR is not deployed.
#   KEY_FILE  the instance's SSH key (default: <KeyPairName>.pem beside this script)
#
# Never run with command tracing (bash -x): see lib.sh.
set -euo pipefail

# shellcheck source=lib.sh source-path=SCRIPTDIR
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

REGION=""
ADMIN_SECRET_ARN=""
ADMIN_PENDING_VERSION=""

# --- the Bootstrap Admin's password hooks scim_smoke calls --------------------

new_admin_password() {
  openssl rand -base64 33 | tr -d '\n'
}

# The new password becomes a new version of the secret, labelled AWSPENDING:
# readable, but not what the instance starts with, until the change succeeds.
stage_admin_password() {
  ADMIN_PENDING_VERSION=$(printf '%s' "$1" |
    jq -Rsc --arg u "$ADMIN_USERNAME" '{username: $u, password: .}' |
    aws secretsmanager put-secret-value --secret-id "$ADMIN_SECRET_ARN" --region "$REGION" \
      --secret-string file:///dev/stdin --version-stages AWSPENDING \
      --query VersionId --output text) ||
    die "cannot stage the Bootstrap Admin's new password in its secret; nothing was changed"
}

commit_admin_password() {
  local current
  if ! current=$(aws secretsmanager describe-secret --secret-id "$ADMIN_SECRET_ARN" --region "$REGION" \
    --query VersionIdsToStages --output json |
    jq -er 'to_entries[] | select(.value | index("AWSCURRENT")) | .key') ||
    ! aws secretsmanager update-secret-version-stage --secret-id "$ADMIN_SECRET_ARN" --region "$REGION" \
      --version-stage AWSCURRENT --move-to-version-id "$ADMIN_PENDING_VERSION" \
      --remove-from-version-id "$current" >/dev/null; then
    die "the Bootstrap Admin's password WAS changed, but promoting it in its secret failed. The new
       password is version $ADMIN_PENDING_VERSION (AWSPENDING) of $ADMIN_SECRET_ARN; promote it with
       aws secretsmanager update-secret-version-stage --version-stage AWSCURRENT --move-to-version-id ..."
  fi
  aws secretsmanager update-secret-version-stage --secret-id "$ADMIN_SECRET_ARN" --region "$REGION" \
    --version-stage AWSPENDING --remove-from-version-id "$ADMIN_PENDING_VERSION" >/dev/null ||
    print_warn "the new password is current, but its version still carries the AWSPENDING label"
  print_info "the Bootstrap Admin's new password is now the current version of its secret"
}

# --- checks ---------------------------------------------------------------------

check_health() {
  local url=$1 waited=0
  until [[ $(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url/actuator/health") == 200 ]]; do
    ((waited < 300)) || die "health: $url/actuator/health did not answer 200 within 300 s"
    sleep 10
    waited=$((waited + 10))
  done
  print_info "health: $url/actuator/health answers 200"
}

check_alarms() {
  local topic actions name
  topic=$(stack_output AlarmTopicArn)
  for name in "$(stack_output DBDeadlocksAlarmName)" "$(stack_output DBBlockedSessionsAlarmName)"; do
    actions=$(aws cloudwatch describe-alarms --alarm-names "$name" --region "$REGION" \
      --query 'MetricAlarms[0].AlarmActions' --output json)
    [[ $(printf '%s' "$actions" | jq -r --arg t "$topic" 'index($t) != null') == true ]] ||
      die "alarms: $name is missing, or does not notify $topic"
    print_info "alarms: $name notifies the stack's alarm topic"
  done
  # shellcheck disable=SC2016 # the backticks are a JMESPath literal, not a shell expansion
  [[ $(aws sns list-subscriptions-by-topic --topic-arn "$topic" --region "$REGION" \
    --query 'length(Subscriptions[?SubscriptionArn != `PendingConfirmation`])' --output text) != 0 ]] ||
    print_warn "alarms: nobody is subscribed to $topic yet (AlarmEmail, or confirm the email subscription)"
}

# The values to look for: every database password (the master's included, which
# must never reach the instance at all), the Bootstrap Admin's current and
# previous password, and the Epic client credentials.
collect_secret_values() {
  local json arn name stage
  SECRET_VALUES=()
  for name in DBMasterSecretArn DBOwnerSecretArn DBAppSecretArn DBRetentionSecretArn; do
    json=$(secret_string "$(stack_output "$name")" "$REGION")
    SECRET_VALUES+=("$name.password=$(json_field "$json" password)")
  done
  for stage in AWSCURRENT AWSPREVIOUS; do
    json=$(secret_string "$ADMIN_SECRET_ARN" "$REGION" "$stage" 2>/dev/null) || continue
    SECRET_VALUES+=("AppBootstrapSecretArn.password($stage)=$(json_field "$json" password)")
  done
  arn=$(stack_output EpicClientSecretArn)
  json=$(secret_string "$arn" "$REGION")
  for name in clientId clientKey clientNextKey; do
    SECRET_VALUES+=("EpicClientSecretArn.$name=$(printf '%s' "$json" | jq -r --arg f "$name" '.[$f] // ""')")
  done
}

remote() { # <command>: run on the instance as ec2-user, stdout to us
  local key_file
  key_file=${KEY_FILE:-$INFRA_DIR/$(stack_output KeyPairName).pem}
  ssh -i "$key_file" -o StrictHostKeyChecking=accept-new -o BatchMode=yes -o ConnectTimeout=15 \
    "ec2-user@$(stack_output EC2PublicIP)" "$1"
}

# check_secret_leaks [boot-only]: with boot-only, only the sources the boot
# writes; the service's log file and journal do not exist before it runs.
check_secret_leaks() {
  local boot_only=${1:-} instance_id source
  collect_secret_values
  instance_id=$(stack_output EC2InstanceId)
  aws ec2 describe-instance-attribute --instance-id "$instance_id" --attribute userData \
    --region "$REGION" --query UserData.Value --output text | base64 --decode |
    scan_for_secrets "the rendered UserData" "${SECRET_VALUES[@]}"
  for source in /var/log/cloud-init-output.log /var/log/cloud-init.log /opt/backend/.env \
    /opt/backend/migrate.env; do
    remote "sudo cat $source" | scan_for_secrets "$source" "${SECRET_VALUES[@]}"
  done
  if [[ -z $boot_only ]]; then
    remote "sudo cat /var/log/backend/backend.json" |
      scan_for_secrets /var/log/backend/backend.json "${SECRET_VALUES[@]}"
    remote "sudo journalctl -u backend --no-pager" |
      scan_for_secrets "the backend service's journal" "${SECRET_VALUES[@]}"
    remote "sudo journalctl -u backend-migrate --no-pager" |
      scan_for_secrets "the migrate step's journal" "${SECRET_VALUES[@]}"
  fi
  SECRET_VALUES=()
}

# The running service holds no owner credential (ADR 0015, ac-1): its JVM's own
# environment, read from /proc on the instance, names no DATABASE_OWNER_*
# variable and contains no part of the owner secret — its ARN or its password.
# Only the migrate step (backend-migrate.service) is given those. The
# environment holds the app and retention passwords, so it is held in a local
# variable for the check and never printed.
check_service_holds_no_owner_credential() {
  local json environ owner_arn
  owner_arn=$(stack_output DBOwnerSecretArn)
  json=$(secret_string "$owner_arn" "$REGION")
  # shellcheck disable=SC2016 # expanded on the instance, not here
  environ=$(remote 'sudo cat "/proc/$(systemctl show -p MainPID --value backend)/environ" | tr "\0" "\n"')
  [[ -n $environ ]] || die "owner credential check: the backend service's environment could not be read"
  if grep -q '^DATABASE_OWNER_' <<<"$environ"; then
    die "owner credential check: the backend service's environment has a DATABASE_OWNER_ variable"
  fi
  printf '%s' "$environ" | scan_for_secrets "the backend service's environment" \
    "DBOwnerSecretArn=$owner_arn" "DBOwnerSecretArn.password=$(json_field "$json" password)"
  environ=""
  json=""
  print_info "owner credential check: the service runs without the owner login"
}

main() {
  local leaks_only="" stack json cmd
  if [[ ${1:-} == --leaks-only ]]; then
    leaks_only=yes
    shift
  fi
  stack=${1:-}
  REGION=${2:-ap-southeast-1}
  [[ -n $stack ]] || die "usage: $0 [--leaks-only] <stack-name> [region]"
  for cmd in aws jq ssh base64; do require_cmd "$cmd"; done

  load_stack_outputs "$stack" "$REGION"
  ADMIN_SECRET_ARN=$(stack_output AppBootstrapSecretArn)

  if [[ -n $leaks_only ]]; then
    check_secret_leaks boot-only
    print_info "secret-leak check passed on stack $stack (boot sources only: the service is not deployed)"
    return 0
  fi

  for cmd in curl openssl; do require_cmd "$cmd"; done
  require_cmd psql "the PostgreSQL client (any supported version)"

  check_health "$(stack_output LoadBalancerURL)"

  json=$(secret_string "$(stack_output DBAppSecretArn)" "$REGION")
  trap close_stack_db_tunnel EXIT
  open_stack_db_tunnel "$REGION"
  check_tls_enforced "$(json_field "$json" username)" "$(json_field "$json" password)"
  close_stack_db_tunnel

  json=$(secret_string "$ADMIN_SECRET_ARN" "$REGION")
  ADMIN_USERNAME=$(json_field "$json" username)
  ADMIN_PASSWORD=$(json_field "$json" password)
  json=""
  scim_smoke "$(stack_output LoadBalancerURL)"
  unset ADMIN_PASSWORD

  check_alarms
  check_service_holds_no_owner_credential
  check_secret_leaks
  print_info "smoke passed on stack $stack"
}

main "$@"
