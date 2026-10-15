#!/bin/bash
# Post-deploy smoke of a running stack. Exits non-zero on the first failed check:
#
#   1. health        /actuator/health through the ALB answers 200
#      JVM trust     the service's JVM keeps the JDK's default trust store, and
#                    that JDK fetches the identity provider's discovery
#                    document and JWKS (Epic's, when Epic Login is on) over it
#   2. TLS          the parameter group forces encryption with TLS 1.0 and 1.1
#                    off; on the instance, a client that cannot do TLS is
#                    refused (tds-plaintext-probe.py), a session asking for no
#                    encryption is encrypted anyway, one that cannot validate
#                    RDS's certificate is refused, and one that validates it
#                    connects
#   3. SCIM          as the Bootstrap Admin, a SCIM create, list and delete, and
#                    no development fixture account (the userNames
#                    authorization.yaml gives them)
#   4. alarms        the deadlock and blocked-session alarms exist, notify the
#                    stack's alarm topic, and read metrics the instance offers
#   5. owner login   the running service's environment holds no part of the
#                    owner or the master secret: only the migrate step and the
#                    database bootstrap are given them
#   6. secret leaks  no secret value appears in the rendered UserData (read back
#                    from EC2) or the instance's cfn-init metadata, the
#                    cloud-init and cfn-init logs, the env files, the bootstrap's,
#                    the migrate step's and the service's journals, or the
#                    service's log file
#
# Runs on the operator's machine with the operator's AWS credentials; the
# database checks run on the instance over SSH, which reads its own secrets.
# On a fresh stack the SCIM step replaces the Bootstrap Admin's seeded password
# and writes the new one back to its secret (AppBootstrapSecretArn), staged as
# AWSPENDING until the change has succeeded. See infra/README.md, "Post-deploy
# smoke".
#
# Usage: ./smoke.sh [--leaks-only] <stack-name> [region]
#   --leaks-only  step 6 alone, over the sources that exist before the JAR is
#                 deployed: the rendered UserData and the cfn-init metadata, the
#                 cloud-init and cfn-init logs, the env files and the database
#                 bootstrap's journal. deploy.sh runs this when the JAR is not
#                 deployed.
#   KEY_FILE  the instance's SSH key (default: <KeyPairName>.pem beside this script)
#
# Never run with command tracing (bash -x): see lib.sh.
set -euo pipefail

# shellcheck source=lib.sh source-path=SCRIPTDIR
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STACK=""
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

# The Performance Insights counters the alarms read: SQL Server's own, which
# RDS publishes through Performance Insights only (infrastructure.yaml).
ALARM_METRICS=("db.Locks.Number of Deadlocks/sec" "db.General Statistics.Processes blocked")

check_alarms() {
  local topic actions name offered metric
  topic=$(stack_output AlarmTopicArn)
  for name in "$(stack_output DBDeadlocksAlarmName)" "$(stack_output DBBlockedSessionsAlarmName)"; do
    actions=$(aws cloudwatch describe-alarms --alarm-names "$name" --region "$REGION" \
      --query 'MetricAlarms[0].AlarmActions' --output json)
    [[ $(printf '%s' "$actions" | jq -r --arg t "$topic" 'index($t) != null') == true ]] ||
      die "alarms: $name is missing, or does not notify $topic"
    print_info "alarms: $name notifies the stack's alarm topic"
  done
  offered=$(aws pi list-available-resource-metrics --service-type RDS --region "$REGION" \
    --identifier "$(stack_output DBResourceId)" --metric-types db --query 'Metrics[].Metric' --output json)
  for metric in "${ALARM_METRICS[@]}"; do
    [[ $(printf '%s' "$offered" | jq -r --arg m "$metric" 'index($m) != null') == true ]] ||
      die "alarms: the instance's Performance Insights offers no '$metric', which an alarm reads"
  done
  print_info "alarms: the instance offers both counters the alarms read"
  # shellcheck disable=SC2016 # the backticks are a JMESPath literal, not a shell expansion
  [[ $(aws sns list-subscriptions-by-topic --topic-arn "$topic" --region "$REGION" \
    --query 'length(Subscriptions[?SubscriptionArn != `PendingConfirmation`])' --output text) != 0 ]] ||
    print_warn "alarms: nobody is subscribed to $topic yet (AlarmEmail, or confirm the email subscription)"
}

# The values to look for: every database password (the master's included,
# which only the database bootstrap may hold), the trust store's password, the
# Bootstrap Admin's current and previous password, and the Epic client
# credentials.
collect_secret_values() {
  local json arn name stage
  SECRET_VALUES=()
  for name in DBMasterSecretArn DBOwnerSecretArn DBAppSecretArn DBRetentionSecretArn \
    DBTrustStoreSecretArn; do
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

# check_secret_leaks [boot-only]: with boot-only, only the sources the boot
# writes; the service's log file and journal do not exist before it runs.
check_secret_leaks() {
  local boot_only=${1:-} instance_id source
  collect_secret_values
  instance_id=$(stack_output EC2InstanceId)
  aws ec2 describe-instance-attribute --instance-id "$instance_id" --attribute userData \
    --region "$REGION" --query UserData.Value --output text | base64 --decode |
    scan_for_secrets "the rendered UserData" "${SECRET_VALUES[@]}"
  aws cloudformation describe-stack-resource --stack-name "$STACK" --logical-resource-id EC2Instance \
    --region "$REGION" --query StackResourceDetail.Metadata --output text |
    scan_for_secrets "the instance's cfn-init metadata" "${SECRET_VALUES[@]}"
  for source in /var/log/cloud-init-output.log /var/log/cloud-init.log /var/log/cfn-init.log \
    /var/log/cfn-init-cmd.log /opt/backend/.env /opt/backend/migrate.env /opt/backend/db-bootstrap.env; do
    remote "sudo cat $source" | scan_for_secrets "$source" "${SECRET_VALUES[@]}"
  done
  remote "sudo journalctl -u backend-db-bootstrap --no-pager" |
    scan_for_secrets "the database bootstrap's journal" "${SECRET_VALUES[@]}"
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

# The running service holds no owner or master credential (ADR 0015, ac-1):
# its JVM's own environment, read from /proc on the instance, names no
# DATABASE_OWNER_* variable and contains no part of the owner or the master
# secret — their ARNs or their passwords. Only the migrate step
# (backend-migrate.service) and the database bootstrap
# (backend-db-bootstrap.service) are given those. The environment holds the app
# and retention passwords, so it is held in a local variable for the check and
# never printed.
check_service_holds_no_owner_credential() {
  local json master_json environ owner_arn master_arn
  owner_arn=$(stack_output DBOwnerSecretArn)
  master_arn=$(stack_output DBMasterSecretArn)
  json=$(secret_string "$owner_arn" "$REGION")
  master_json=$(secret_string "$master_arn" "$REGION")
  # shellcheck disable=SC2016 # expanded on the instance, not here
  environ=$(remote 'sudo cat "/proc/$(systemctl show -p MainPID --value backend)/environ" | tr "\0" "\n"')
  [[ -n $environ ]] || die "owner credential check: the backend service's environment could not be read"
  if grep -q '^DATABASE_OWNER_' <<<"$environ"; then
    die "owner credential check: the backend service's environment has a DATABASE_OWNER_ variable"
  fi
  printf '%s' "$environ" | scan_for_secrets "the backend service's environment" \
    "DBOwnerSecretArn=$owner_arn" "DBOwnerSecretArn.password=$(json_field "$json" password)" \
    "DBMasterSecretArn=$master_arn" "DBMasterSecretArn.password=$(json_field "$master_json" password)"
  environ=""
  json=""
  master_json=""
  print_info "owner credential check: the service runs without the owner and the master logins"
}

# The driver-scoped trust store left the JVM's default trust alone (ADR 0015):
# the running service's JVM has no javax.net.ssl.trustStore, and the same JDK,
# with its default trust store, fetches the identity provider's documents over
# HTTPS — Epic's OIDC discovery document and the JWKS it names when Epic Login
# is on, which the service fetches on first use, never at startup; a public
# AWS HTTPS endpoint when it is off. Runs on the instance, as the service user.
check_jvm_default_trust() {
  local out line
  # shellcheck disable=SC2016 # expanded on the instance, not here
  if ! out=$(remote 'sudo bash -s' 2>&1 <<'SCRIPT'
set -euo pipefail
pid=$(systemctl show -p MainPID --value backend)
jcmd="$(dirname "$(readlink -f /usr/bin/java)")/jcmd"
if sudo -u springboot "$jcmd" "$pid" VM.system_properties | grep -q '^javax\.net\.ssl\.trustStore='; then
  echo "the service's JVM has a javax.net.ssl.trustStore: the default trust store was replaced"
  exit 1
fi
echo "the service's JVM keeps the JDK's default trust store (no javax.net.ssl.trustStore)"
urls=()
if grep -q '^APP_EPIC_ENABLED=true$' /opt/backend/.env; then
  issuer=$(sed -n 's/^APP_EPIC_OAUTH_ISSUER=//p' /opt/backend/.env)
  discovery="$issuer/.well-known/openid-configuration"
  urls+=("$discovery" "$(curl -fsS --max-time 15 "$discovery" | jq -er '.jwks_uri')")
else
  region=$(sed -n 's/^AWS_REGION=//p' /opt/backend/.env)
  urls+=("https://truststore.pki.rds.amazonaws.com/$region/$region-bundle.pem")
fi
dir=$(mktemp -d)
chmod 0755 "$dir"
cat >"$dir/DefaultTrust.java" <<'JAVA'
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class DefaultTrust {
    public static void main(String[] urls) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (String url : urls) {
            int status = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            System.out.println("the JDK's default trust store fetched " + url + ": " + status);
            if (status >= 400) {
                System.exit(1);
            }
        }
    }
}
JAVA
chmod 0644 "$dir/DefaultTrust.java"
sudo -u springboot /usr/bin/java "$dir/DefaultTrust.java" "${urls[@]}"
rm -rf "$dir"
SCRIPT
  ); then
    die "JVM trust: $out"
  fi
  while IFS= read -r line; do print_info "JVM trust: $line"; done <<<"$out"
}

main() {
  local leaks_only="" json cmd
  if [[ ${1:-} == --leaks-only ]]; then
    leaks_only=yes
    shift
  fi
  STACK=${1:-}
  REGION=${2:-ap-southeast-1}
  [[ -n $STACK ]] || die "usage: $0 [--leaks-only] <stack-name> [region]"
  for cmd in aws jq ssh base64; do require_cmd "$cmd"; done

  load_stack_outputs "$STACK" "$REGION"
  ADMIN_SECRET_ARN=$(stack_output AppBootstrapSecretArn)

  if [[ -n $leaks_only ]]; then
    check_secret_leaks boot-only
    print_info "secret-leak check passed on stack $STACK (boot sources only: the service is not deployed)"
    return 0
  fi

  for cmd in curl openssl; do require_cmd "$cmd"; done

  check_health "$(stack_output LoadBalancerURL)"
  check_jvm_default_trust
  check_tls_enforced "$REGION"

  json=$(secret_string "$ADMIN_SECRET_ARN" "$REGION")
  ADMIN_USERNAME=$(json_field "$json" username)
  ADMIN_PASSWORD=$(json_field "$json" password)
  json=""
  scim_smoke "$(stack_output LoadBalancerURL)"
  unset ADMIN_PASSWORD

  check_alarms
  check_service_holds_no_owner_credential
  check_secret_leaks
  print_info "smoke passed on stack $STACK"
}

main "$@"
