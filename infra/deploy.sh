#!/bin/bash

# AWS CloudFormation Deployment Script for Spring Boot Backend
#
# Prompts for no password: every credential is generated into Secrets Manager
# by the stack (infra/README.md, "Database credentials"). The stack's instance
# bootstraps the database itself at boot; this script waits for that boot,
# re-runs the bootstrap to prove it a no-op (db-bootstrap.sh --expect-no-op),
# and runs the post-deploy smoke (smoke.sh), which fails the deploy on a leaked
# secret: the full smoke once the JAR is deployed, and its leak check over the
# boot alone when it is not. The template exceeds CloudFormation's inline
# template-body limit, so it is staged in S3 first (stage_template).
# Never run with command tracing (bash -x): see lib.sh.

set -euo pipefail

# Resolve paths so the script works from any working directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BACKEND_DIR="${REPO_ROOT}/backend"

# shellcheck source=lib.sh source-path=SCRIPTDIR
source "${SCRIPT_DIR}/lib.sh"

# Check prerequisites
check_prerequisites() {
    print_info "Checking prerequisites..."

    local cmd
    for cmd in aws jq curl ssh scp openssl base64; do
        require_cmd "$cmd"
    done

    if ! aws sts get-caller-identity &> /dev/null; then
        print_error "AWS CLI is not configured. Run 'aws configure' first."
        exit 1
    fi

    # The stack's bootstrap SQL must be the local init's (infra/README.md,
    # "Database bootstrap"): a template whose copy has drifted is not deployed.
    "${SCRIPT_DIR}/check-bootstrap-parity.sh" ||
        die "the template's database bootstrap SQL differs from backend/scripts/local-db-init.sql"

    print_info "Prerequisites check passed"
}

# The IAM principal deploying the stack administers its database key (and only
# administers it). An assumed role's session ARN is turned into the role's ARN,
# path included, which is what a key policy names.
default_key_admin_arn() {
    local caller role
    caller=$(aws sts get-caller-identity --query Arn --output text)
    case $caller in
        arn:*:sts::*:assumed-role/*)
            role=${caller#*:assumed-role/}
            aws iam get-role --role-name "${role%%/*}" --query Role.Arn --output text
            ;;
        *) printf '%s\n' "$caller" ;;
    esac
}

# Get user inputs
get_inputs() {
    print_info "CloudFormation Stack Configuration"
    echo

    read -r -p "Enter stack name (default: spring-backend): " STACK_NAME
    STACK_NAME=${STACK_NAME:-spring-backend}

    AWS_REGION="ap-southeast-1"
    print_info "Region set to: ap-southeast-1 (Singapore)"

    # VPC Configuration (Required)
    echo
    print_info "VPC Configuration (Existing VPC Required)"

    read -r -p "Enter existing VPC ID (e.g., vpc-12345678): " VPC_ID
    [ -n "$VPC_ID" ] || die "VPC ID is required"

    read -r -p "Enter Public Subnet 1 ID (e.g., subnet-12345678): " PUBLIC_SUBNET_1_ID
    [ -n "$PUBLIC_SUBNET_1_ID" ] || die "Public Subnet 1 ID is required"

    read -r -p "Enter Public Subnet 2 ID (e.g., subnet-87654321): " PUBLIC_SUBNET_2_ID
    [ -n "$PUBLIC_SUBNET_2_ID" ] || die "Public Subnet 2 ID is required"

    read -r -p "Enter Private Subnet 1 ID (e.g., subnet-11111111): " PRIVATE_SUBNET_1_ID
    [ -n "$PRIVATE_SUBNET_1_ID" ] || die "Private Subnet 1 ID is required"

    read -r -p "Enter Private Subnet 2 ID (e.g., subnet-22222222): " PRIVATE_SUBNET_2_ID
    [ -n "$PRIVATE_SUBNET_2_ID" ] || die "Private Subnet 2 ID is required"

    echo
    # EC2 Key Pair Configuration
    print_info "EC2 Key Pair Configuration"
    read -r -p "Create new EC2 Key Pair? (y/n, default: y): " CREATE_KEY_PAIR
    CREATE_KEY_PAIR=${CREATE_KEY_PAIR:-y}

    if [[ $CREATE_KEY_PAIR =~ ^[Yy]$ ]]; then
        CREATE_KEY_PAIR_VALUE="true"
        read -r -p "Enter Key Pair name (default: spring-backend-key): " KEY_NAME
        KEY_NAME=${KEY_NAME:-spring-backend-key}
        print_info "CloudFormation will create the key pair; this script retrieves its private key."
    else
        CREATE_KEY_PAIR_VALUE="false"
        read -r -p "Enter existing Key Pair name: " KEY_NAME
        [ -n "$KEY_NAME" ] || die "Key Pair name is required"
    fi

    echo
    read -r -p "Enter EC2 instance type (default: t3.small): " INSTANCE_TYPE
    INSTANCE_TYPE=${INSTANCE_TYPE:-t3.small}

    read -r -p "Enter your IP for SSH access (e.g., 1.2.3.4/32): " SSH_LOCATION
    SSH_LOCATION=${SSH_LOCATION:-0.0.0.0/0}

    # Database configuration. No password: the master user's is RDS-managed and
    # the three logins' are generated, all in Secrets Manager.
    print_info "Database Configuration"
    read -r -p "Enter database name (default: backend): " DB_NAME
    DB_NAME=${DB_NAME:-backend}

    # RDS for SQL Server Standard Edition, licence-included: it runs on none of
    # the small classes, and costs far more than the PostgreSQL stack's
    # db.t3.micro did (README, "Engine, edition and cost").
    print_warn "SQL Server Standard Edition (licence-included) bills its licence by the hour: see README, \"Engine, edition and cost\""
    read -r -p "Enter RDS instance class (default: db.t3.xlarge, the smallest SE runs on): " DB_INSTANCE_CLASS
    DB_INSTANCE_CLASS=${DB_INSTANCE_CLASS:-db.t3.xlarge}

    local default_admin
    default_admin=$(default_key_admin_arn)
    read -r -p "IAM principal administering the database KMS key (default: ${default_admin}): " KEY_ADMIN_ARN
    KEY_ADMIN_ARN=${KEY_ADMIN_ARN:-$default_admin}

    read -r -p "Email for database alarms (optional, confirm the email AWS sends): " ALARM_EMAIL

    # Redis configuration
    print_info "Redis Configuration"
    read -r -p "Enter Redis node type (default: cache.t3.micro): " REDIS_NODE_TYPE
    REDIS_NODE_TYPE=${REDIS_NODE_TYPE:-cache.t3.micro}

    # Application configuration. The Bootstrap Admin's password is generated into
    # its secret; smoke.sh replaces it on the first deploy.
    print_info "Application Configuration"
    read -r -p "Enter application ADMIN username (default: admin): " APP_BOOTSTRAP_USERNAME
    APP_BOOTSTRAP_USERNAME=${APP_BOOTSTRAP_USERNAME:-admin}
}

# The engine and class RDS will actually be asked for: the template names the
# major version 16.00 (SQL Server 2022), which RDS resolves to its default
# build, and the instance class must be orderable for Standard Edition,
# licence-included, in this region. Checked before anything is created.
check_engine_and_class() {
    local version
    version=$(aws rds describe-db-engine-versions --engine sqlserver-se --engine-version 16.00 \
        --default-only --region "${AWS_REGION}" --query 'DBEngineVersions[0].EngineVersion' --output text)
    [[ -n $version && $version != None ]] ||
        die "RDS offers no default SQL Server 2022 (16.00) Standard Edition build in ${AWS_REGION}"
    print_info "RDS resolves engine version 16.00 to ${version}"
    [[ $(aws rds describe-orderable-db-instance-options --engine sqlserver-se --engine-version "$version" \
        --license-model license-included --db-instance-class "${DB_INSTANCE_CLASS}" --region "${AWS_REGION}" \
        --query 'length(OrderableDBInstanceOptions)' --output text) != 0 ]] ||
        die "${DB_INSTANCE_CLASS} is not orderable for SQL Server Standard Edition, licence-included, in ${AWS_REGION}"
    print_info "${DB_INSTANCE_CLASS} is orderable for SQL Server Standard Edition, licence-included"
}

# The template is larger than create-stack's 51,200-byte --template-body
# limit, so it goes to S3 and the stack is created from its URL. The bucket is
# CFN_TEMPLATE_BUCKET, or one this script creates once per account and region
# with every public access blocked. The template holds no secret.
stage_template() {
    local account bucket key
    account=$(aws sts get-caller-identity --query Account --output text)
    bucket=${CFN_TEMPLATE_BUCKET:-cfn-templates-${account}-${AWS_REGION}}
    if ! aws s3api head-bucket --bucket "$bucket" --region "${AWS_REGION}" 2>/dev/null; then
        print_info "Creating the template bucket ${bucket}"
        aws s3api create-bucket --bucket "$bucket" --region "${AWS_REGION}" \
            --create-bucket-configuration "LocationConstraint=${AWS_REGION}" >/dev/null
        aws s3api put-public-access-block --bucket "$bucket" --region "${AWS_REGION}" \
            --public-access-block-configuration \
            BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
    fi
    key="${STACK_NAME}/infrastructure-$(date +%Y%m%d%H%M%S).yaml"
    aws s3 cp "${SCRIPT_DIR}/infrastructure.yaml" "s3://${bucket}/${key}" --region "${AWS_REGION}" >/dev/null
    TEMPLATE_URL="https://${bucket}.s3.${AWS_REGION}.amazonaws.com/${key}"
    print_info "Template staged at ${TEMPLATE_URL}"
}

# Create parameters file. It holds no secret.
create_parameters_file() {
    print_info "Creating parameters file..."

    PARAMETERS_FILE="/tmp/cfn-parameters-${STACK_NAME}.json"
    jq -n \
        --arg VpcId "$VPC_ID" \
        --arg PublicSubnet1Id "$PUBLIC_SUBNET_1_ID" \
        --arg PublicSubnet2Id "$PUBLIC_SUBNET_2_ID" \
        --arg PrivateSubnet1Id "$PRIVATE_SUBNET_1_ID" \
        --arg PrivateSubnet2Id "$PRIVATE_SUBNET_2_ID" \
        --arg CreateKeyPair "$CREATE_KEY_PAIR_VALUE" \
        --arg KeyName "$KEY_NAME" \
        --arg InstanceType "$INSTANCE_TYPE" \
        --arg SSHLocation "$SSH_LOCATION" \
        --arg DBName "$DB_NAME" \
        --arg DBInstanceClass "$DB_INSTANCE_CLASS" \
        --arg KeyAdminArn "$KEY_ADMIN_ARN" \
        --arg AlarmEmail "$ALARM_EMAIL" \
        --arg RedisNodeType "$REDIS_NODE_TYPE" \
        --arg AppBootstrapUsername "$APP_BOOTSTRAP_USERNAME" \
        '$ARGS.named | to_entries | map({ParameterKey: .key, ParameterValue: .value})' \
        > "$PARAMETERS_FILE"

    print_info "Parameters file created at ${PARAMETERS_FILE}"
}

# Deploy stack
deploy_stack() {
    print_info "Deploying CloudFormation stack: ${STACK_NAME}"

    aws cloudformation create-stack \
        --stack-name "${STACK_NAME}" \
        --template-url "${TEMPLATE_URL}" \
        --parameters "file://${PARAMETERS_FILE}" \
        --capabilities CAPABILITY_NAMED_IAM \
        --region "${AWS_REGION}"

    print_info "Stack creation initiated. Waiting for completion..."
    print_warn "This may take 15-20 minutes..."

    aws cloudformation wait stack-create-complete \
        --stack-name "${STACK_NAME}" \
        --region "${AWS_REGION}"

    print_info "Stack created successfully!"
}

# Display outputs
display_outputs() {
    print_info "Retrieving stack outputs..."

    load_stack_outputs "${STACK_NAME}" "${AWS_REGION}"
    EC2_IP=$(stack_output EC2PublicIP)
    ALB_DNS=$(stack_output LoadBalancerDNS)
    ALB_URL=$(stack_output LoadBalancerURL)
    DB_ENDPOINT=$(stack_output DBEndpoint)
    REDIS_ENDPOINT=$(stack_output RedisEndpoint)
    SSH_COMMAND=$(stack_output SSHCommand)
    KEY_RETRIEVE_CMD=$(stack_output GetPrivateKeyCommand)
    ADMIN_SECRET_ARN=$(stack_output AppBootstrapSecretArn)
    ALARM_TOPIC_ARN=$(stack_output AlarmTopicArn)

    echo
    print_info "=== Deployment Complete ==="
    echo
    echo "Load Balancer DNS: ${ALB_DNS}"
    echo "Application URL: ${ALB_URL}"
    echo "EC2 Instance IP (SSH): ${EC2_IP}"
    echo "Database Endpoint: ${DB_ENDPOINT}"
    echo "Redis Endpoint: ${REDIS_ENDPOINT}"
    echo "Bootstrap Admin secret: ${ADMIN_SECRET_ARN}"
    echo "Alarm topic: ${ALARM_TOPIC_ARN}"
    echo

    echo "SSH Command:"
    echo "  ${SSH_COMMAND}"
    echo

    # Save to file
    cat > "${STACK_NAME}-outputs.txt" << EOF
Stack Name: ${STACK_NAME}
Region: ${AWS_REGION}
Deployed: $(date)

Load Balancer DNS: ${ALB_DNS}
Application URL: ${ALB_URL}
EC2 Instance IP: ${EC2_IP}
Database Endpoint: ${DB_ENDPOINT}
Redis Endpoint: ${REDIS_ENDPOINT}
Bootstrap Admin secret: ${ADMIN_SECRET_ARN}
Alarm topic: ${ALARM_TOPIC_ARN}

$(if [ "$CREATE_KEY_PAIR_VALUE" = "true" ]; then
echo "Retrieve Private Key Command:"
echo "${KEY_RETRIEVE_CMD}"
echo ""
fi)

SSH Command:
${SSH_COMMAND}

Next Steps (deploy.sh does 1-3 when you answer yes to the JAR prompt):
1. The database and its logins were bootstrapped by the instance at boot; to
   re-run that (a no-op on a healthy stack): ./db-bootstrap.sh ${STACK_NAME} ${AWS_REGION}
2. Build the integrated JAR (SPA + backend), from the repo root: make package
   (equivalently: scripts/package.sh — 'cd backend && ./mvnw clean package' is
   backend-only and produces a JAR with no SPA)
3. Copy JAR to EC2: scp -i ${KEY_NAME}.pem ../backend/target/backend-0.0.1-SNAPSHOT.jar ec2-user@${EC2_IP}:/tmp/backend.jar
   then on the instance: sudo mv /tmp/backend.jar /opt/backend/backend.jar && sudo chown springboot:springboot /opt/backend/backend.jar
   and: sudo systemctl stop backend && sudo systemctl restart backend-migrate && sudo systemctl start backend && sudo systemctl enable backend
   (the migrate step re-runs the database bootstrap, then the migrations as the owner login; if it fails, do not start the service)
4. Post-deploy smoke (health, TLS, SCIM, alarms, secret leaks): ./smoke.sh ${STACK_NAME} ${AWS_REGION}
5. The Bootstrap Admin's password: aws secretsmanager get-secret-value --secret-id ${ADMIN_SECRET_ARN} --query SecretString --output text
EOF

    print_info "Outputs saved to ${STACK_NAME}-outputs.txt"
}

# The private key of a key pair CloudFormation created, written once, owner-only.
retrieve_ssh_key() {
    KEY_FILE="${SCRIPT_DIR}/${KEY_NAME}.pem"
    export KEY_FILE
    if [ -f "$KEY_FILE" ]; then
        print_info "Using SSH key ${KEY_FILE}"
        return 0
    fi
    [ "$CREATE_KEY_PAIR_VALUE" = "true" ] ||
        die "SSH key ${KEY_FILE} not found: place the existing key pair's private key there"

    print_info "Retrieving the stack's SSH private key into ${KEY_FILE}"
    (
        umask 077
        aws ssm get-parameter \
            --name "/ec2/keypair/$(stack_output KeyPairId)" \
            --with-decryption --query Parameter.Value --output text \
            --region "${AWS_REGION}" > "$KEY_FILE"
    )
    chmod 400 "$KEY_FILE"
}

# The instance bootstraps the database at boot (UserData). Wait for that
# boot, then run the bootstrap again: on a fresh stack the re-run must change
# nothing, which proves it idempotent before anything depends on it.
bootstrap_database() {
    "${SCRIPT_DIR}/db-bootstrap.sh" --expect-no-op "${STACK_NAME}" "${AWS_REGION}"
}

# Prompt for JAR deployment
prompt_jar_deployment() {
    echo
    read -r -p "Do you want to deploy the application JAR now? (y/n): " DEPLOY_JAR

    if [[ $DEPLOY_JAR =~ ^[Yy]$ ]]; then
        deploy_jar
        "${SCRIPT_DIR}/smoke.sh" "${STACK_NAME}" "${AWS_REGION}"
    else
        # The boot's leak check needs no JAR: UserData, cloud-init logs, .env.
        print_warn "Skipping JAR deployment; checking the boot for leaked secrets only"
        "${SCRIPT_DIR}/smoke.sh" --leaks-only "${STACK_NAME}" "${AWS_REGION}"
        print_warn "Deploy the JAR, then run ./smoke.sh ${STACK_NAME} ${AWS_REGION} for the full smoke; see ${STACK_NAME}-outputs.txt"
    fi
}

# Deploy JAR
deploy_jar() {
    print_info "Building application (SPA + backend, integrated JAR)..."

    if [ ! -f "${BACKEND_DIR}/pom.xml" ]; then
        print_error "pom.xml not found at ${BACKEND_DIR}. Is infra/ still a sibling of backend/?"
        exit 1
    fi

    if [ ! -x "${REPO_ROOT}/scripts/package.sh" ]; then
        print_error "scripts/package.sh not found or not executable at ${REPO_ROOT}/scripts/package.sh"
        exit 1
    fi

    # The integrated package path, not a bare './mvnw clean package': the
    # backend's with-frontend profile is off by default, so a pure backend build
    # ships a JAR with no SPA. package.sh builds frontend/dist and activates the
    # profile with an explicit -Dfrontend.dist.dir.
    "${REPO_ROOT}/scripts/package.sh"

    JAR_FILE=$(find "${BACKEND_DIR}/target" -maxdepth 1 -name 'backend-*.jar' ! -name '*-plain.jar' -print -quit)

    if [ -z "$JAR_FILE" ]; then
        print_error "JAR file not found in ${BACKEND_DIR}/target"
        exit 1
    fi

    # package.sh already asserts this; re-check the artefact we are about to ship
    # so a stale or hand-built JAR in target/ cannot be deployed SPA-less.
    if command -v unzip >/dev/null 2>&1; then
        if unzip -Z1 "${JAR_FILE}" 'BOOT-INF/classes/static/index.html' >/dev/null 2>&1; then
            print_info "verified BOOT-INF/classes/static/index.html inside ${JAR_FILE}"
        else
            print_error "${JAR_FILE} contains no BOOT-INF/classes/static/index.html — the SPA is missing from the JAR"
            exit 1
        fi
    else
        print_warn "unzip not installed — cannot verify the SPA is inside ${JAR_FILE}"
    fi

    print_info "Copying JAR to EC2 instance..."

    scp -i "$KEY_FILE" \
        -o StrictHostKeyChecking=accept-new \
        "${JAR_FILE}" \
        "ec2-user@${EC2_IP}:/tmp/backend.jar"

    print_info "Migrating the schema (migrate step), then starting the application..."

    # The migrate step runs first — after the database bootstrap, which its unit
    # requires — as the owner login, against the new JAR; the service is stopped
    # while it runs and is started only once it succeeded. A failed migration
    # ends this script with the service still stopped, so a schema the new JAR
    # does not match is never served (infra/README.md, "Migrate step").
    ssh -i "$KEY_FILE" \
        -o StrictHostKeyChecking=accept-new \
        "ec2-user@${EC2_IP}" << 'ENDSSH'
set -euo pipefail
sudo mv /tmp/backend.jar /opt/backend/backend.jar
sudo chown springboot:springboot /opt/backend/backend.jar
sudo systemctl stop backend
if ! sudo systemctl restart backend-migrate; then
    sudo journalctl -u backend-db-bootstrap -u backend-migrate -n 50 --no-pager || true
    echo "the migrate step failed; the service was not started" >&2
    exit 1
fi
sudo systemctl start backend
sudo systemctl enable backend
sleep 5
sudo systemctl status backend --no-pager
ENDSSH

    print_info "Application deployed and started!"
}

# Main execution
main() {
    echo "=========================================="
    echo "AWS CloudFormation Deployment Script"
    echo "Spring Boot Backend Infrastructure"
    echo "=========================================="
    echo

    check_prerequisites
    get_inputs
    check_engine_and_class
    create_parameters_file
    stage_template
    deploy_stack
    display_outputs
    retrieve_ssh_key
    bootstrap_database
    prompt_jar_deployment

    echo
    print_info "Deployment completed successfully!"
    print_info "Check ${STACK_NAME}-outputs.txt for connection details"
}

# Run main function
main
