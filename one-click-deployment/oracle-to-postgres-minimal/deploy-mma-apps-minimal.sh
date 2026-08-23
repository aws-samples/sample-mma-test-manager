#!/bin/bash
###############################################################################
# MMA Test Manager - Minimal Deployment
#
# Deploys VS Code (code-server) + the MMA Test Manager onto a private-subnet
# EC2 host, for customers who ALREADY have a VPC, subnet, Test Manager repo DB,
# source Oracle DB, target PostgreSQL DB, and a DMS Schema Conversion project.
#
# Configure via environment variables (see the "Required configuration" block),
# then run:   ./deploy-mma-apps-minimal.sh deploy
#
# Other commands: validate | status | outputs | delete
###############################################################################
set -euo pipefail

# ----------------------------------------------------------------------------
# Required configuration (export these before running, or edit the defaults)
# ----------------------------------------------------------------------------
AWS_PROFILE="${AWS_PROFILE:-}"                 # e.g. workshop  (optional)
REGION="${AWS_REGION:-ap-southeast-2}"
STACK_NAME="${STACK_NAME:-mma-testmgr-minimal}"
STACK_PREFIX="${STACK_PREFIX:-mma-min}"

VPC_ID="${VPC_ID:-}"                           # existing VPC id
EC2_SUBNET_ID="${EC2_SUBNET_ID:-}"             # existing PRIVATE subnet id
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"           # VS Code + Test Manager password
VSCODE_USER="${VSCODE_USER:-awsmma}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.large}"
INSTANCE_VOLUME_SIZE="${INSTANCE_VOLUME_SIZE:-50}"
KEY_PAIR_NAME="${KEY_PAIR_NAME:-}"             # optional
ALLOWED_CLIENT_CIDR="${ALLOWED_CLIENT_CIDR:-}" # optional; blank = SSM-only (no inbound)

CODE_REPO_URL="${CODE_REPO_URL:-https://github.com/aws-samples/sample-mma-test-manager.git}"
CODE_REPO_BRANCH="${CODE_REPO_BRANCH:-main}"

REPO_DB_SECRET_ARN="${REPO_DB_SECRET_ARN:-}"
SOURCE_ORACLE_SECRET_ARN="${SOURCE_ORACLE_SECRET_ARN:-}"
TARGET_POSTGRES_SECRET_ARN="${TARGET_POSTGRES_SECRET_ARN:-}"
SECRETS_KMS_KEY_ARN="${SECRETS_KMS_KEY_ARN:-}"  # optional (blank = aws/secretsmanager)
S3_DMS_PROJECT_PATH="${S3_DMS_PROJECT_PATH:-}"  # s3://bucket/project
S3_DMS_PROJECT_BUCKET="${S3_DMS_PROJECT_BUCKET:-}"

# S3 bucket to hold the nested CloudFormation templates. Defaults to a
# per-account bucket that this script will create if missing.
TEMPLATE_S3_PREFIX="${TEMPLATE_S3_PREFIX:-mma-nested-stacks/}"
TEMPLATE_S3_BUCKET="${TEMPLATE_S3_BUCKET:-}"

# ----------------------------------------------------------------------------
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()   { echo -e "${RED}[ERROR]${NC} $1"; }

AWSCLI=(aws --region "$REGION")
[ -n "$AWS_PROFILE" ] && AWSCLI+=(--profile "$AWS_PROFILE")

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN_TEMPLATE="$SCRIPT_DIR/mma-apps-main-stack.yaml"
NESTED_DIR="$SCRIPT_DIR/mma-nested-stacks"

require() {
  local missing=0
  for v in VPC_ID EC2_SUBNET_ID ADMIN_PASSWORD REPO_DB_SECRET_ARN \
           SOURCE_ORACLE_SECRET_ARN TARGET_POSTGRES_SECRET_ARN \
           S3_DMS_PROJECT_PATH S3_DMS_PROJECT_BUCKET; do
    if [ -z "${!v}" ]; then err "Missing required variable: $v"; missing=1; fi
  done
  [ "$missing" -eq 0 ] || { err "Set the variables above and re-run."; exit 1; }
}

resolve_bucket() {
  if [ -z "$TEMPLATE_S3_BUCKET" ]; then
    local acct; acct="$("${AWSCLI[@]}" sts get-caller-identity --query Account --output text)"
    TEMPLATE_S3_BUCKET="mma-cfn-templates-${acct}-${REGION}"
  fi
}

ensure_bucket() {
  resolve_bucket
  if "${AWSCLI[@]}" s3api head-bucket --bucket "$TEMPLATE_S3_BUCKET" 2>/dev/null; then
    info "Using existing template bucket: $TEMPLATE_S3_BUCKET"
  else
    info "Creating template bucket: $TEMPLATE_S3_BUCKET"
    if [ "$REGION" = "us-east-1" ]; then
      "${AWSCLI[@]}" s3api create-bucket --bucket "$TEMPLATE_S3_BUCKET"
    else
      "${AWSCLI[@]}" s3api create-bucket --bucket "$TEMPLATE_S3_BUCKET" \
        --create-bucket-configuration LocationConstraint="$REGION"
    fi
  fi
}

upload_templates() {
  info "Uploading nested templates to s3://$TEMPLATE_S3_BUCKET/$TEMPLATE_S3_PREFIX"
  "${AWSCLI[@]}" s3 cp "$NESTED_DIR/" "s3://$TEMPLATE_S3_BUCKET/$TEMPLATE_S3_PREFIX" \
    --recursive --exclude "*" --include "*.yaml"
}

validate() {
  info "Validating templates..."
  "${AWSCLI[@]}" cloudformation validate-template \
    --template-body "file://$MAIN_TEMPLATE" >/dev/null
  for f in "$NESTED_DIR"/*.yaml; do
    "${AWSCLI[@]}" cloudformation validate-template --template-body "file://$f" >/dev/null
    info "  ok: $(basename "$f")"
  done
  info "All templates valid."
}

params() {
  cat <<EOP
ParameterKey=VpcId,ParameterValue=$VPC_ID
ParameterKey=Ec2SubnetId,ParameterValue=$EC2_SUBNET_ID
ParameterKey=AdminPassword,ParameterValue=$ADMIN_PASSWORD
ParameterKey=VSCodeUser,ParameterValue=$VSCODE_USER
ParameterKey=InstanceType,ParameterValue=$INSTANCE_TYPE
ParameterKey=InstanceVolumeSize,ParameterValue=$INSTANCE_VOLUME_SIZE
ParameterKey=KeyPairName,ParameterValue=$KEY_PAIR_NAME
ParameterKey=AllowedClientCidr,ParameterValue=$ALLOWED_CLIENT_CIDR
ParameterKey=CodeRepoUrl,ParameterValue=$CODE_REPO_URL
ParameterKey=CodeRepoBranch,ParameterValue=$CODE_REPO_BRANCH
ParameterKey=StackPrefix,ParameterValue=$STACK_PREFIX
ParameterKey=TemplateS3Bucket,ParameterValue=$TEMPLATE_S3_BUCKET
ParameterKey=TemplateS3Prefix,ParameterValue=$TEMPLATE_S3_PREFIX
ParameterKey=RepoDBSecretArn,ParameterValue=$REPO_DB_SECRET_ARN
ParameterKey=SourceOracleSecretArn,ParameterValue=$SOURCE_ORACLE_SECRET_ARN
ParameterKey=TargetPostgresSecretArn,ParameterValue=$TARGET_POSTGRES_SECRET_ARN
ParameterKey=SecretsKmsKeyArn,ParameterValue=$SECRETS_KMS_KEY_ARN
ParameterKey=S3DMSProjectPath,ParameterValue=$S3_DMS_PROJECT_PATH
ParameterKey=S3DMSProjectBucket,ParameterValue=$S3_DMS_PROJECT_BUCKET
EOP
}

stack_exists() {
  "${AWSCLI[@]}" cloudformation describe-stacks --stack-name "$STACK_NAME" >/dev/null 2>&1
}

deploy() {
  require
  ensure_bucket
  upload_templates
  validate

  local action
  if stack_exists; then action="update-stack"; else action="create-stack"; fi
  info "Running $action for $STACK_NAME ..."

  # shellcheck disable=SC2046
  "${AWSCLI[@]}" cloudformation "$action" \
    --stack-name "$STACK_NAME" \
    --template-body "file://$MAIN_TEMPLATE" \
    --parameters $(params) \
    --capabilities CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
    $( [ "$action" = "create-stack" ] && echo --disable-rollback )

  info "Waiting for stack operation to complete (this can take ~15-20 min)..."
  "${AWSCLI[@]}" cloudformation wait "stack-${action%%-stack}-complete" \
    --stack-name "$STACK_NAME" || true
  outputs
}

outputs() {
  "${AWSCLI[@]}" cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query 'Stacks[0].Outputs' --output table
}

status() {
  "${AWSCLI[@]}" cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query 'Stacks[0].StackStatus' --output text
}

delete() {
  warn "Deleting stack $STACK_NAME"
  read -r -p "Type 'yes' to confirm: " c
  [ "$c" = "yes" ] || { info "Cancelled"; exit 0; }
  "${AWSCLI[@]}" cloudformation delete-stack --stack-name "$STACK_NAME"
  "${AWSCLI[@]}" cloudformation wait stack-delete-complete --stack-name "$STACK_NAME"
  info "Deleted."
}

case "${1:-deploy}" in
  deploy)   deploy ;;
  validate) validate ;;
  status)   status ;;
  outputs)  outputs ;;
  delete)   delete ;;
  *) echo "Usage: $0 {deploy|validate|status|outputs|delete}"; exit 1 ;;
esac
