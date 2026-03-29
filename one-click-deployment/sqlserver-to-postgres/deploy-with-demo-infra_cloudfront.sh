#!/bin/bash
# Deploy MMA Test Manager with Demo Infrastructure

set -e

STACK_PREFIX="${1:-mma9}"
REGION="${AWS_REGION:-us-east-1}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-YOUR_SECURE_PASSWORD_HERE}"

echo "=== MMA Test Manager Deployment with Demo Infrastructure ==="
echo "Region: $REGION"
echo "Stack Prefix: $STACK_PREFIX"
echo ""

# Step 1: Create S3 bucket
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUCKET_NAME="mma-dms-sc-${ACCOUNT_ID}"
echo "Step 1: Checking S3 bucket: $BUCKET_NAME"
if aws s3 ls s3://"$BUCKET_NAME" --region "$REGION" 2>/dev/null; then
  echo "✅ Bucket already exists"
else
  echo "Creating S3 bucket..."
  aws s3 mb s3://"$BUCKET_NAME" --region "$REGION"
  echo "✅ Bucket created"
fi

# Step 4: Upload CFN templates
echo ""
echo "Step 4: Uploading CloudFormation templates..."
aws s3 cp mma-nested-stacks/ s3://"$BUCKET_NAME"/mma-nested-stacks/ --recursive
echo "✅ Templates uploaded"

# Step 5: Get VPC and Subnets
echo ""
echo "Step 5: Getting VPC and Subnet information..."
VPC_ID=$(aws ec2 describe-vpcs --region "$REGION" --filters "Name=tag:Name,Values=MMA-vpc" --query "Vpcs[0].VpcId" --output text)
EC2SUBNET1=$(aws ec2 describe-subnets --region us-east-1 --filters "Name=vpc-id,Values="$VPC_ID"" "Name=tag:Name,Values=MMA-subnet-public1-AvailabilityZone1" --query "Subnets[0].SubnetId" --output text)
#EC2SUBNET1=$(aws ec2 describe-subnets --region us-east-1 --filters "Name=vpc-id,Values="$VPC_ID"" "Name=tag:Name,Values=MMA-subnet-private1-AvailabilityZone1" --query "Subnets[0].SubnetId" --output text)
PrivSUBNET1=$(aws ec2 describe-subnets --region us-east-1 --filters "Name=vpc-id,Values="$VPC_ID"" "Name=tag:Name,Values=MMA-subnet-private1-AvailabilityZone1" --query "Subnets[0].SubnetId" --output text)
PrivSUBNET2=$(aws ec2 describe-subnets --region us-east-1 --filters "Name=vpc-id,Values="$VPC_ID"" "Name=tag:Name,Values=MMA-subnet-private2-AvailabilityZone2" --query "Subnets[0].SubnetId" --output text)

echo "VPC: $VPC_ID"
echo "EC2 Subnet 1: $EC2SUBNET1"
echo "Private Subnet 1: "$PrivSUBNET1""
echo "Private Subnet 2: "$PrivSUBNET2""

# Step 6: Deploy stack
echo ""
echo "Step 6: Deploying CloudFormation stack..."
echo "Stack Name: $STACK_PREFIX"
echo ""

aws cloudformation create-stack \
  --stack-name "$STACK_PREFIX" \
  --template-body file://mma-apps-main-stack.yaml \
  --parameters \
    ParameterKey=VpcId,ParameterValue="$VPC_ID" \
    ParameterKey=Ec2SubnetId,ParameterValue="$EC2SUBNET1" \
    ParameterKey=PrivSubnetId1,ParameterValue="$PrivSUBNET1" \
    ParameterKey=PrivSubnetId2,ParameterValue="$PrivSUBNET2" \
    ParameterKey=DeployDemoInfra,ParameterValue=true \
    ParameterKey=UseCloudFront,ParameterValue=true \
    ParameterKey=InstanceType,ParameterValue=t3.large \
    ParameterKey=StackPrefix,ParameterValue="$STACK_PREFIX" \
    ParameterKey=TemplateS3Bucket,ParameterValue="$BUCKET_NAME" \
    ParameterKey=TemplateS3Prefix,ParameterValue=mma-nested-stacks/ \
    ParameterKey=AdminPassword,ParameterValue="$ADMIN_PASSWORD" \
    ParameterKey=VSCodeUser,ParameterValue=awsmma \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
  --disable-rollback \
  --region "$REGION"

echo ""
echo "✅ Stack deployment initiated!"
echo ""
echo "=== Deployment Information ==="
echo "Stack Name: $STACK_PREFIX"
echo "S3 Bucket: $BUCKET_NAME"
echo "Region: $REGION"
echo ""
echo "Monitor stack creation:"
echo "  aws cloudformation describe-stacks --stack-name "$STACK_PREFIX" --region "$REGION" --query 'Stacks[0].StackStatus'"
echo ""
echo "Wait for completion:"
echo "  aws cloudformation wait stack-create-complete --stack-name "$STACK_PREFIX" --region "$REGION""
