#!/bin/bash

# Script to get VPC information for CloudFormation deployment
# Usage: ./get-vpc-info.sh [vpc-id]

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

REGION="ap-southeast-1"

print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

if [ $# -eq 0 ]; then
    print_header "Available VPCs in ap-southeast-1"
    aws ec2 describe-vpcs \
        --region ${REGION} \
        --query 'Vpcs[*].[VpcId,CidrBlock,Tags[?Key==`Name`].Value|[0],State]' \
        --output table
    
    echo
    echo "Usage: $0 <vpc-id>"
    echo "Example: $0 vpc-0a1b2c3d4e5f6g7h8"
    exit 0
fi

VPC_ID=$1

print_header "VPC Information"
echo "VPC ID: $VPC_ID"
echo "Region: $REGION"
echo

# Validate VPC exists
if ! aws ec2 describe-vpcs --vpc-ids ${VPC_ID} --region ${REGION} &> /dev/null; then
    print_warn "VPC $VPC_ID not found in region $REGION"
    exit 1
fi

# Get VPC details
print_info "VPC Details:"
aws ec2 describe-vpcs \
    --vpc-ids ${VPC_ID} \
    --region ${REGION} \
    --query 'Vpcs[0].[VpcId,CidrBlock,State,Tags[?Key==`Name`].Value|[0]]' \
    --output table

echo

# Get Internet Gateway
print_info "Internet Gateway:"
IGW=$(aws ec2 describe-internet-gateways \
    --region ${REGION} \
    --filters "Name=attachment.vpc-id,Values=${VPC_ID}" \
    --query 'InternetGateways[0].InternetGatewayId' \
    --output text)

if [ "$IGW" != "None" ]; then
    echo "✓ Internet Gateway: $IGW"
else
    print_warn "No Internet Gateway attached to this VPC"
fi

echo

# Get NAT Gateways
print_info "NAT Gateways:"
aws ec2 describe-nat-gateways \
    --region ${REGION} \
    --filter "Name=vpc-id,Values=${VPC_ID}" "Name=state,Values=available" \
    --query 'NatGateways[*].[NatGatewayId,SubnetId,State]' \
    --output table

echo

# Get Public Subnets
print_header "PUBLIC SUBNETS (for EC2)"
PUBLIC_SUBNETS=$(aws ec2 describe-subnets \
    --region ${REGION} \
    --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=true" \
    --query 'Subnets[*].[SubnetId,AvailabilityZone,CidrBlock,Tags[?Key==`Name`].Value|[0]]' \
    --output table)

if [ -n "$PUBLIC_SUBNETS" ]; then
    echo "$PUBLIC_SUBNETS"
    
    PUBLIC_COUNT=$(aws ec2 describe-subnets \
        --region ${REGION} \
        --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=true" \
        --query 'length(Subnets)' \
        --output text)
    
    if [ "$PUBLIC_COUNT" -lt 2 ]; then
        print_warn "Only $PUBLIC_COUNT public subnet(s) found. Need at least 2 in different AZs."
    else
        print_info "✓ Found $PUBLIC_COUNT public subnet(s)"
    fi
else
    print_warn "No public subnets found"
fi

echo

# Get Private Subnets
print_header "PRIVATE SUBNETS (for RDS for SQL Server & Redis)"
PRIVATE_SUBNETS=$(aws ec2 describe-subnets \
    --region ${REGION} \
    --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=false" \
    --query 'Subnets[*].[SubnetId,AvailabilityZone,CidrBlock,Tags[?Key==`Name`].Value|[0]]' \
    --output table)

if [ -n "$PRIVATE_SUBNETS" ]; then
    echo "$PRIVATE_SUBNETS"
    
    PRIVATE_COUNT=$(aws ec2 describe-subnets \
        --region ${REGION} \
        --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=false" \
        --query 'length(Subnets)' \
        --output text)
    
    if [ "$PRIVATE_COUNT" -lt 2 ]; then
        print_warn "Only $PRIVATE_COUNT private subnet(s) found. RDS for SQL Server needs at least 2 in different AZs (its subnet group)."
    else
        print_info "✓ Found $PRIVATE_COUNT private subnet(s)"
    fi
else
    print_warn "No private subnets found"
fi

echo

# Generate parameters JSON snippet
print_header "CloudFormation Parameters"
echo "Add these to your parameters.json file:"
echo

# Get subnet IDs
PUBLIC_SUBNET_1=$(aws ec2 describe-subnets \
    --region ${REGION} \
    --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=true" \
    --query 'Subnets[0].SubnetId' \
    --output text)

PUBLIC_SUBNET_2=$(aws ec2 describe-subnets \
    --region ${REGION} \
    --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=true" \
    --query 'Subnets[1].SubnetId' \
    --output text)

PRIVATE_SUBNET_1=$(aws ec2 describe-subnets \
    --region ${REGION} \
    --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=false" \
    --query 'Subnets[0].SubnetId' \
    --output text)

PRIVATE_SUBNET_2=$(aws ec2 describe-subnets \
    --region ${REGION} \
    --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=false" \
    --query 'Subnets[1].SubnetId' \
    --output text)

cat << EOF
{
  "ParameterKey": "VpcId",
  "ParameterValue": "${VPC_ID}"
},
{
  "ParameterKey": "PublicSubnet1Id",
  "ParameterValue": "${PUBLIC_SUBNET_1}"
},
{
  "ParameterKey": "PublicSubnet2Id",
  "ParameterValue": "${PUBLIC_SUBNET_2}"
},
{
  "ParameterKey": "PrivateSubnet1Id",
  "ParameterValue": "${PRIVATE_SUBNET_1}"
},
{
  "ParameterKey": "PrivateSubnet2Id",
  "ParameterValue": "${PRIVATE_SUBNET_2}"
}
EOF

echo
echo

# Validation summary
print_header "Validation Summary"

ALL_GOOD=true

if [ "$IGW" == "None" ]; then
    echo "❌ No Internet Gateway attached"
    ALL_GOOD=false
else
    echo "✓ Internet Gateway attached"
fi

if [ "$PUBLIC_SUBNET_1" == "None" ] || [ "$PUBLIC_SUBNET_2" == "None" ]; then
    echo "❌ Need at least 2 public subnets in different AZs"
    ALL_GOOD=false
else
    echo "✓ Public subnets available"
fi

if [ "$PRIVATE_SUBNET_1" == "None" ] || [ "$PRIVATE_SUBNET_2" == "None" ]; then
    echo "❌ Need at least 2 private subnets in different AZs (RDS for SQL Server, Redis)"
    ALL_GOOD=false
else
    echo "✓ Private subnets available"
fi

echo

echo "Note: at boot the EC2 instance (public subnet 1) downloads the SQL Server command-line"
echo "tools from packages.microsoft.com and the RDS CA bundle from truststore.pki.rds.amazonaws.com,"
echo "so that subnet needs a route to the Internet Gateway."
echo

if [ "$ALL_GOOD" = true ]; then
    print_info "✓ VPC is ready for CloudFormation deployment!"
else
    print_warn "VPC configuration needs adjustments before deployment"
fi
