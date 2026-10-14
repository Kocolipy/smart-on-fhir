#!/bin/bash

# AWS CloudFormation Stack Cleanup Script

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Get inputs
read -p "Enter stack name to delete (default: spring-backend): " STACK_NAME
STACK_NAME=${STACK_NAME:-spring-backend}

AWS_REGION="ap-southeast-1"
print_info "Region set to: ap-southeast-1 (Singapore)"

# Check if stack exists
print_info "Checking if stack ${STACK_NAME} exists..."

if ! aws cloudformation describe-stacks --stack-name ${STACK_NAME} --region ${AWS_REGION} &> /dev/null; then
    print_error "Stack ${STACK_NAME} does not exist in region ${AWS_REGION}"
    exit 1
fi

# Get stack status
STACK_STATUS=$(aws cloudformation describe-stacks \
    --stack-name ${STACK_NAME} \
    --region ${AWS_REGION} \
    --query 'Stacks[0].StackStatus' \
    --output text)

print_info "Stack status: ${STACK_STATUS}"

# Warning
echo
print_warn "WARNING: This will delete the following resources:"
echo "  - EC2 Instance and its data"
echo "  - RDS Database (a final snapshot will be created)"
echo "  - The database and application secrets (scheduled for deletion by Secrets Manager)"
echo "  (The database KMS key is RETAINED: the final snapshot is encrypted under it."
echo "   Schedule its deletion yourself once the snapshot is no longer needed.)"
echo "  - ElastiCache Redis cluster"
echo "  - VPC and all networking components"
echo "  - NAT Gateway and Elastic IP"
echo
read -p "Are you sure you want to proceed? (yes/no): " CONFIRM

if [ "$CONFIRM" != "yes" ]; then
    print_info "Deletion cancelled"
    exit 0
fi

# Delete stack
print_info "Deleting stack ${STACK_NAME}..."

aws cloudformation delete-stack \
    --stack-name ${STACK_NAME} \
    --region ${AWS_REGION}

print_info "Deletion initiated. Waiting for completion..."
print_warn "This may take 10-15 minutes..."

aws cloudformation wait stack-delete-complete \
    --stack-name ${STACK_NAME} \
    --region ${AWS_REGION}

print_info "Stack ${STACK_NAME} deleted successfully!"

# Cleanup local files
if [ -f "/tmp/cfn-parameters-${STACK_NAME}.json" ]; then
    rm /tmp/cfn-parameters-${STACK_NAME}.json
    print_info "Cleaned up local parameter file"
fi

if [ -f "${STACK_NAME}-outputs.txt" ]; then
    rm ${STACK_NAME}-outputs.txt
    print_info "Cleaned up outputs file"
fi

echo
print_info "Cleanup complete!"
