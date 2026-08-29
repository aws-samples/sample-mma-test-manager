#!/bin/bash
# Deploy MMA Test Manager with Demo Infrastructure (Db2 LUW -> Aurora PostgreSQL)
#
# Engine-specific values below are taken from two templates that have been
# exercised against a real account:
#   rds-db2-ce.yaml          - RDS for Db2 Community Edition provisioning
#   dms-sc-project-db2.yaml  - DMS Schema Conversion project for a Db2 source
#
# ONE PREREQUISITE, outside this stack. Not optional.
#
# 1. IBM Community Edition registration.
#    RDS for Db2 CE is bring-your-own-licence. The CE licence is free from IBM,
#    but you must register to obtain a Customer ID and a Site ID.
#    Register (free): https://www.ibm.com/account/reg/us-en/signup?formid=urx-54367
#
#    These two IDs are CloudFormation parameters (Db2CustomerId, Db2SiteId),
#    NOT environment variables. This script does not handle them. Supply them
#    either by setting Default values on those parameters in
#    mma-apps-main-stack.yaml, or by appending them to this script's arguments:
#
#      ./deploy-with-demo-infra.sh <stack-prefix> \
#        ParameterKey=Db2CustomerId,ParameterValue=<your IBM customer ID> \
#        ParameterKey=Db2SiteId,ParameterValue=<your IBM site ID>
#
#    A template Rule rejects the stack immediately if DeployDemoInfra=true and
#    either ID is empty, so a missing licence ID fails at validation rather
#    than 10 minutes later on the Db2 resource.
#
# The License Manager self-managed licence configuration is NO LONGER a manual
# prerequisite. mma-nested-stacks/license-manager-stack.yaml creates it during
# deployment via a custom resource (CloudFormation has no native resource type
# for a self-managed configuration). It adopts an existing db2-ce configuration
# if the region already has one, and on stack delete removes only a
# configuration it created itself. A fresh workshop account needs no manual
# setup. The deploy role needs license-manager:CreateLicenseConfiguration,
# ListLicenseConfigurations, GetLicenseConfiguration and
# DeleteLicenseConfiguration.
#
# Usage:
#   ./deploy-with-demo-infra.sh [stack-prefix] [extra ParameterKey=...,ParameterValue=... ...]
#
# Optional overrides:
#   ADMIN_PASSWORD             Test Manager / VS Code password (no default)
#   AWS_REGION                 default us-east-1
#   DB2_ENGINE_VERSION         default 12.1.4.0.sb00085812.r1
#   DB2_INSTANCE_CLASS         default db.m7i.large (fallback db.t3.small)
#   SKIP_PREFLIGHT=1           bypass the pre-flight checks below
#
# Network overrides (Step 4). By default the script discovers the MMA demo VPC
# and its subnets by tag. Supply IDs directly to skip discovery entirely -
# useful when the network is not tagged the MMA way, or lives in an account
# where those tags mean something else:
#
#   VPC_ID                     e.g. vpc-0123456789abcdef0
#   EC2_SUBNET_ID              public subnet for the EC2 instance
#   PRIV_SUBNET_ID_1           private subnet, AZ 1
#   PRIV_SUBNET_ID_2           private subnet, AZ 2  (must differ from AZ 1)
#
#   Example - fully explicit:
#     AWS_REGION=eu-west-1 \
#     VPC_ID=vpc-abc123 \
#     EC2_SUBNET_ID=subnet-pub1 \
#     PRIV_SUBNET_ID_1=subnet-priv1 \
#     PRIV_SUBNET_ID_2=subnet-priv2 \
#     ./deploy-with-demo-infra_cloudfront.sh my-stack
#
# Or keep tag discovery but point it at differently named tags:
#   VPC_NAME_TAG               default MMA-vpc
#   EC2_SUBNET_NAME_TAG        default MMA-subnet-public1-AvailabilityZone1
#   PRIV_SUBNET_1_NAME_TAG     default MMA-subnet-private1-AvailabilityZone1
#   PRIV_SUBNET_2_NAME_TAG     default MMA-subnet-private2-AvailabilityZone2
#
# NOTE: pass network values through these variables, NOT as trailing
# ParameterKey=VpcId,... arguments. create-stack already sets VpcId,
# Ec2SubnetId, PrivSubnetId1 and PrivSubnetId2 explicitly, and CloudFormation
# rejects a parameter supplied twice.

set -euo pipefail

STACK_PREFIX="${1:-mma9}"
shift || true
# Any remaining arguments are forwarded verbatim to create-stack --parameters.
# Use this to supply Db2CustomerId / Db2SiteId, or to override any other
# parameter, without editing the template.
EXTRA_PARAMETERS=("$@")

REGION="${AWS_REGION:-us-east-1}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"

# Engine values confirmed in rds-db2-ce.yaml. CE ships as 12.1 only, but RDS
# requires the fully-qualified build string, not the bare major.minor.
DB2_ENGINE_VERSION="${DB2_ENGINE_VERSION:-12.1.4.0.sb00085812.r1}"
DB2_INSTANCE_CLASS="${DB2_INSTANCE_CLASS:-db.m7i.large}"
DB2_INSTANCE_CLASS_FALLBACK="db.t3.small"

SKIP_PREFLIGHT="${SKIP_PREFLIGHT:-}"

echo "=== MMA Test Manager Deployment with Demo Infrastructure ==="
echo "Region:            $REGION"
echo "Stack Prefix:      $STACK_PREFIX"
echo "Db2 engine:        db2-ce $DB2_ENGINE_VERSION"
echo "Db2 class:         $DB2_INSTANCE_CLASS"
echo ""

###############################################################################
# Step 1: Pre-flight checks
#
# The demo stack takes up to 60 minutes and runs with --disable-rollback, so a
# late failure leaves a half-built environment to clean up by hand. Everything
# cheap enough to check up front is checked here.
###############################################################################
preflight_failed=0

fail() {
  echo "  ✗ $1"
  preflight_failed=1
}

if [ -n "$SKIP_PREFLIGHT" ]; then
  echo "Step 1: Pre-flight checks SKIPPED (SKIP_PREFLIGHT is set)"
else
  echo "Step 1: Pre-flight checks"

  # --- Admin password -------------------------------------------------------
  # Must satisfy the AllowedPattern on the AdminPassword stack parameter. That
  # pattern uses lookaheads, which grep does not support, so each character
  # class is checked separately here.
  if [ -z "$ADMIN_PASSWORD" ]; then
    fail "ADMIN_PASSWORD is not set. export ADMIN_PASSWORD=<password>"
  else
    pw_ok=1
    [ "${#ADMIN_PASSWORD}" -ge 8 ] || pw_ok=0
    printf '%s' "$ADMIN_PASSWORD" | grep -q '[a-z]' || pw_ok=0
    printf '%s' "$ADMIN_PASSWORD" | grep -q '[A-Z]' || pw_ok=0
    printf '%s' "$ADMIN_PASSWORD" | grep -q '[0-9]' || pw_ok=0
    printf '%s' "$ADMIN_PASSWORD" | grep -q '[^a-zA-Z0-9]' || pw_ok=0
    if [ "$pw_ok" -eq 0 ]; then
      fail "ADMIN_PASSWORD must be 8+ chars with lowercase, uppercase, digit and special character."
    fi
    case "$ADMIN_PASSWORD" in
      *'$'*|*'#'*|*'{'*|*'}'*)
        fail "ADMIN_PASSWORD contains \$ # { or }, which conflict with shell processing in the SSM documents." ;;
    esac
  fi

  # --- IBM licence identifiers ----------------------------------------------
  # Not checked here: Db2CustomerId / Db2SiteId are CloudFormation parameters
  # and this script never sees their values. A Rule in mma-apps-main-stack.yaml
  # rejects the stack at validation time if either is empty while
  # DeployDemoInfra=true.

  # --- License Manager self-managed licence ---------------------------------
  # The stack now creates this in-flight (license-manager-stack.yaml), so a
  # missing configuration is NOT a failure - a fresh workshop account will not
  # have one, and failing here would block the very deploy that creates it.
  # Reported for information only.
  echo "  Checking for a License Manager configuration filtered on db2-ce..."
  if lm_configs=$(aws license-manager list-license-configurations \
        --region "$REGION" \
        --query "LicenseConfigurations[?ProductInformationList[?ProductInformationFilterList[?contains(ProductInformationFilterValue, 'db2-ce')]]].Name" \
        --output text 2>/dev/null); then
    if [ -z "$lm_configs" ] || [ "$lm_configs" = "None" ]; then
      echo "  · None found - LicenseManagerStack will create one during deployment."
    else
      echo "  ✓ Existing configuration found: $lm_configs (will be adopted, not duplicated)"
    fi
  else
    echo "  ! Could not query License Manager (missing permissions?)."
    echo "    The stack will still attempt to create the configuration; the deploy"
    echo "    role needs license-manager:CreateLicenseConfiguration and"
    echo "    ListLicenseConfigurations for that to succeed."
  fi

  # --- Db2 CE engine version ------------------------------------------------
  echo "  Checking db2-ce engine version $DB2_ENGINE_VERSION..."
  db2_versions=$(aws rds describe-db-engine-versions \
    --engine db2-ce --region "$REGION" \
    --query 'DBEngineVersions[].EngineVersion' --output text 2>/dev/null || true)
  if [ -z "$db2_versions" ]; then
    fail "No db2-ce engine versions returned in $REGION. RDS for Db2 Community Edition may not be available in this region."
  elif ! printf '%s\n' $db2_versions | grep -qx "$DB2_ENGINE_VERSION"; then
    latest=$(printf '%s\n' $db2_versions | sort -V | tail -n 1)
    fail "db2-ce $DB2_ENGINE_VERSION is not available in $REGION. Available: $db2_versions. Re-run with DB2_ENGINE_VERSION=$latest"
  else
    echo "  ✓ Engine version available"
  fi

  # --- Db2 instance class ---------------------------------------------------
  echo "  Checking instance class $DB2_INSTANCE_CLASS..."
  db2_classes=$(aws rds describe-orderable-db-instance-options \
    --engine db2-ce --engine-version "$DB2_ENGINE_VERSION" --region "$REGION" \
    --query 'OrderableDBInstanceOptions[].DBInstanceClass' --output text 2>/dev/null || true)
  if [ -z "$db2_classes" ]; then
    echo "  ! Could not enumerate orderable classes for db2-ce $DB2_ENGINE_VERSION. Verify manually."
  elif printf '%s\n' $db2_classes | grep -qx "$DB2_INSTANCE_CLASS"; then
    echo "  ✓ Instance class orderable"
  elif printf '%s\n' $db2_classes | grep -qx "$DB2_INSTANCE_CLASS_FALLBACK"; then
    echo "  ! $DB2_INSTANCE_CLASS is not orderable in $REGION; falling back to $DB2_INSTANCE_CLASS_FALLBACK"
    DB2_INSTANCE_CLASS="$DB2_INSTANCE_CLASS_FALLBACK"
  else
    fail "Neither $DB2_INSTANCE_CLASS nor $DB2_INSTANCE_CLASS_FALLBACK is orderable in $REGION. Available: $(printf '%s ' $db2_classes)"
  fi

  # --- DMS knows the db2 (LUW) engine --------------------------------------
  # DMS distinguishes 'db2' (LUW) from 'db2zos' (z/OS). This confirms the data
  # migration side only; Schema Conversion source support for
  # Db2 LUW -> Aurora PostgreSQL must be confirmed separately.
  echo "  Checking DMS support for the db2 source engine..."
  if dms_db2=$(aws dms describe-endpoint-types --region "$REGION" \
        --filters Name=engine-name,Values=db2 \
        --query 'SupportedEndpointTypes[?EndpointType==`source`].EngineName' \
        --output text 2>/dev/null); then
    if [ -z "$dms_db2" ] || [ "$dms_db2" = "None" ]; then
      fail "DMS does not report db2 as a source endpoint engine in $REGION."
    else
      echo "  ✓ DMS db2 source endpoint supported"
      echo "  ! Schema Conversion for Db2 LUW -> Aurora PostgreSQL is a separate"
      echo "    capability. Confirm it has launched in $REGION before relying on"
      echo "    the schema conversion and comparison-test modules."
    fi
  else
    echo "  ! Could not query DMS endpoint types. Verify manually."
  fi

  if [ "$preflight_failed" -ne 0 ]; then
    echo ""
    echo "❌ Pre-flight checks failed. Fix the items above and re-run."
    echo "   To bypass (not recommended): SKIP_PREFLIGHT=1 $0 $STACK_PREFIX"
    exit 1
  fi
  echo "  ✓ All pre-flight checks passed"
fi

###############################################################################
# Step 2: S3 bucket for nested stack templates
###############################################################################
echo ""
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUCKET_NAME="mma-dms-sc-${ACCOUNT_ID}"
echo "Step 2: Checking S3 bucket: $BUCKET_NAME"
if aws s3 ls "s3://$BUCKET_NAME" --region "$REGION" >/dev/null 2>&1; then
  echo "✅ Bucket already exists"
else
  echo "Creating S3 bucket..."
  aws s3 mb "s3://$BUCKET_NAME" --region "$REGION"
  echo "✅ Bucket created"
fi

###############################################################################
# Step 3: Upload nested stack templates
###############################################################################
echo ""
echo "Step 3: Uploading CloudFormation templates..."
aws s3 cp mma-nested-stacks/ "s3://$BUCKET_NAME/mma-nested-stacks/" --recursive
echo "✅ Templates uploaded"

###############################################################################
# Step 3b: Upload the Db2 demo SQL scripts and the script runner
###############################################################################
# These must be in S3 BEFORE create-stack. RunDemoSetupEnvironment and
# RunDemoDb2Schema execute SSM documents DURING stack creation, so anything the
# stack itself creates would still be empty when those steps read it.
#
# Neither the scripts nor the runner can be read from the code checkout on the
# instance: CodeURL defaults to a published Workshop Studio asset that predates
# the Db2 track, so it contains neither
# one-click-deployment/db2-to-postgres/demo-scripts/ nor
# db2-luw-client-mcp/tools/. RunRepoDownload succeeds (the download and unzip
# work; the content is wrong) and the demo steps then failed on the missing
# paths.
#
# Db2ScriptRunner.java is the program that executes the SQL, so it is staged
# alongside the scripts it runs - keeping the two in lockstep and independent of
# whatever CodeURL points at.
#
# Reuses $BUCKET_NAME, already created above for the templates, under a separate
# demo-scripts/ prefix.
echo ""
echo "Step 3b: Uploading Db2 demo SQL scripts and script runner..."

DEMO_SCRIPTS_PREFIX="demo-scripts/"
SCRIPT_RUNNER="../../db2-luw-client-mcp/tools/Db2ScriptRunner.java"

# Fail here rather than 20 minutes into the deploy if anything is missing.
# The list matches the six SetupEnvironmentDocument verifies on the instance.
REQUIRED_SCRIPTS=(
  01_user_rds_db2_phase1.sql
  01_user_rds_db2_phase2.sql
  01_user_rds_db2_phase3.sql
  02_schema_db2_v2.sql
  03_data_db2_v2.sql
  04_complex_schema_db2_v2.sql
)
MISSING_SCRIPTS=()
for f in "${REQUIRED_SCRIPTS[@]}"; do
  [ -s "demo-scripts/$f" ] || MISSING_SCRIPTS+=("$f")
done
if [ ${#MISSING_SCRIPTS[@]} -gt 0 ]; then
  echo ""
  echo "❌ Required demo SQL scripts are missing or empty in ./demo-scripts:"
  for f in "${MISSING_SCRIPTS[@]}"; do echo "     $f"; done
  echo ""
  echo "   Run this script from one-click-deployment/db2-to-postgres so that"
  echo "   ./demo-scripts resolves, and check the files are present."
  exit 1
fi

if [ ! -s "$SCRIPT_RUNNER" ]; then
  echo ""
  echo "❌ Db2ScriptRunner.java not found at $SCRIPT_RUNNER"
  echo ""
  echo "   This is the program that executes the demo SQL scripts; without it"
  echo "   RunDemoDb2Schema cannot populate Db2. Run this script from"
  echo "   one-click-deployment/db2-to-postgres inside a full repo checkout."
  exit 1
fi

aws s3 cp demo-scripts/ "s3://$BUCKET_NAME/$DEMO_SCRIPTS_PREFIX" \
  --recursive --exclude '*' --include '*.sql' --region "$REGION"
aws s3 cp "$SCRIPT_RUNNER" \
  "s3://$BUCKET_NAME/${DEMO_SCRIPTS_PREFIX}Db2ScriptRunner.java" --region "$REGION"
echo "✅ Demo SQL scripts and runner uploaded to s3://$BUCKET_NAME/$DEMO_SCRIPTS_PREFIX"

###############################################################################
# Step 3c: Build and upload the application code archive
###############################################################################
# The stack fetches this archive in RunRepoDownload and unpacks it to
# /workshop/MMA-Test-Manager, then RunApplicationDeployment builds it with
# build-all.sh. It MUST exist in S3 before create-stack, because that step runs
# during stack creation.
#
# Built and uploaded here rather than left to the caller. Previously the script
# created the bucket and uploaded templates, demo scripts and the runner into it
# but expected mma-apps.zip to already be present - so a deploy that omitted
# that one manual upload failed ~10 minutes in with a 404 on HeadObject.
#
# Set CODE_ZIP to use a prebuilt archive instead of zipping the working tree,
# or CODE_URL to point at an externally hosted one and skip this entirely.
echo ""
echo "Step 3c: Building and uploading the application code archive..."

REPO_ROOT="../.."
CODE_KEY="mma-apps.zip"

# An explicit ParameterKey=CodeURL on the command line wins as the value passed
# to the stack. It does NOT skip the build when it points at the bucket this
# script manages: skipping in that case produced a stack that referenced
# s3://<our bucket>/mma-apps.zip while nothing ever uploaded it, failing with a
# 404 on HeadObject. Only an archive hosted ELSEWHERE is treated as "already
# provided".
code_url=""
for p in "${EXTRA_PARAMETERS[@]+"${EXTRA_PARAMETERS[@]}"}"; do
  case "$p" in
    ParameterKey=CodeURL,ParameterValue=*)
      code_url="${p#ParameterKey=CodeURL,ParameterValue=}" ;;
  esac
done

# Does the supplied CodeURL name the bucket this script uploads to?
code_url_is_ours=0
if [ -n "$code_url" ]; then
  case "$code_url" in
    s3://"$BUCKET_NAME"/*|https://"$BUCKET_NAME".s3.*amazonaws.com/*|https://s3.*amazonaws.com/"$BUCKET_NAME"/*)
      code_url_is_ours=1 ;;
  esac
fi

if [ -n "$code_url" ] && [ "$code_url_is_ours" -eq 0 ]; then
  echo "  CodeURL points outside $BUCKET_NAME; skipping build and upload:"
  echo "    $code_url"
elif [ -n "${CODE_URL:-}" ]; then
  # Caller supplied an archive location; nothing to build or upload. CODE_BUCKET
  # is derived below so the instance role is granted read access when it is an
  # S3 location.
  echo "  CODE_URL set; skipping build and upload: $CODE_URL"
  code_url="$CODE_URL"
else
  if [ -n "${CODE_ZIP:-}" ]; then
    [ -s "$CODE_ZIP" ] || { echo "❌ CODE_ZIP is set but not readable: $CODE_ZIP"; exit 1; }
    zip_path="$CODE_ZIP"
    echo "  Using prebuilt archive: $zip_path"
  else
    # Sanity-check the tree before zipping: these are the two paths the SSM
    # documents read out of the checkout, so a wrong working directory should
    # fail here rather than on the instance.
    for required in "$REPO_ROOT/build-all.sh" "$REPO_ROOT/db2-luw-client-mcp/tools/Db2ScriptRunner.java"; do
      [ -e "$required" ] || {
        echo "❌ Expected $required in the repo checkout."
        echo "   Run this script from one-click-deployment/db2-to-postgres inside a"
        echo "   full checkout, or set CODE_ZIP to a prebuilt archive."
        exit 1
      }
    done

    zip_path="$(mktemp -d)/mma-apps.zip"
    echo "  Zipping $(cd "$REPO_ROOT" && pwd) ..."
    # Contents must sit at the TOP LEVEL of the archive: the SSM document does
    # `unzip -d /workshop/MMA-Test-Manager`, so a wrapper directory would shift
    # every path down one level and break the demo-scripts and runner lookups.
    # COPYFILE_DISABLE stops macOS storing AppleDouble files as __MACOSX/.
    ( cd "$REPO_ROOT" && COPYFILE_DISABLE=1 zip -rq "$zip_path" . \
        -x '*/target/*' '*.DS_Store' '*/.DS_Store' '*/.git/*' '*.zip' '__MACOSX/*' )
    [ -s "$zip_path" ] || { echo "❌ Failed to create $zip_path"; exit 1; }
  fi

  # Verify the archive really carries what the stack will read out of it, so a
  # stale or wrongly-rooted zip fails now rather than mid-deploy.
  #
  # The listing is captured first rather than piped into `grep -q`: grep exits at
  # the first match, unzip then takes SIGPIPE, and under `set -o pipefail` that
  # fails the pipeline even though the entry was found. Intermittent, depending
  # on where in the listing the match falls - measured at 2 failures in 10 runs.
  zip_entries=$(unzip -Z1 "$zip_path")
  for entry in build-all.sh db2-luw-client-mcp/tools/Db2ScriptRunner.java; do
    if ! printf '%s\n' "$zip_entries" | grep -qxF "$entry"; then
      echo "❌ $zip_path does not contain $entry at the top level."
      echo "   Archive contents must not be nested under a wrapper directory."
      exit 1
    fi
  done

  aws s3 cp "$zip_path" "s3://$BUCKET_NAME/$CODE_KEY" --region "$REGION"
  echo "✅ Code archive uploaded to s3://$BUCKET_NAME/$CODE_KEY"
  code_url="s3://$BUCKET_NAME/$CODE_KEY"
fi

###############################################################################
# Step 3d: Resolve the code archive bucket
###############################################################################
# CodeBucket is passed to the stack so the instance role can be granted read
# access to the code archive. RepoDownloadDocument then fetches it with
# `aws s3 cp` under that role, which means the bucket does NOT need to be public
# and no presigned URL is required.
#
# code_url was resolved in Step 3c: an explicit CodeURL parameter, the CODE_URL
# environment variable, or the archive this script just uploaded. A non-S3 value
# leaves CODE_BUCKET empty, no grant is added, and the document uses wget.
CODE_BUCKET=""
case "$code_url" in
  s3://*)
    rest="${code_url#s3://}"; CODE_BUCKET="${rest%%/*}" ;;
  https://*.s3.amazonaws.com/*|https://*.s3.*.amazonaws.com/*)
    rest="${code_url#https://}"; host="${rest%%/*}"; CODE_BUCKET="${host%%.s3.*}" ;;
  https://s3.amazonaws.com/*|https://s3.*.amazonaws.com/*)
    rest="${code_url#https://}"; rest="${rest#*/}"; CODE_BUCKET="${rest%%/*}" ;;
esac

# CodeURL is only added to the create-stack parameters when it is not already
# supplied explicitly, since CloudFormation rejects a duplicated ParameterKey.
CODE_URL_PARAM=()
case " ${EXTRA_PARAMETERS[*]+${EXTRA_PARAMETERS[*]}} " in
  *ParameterKey=CodeURL,*) ;;
  *) CODE_URL_PARAM=("ParameterKey=CodeURL,ParameterValue=$code_url") ;;
esac

echo ""
if [ -n "$CODE_BUCKET" ]; then
  echo "Step 3d: Code archive in S3 - bucket $CODE_BUCKET (private access via instance role)"
else
  echo "Step 3d: Code archive not in S3 - it will be fetched over HTTPS and must be publicly reachable"
fi

###############################################################################
# Step 4: Resolve VPC and subnets
###############################################################################
echo ""
echo "Step 4: Getting VPC and Subnet information..."

lookup_subnet() {
  aws ec2 describe-subnets --region "$REGION" \
    --filters "Name=vpc-id,Values=$VPC_ID" "Name=tag:Name,Values=$1" \
    --query "Subnets[0].SubnetId" --output text
}

VPC_ID="${VPC_ID:-}"
if [ -n "$VPC_ID" ]; then
  echo "  Using VPC_ID from environment: $VPC_ID"
else
  VPC_ID=$(aws ec2 describe-vpcs --region "$REGION" \
    --filters "Name=tag:Name,Values=$VPC_NAME_TAG" \
    --query "Vpcs[0].VpcId" --output text)
fi

# Each subnet is taken from the environment if set, otherwise discovered. The
# subnet lookups filter on VPC_ID, so an explicit VPC with discovered subnets
# works too.
EC2SUBNET1="${EC2_SUBNET_ID:-}"
[ -n "$EC2SUBNET1" ] || EC2SUBNET1=$(lookup_subnet "$EC2_SUBNET_NAME_TAG")
PrivSUBNET1="${PRIV_SUBNET_ID_1:-}"
[ -n "$PrivSUBNET1" ] || PrivSUBNET1=$(lookup_subnet "$PRIV_SUBNET_1_NAME_TAG")
PrivSUBNET2="${PRIV_SUBNET_ID_2:-}"
[ -n "$PrivSUBNET2" ] || PrivSUBNET2=$(lookup_subnet "$PRIV_SUBNET_2_NAME_TAG")

echo "VPC:               $VPC_ID"
echo "EC2 Subnet 1:      $EC2SUBNET1"
echo "Private Subnet 1:  $PrivSUBNET1"
echo "Private Subnet 2:  $PrivSUBNET2"

# describe-* returns the string "None" rather than failing when a tag lookup
# misses. Catch that here instead of letting CloudFormation reject it 30s in.
for pair in "VPC_ID:$VPC_ID" "EC2SUBNET1:$EC2SUBNET1" \
            "PrivSUBNET1:$PrivSUBNET1" "PrivSUBNET2:$PrivSUBNET2"; do
  name="${pair%%:*}"; value="${pair#*:}"
  if [ -z "$value" ] || [ "$value" = "None" ]; then
    echo ""
    echo "❌ $name could not be resolved in $REGION."
    echo ""
    echo "   No template in this repo creates a VPC - an existing network is a"
    echo "   prerequisite. By default this script looks for the MMA demo VPC"
    echo "   (tag Name=$VPC_NAME_TAG) with subnets tagged:"
    echo "     $EC2_SUBNET_NAME_TAG"
    echo "     $PRIV_SUBNET_1_NAME_TAG"
    echo "     $PRIV_SUBNET_2_NAME_TAG"
    echo ""
    echo "   Options:"
    echo "     a) Pass IDs directly, skipping tag discovery:"
    echo "          VPC_ID=vpc-... EC2_SUBNET_ID=subnet-... \\"
    echo "          PRIV_SUBNET_ID_1=subnet-... PRIV_SUBNET_ID_2=subnet-... \\"
    echo "          ./$(basename "$0") $STACK_PREFIX"
    echo "     b) Point discovery at your tag names, e.g. VPC_NAME_TAG=my-vpc"
    echo "     c) Check AWS_REGION - it is currently $REGION"
    echo ""
    echo "   To see what exists in this region:"
    echo "     aws ec2 describe-vpcs --region $REGION \\"
    echo "       --query \"Vpcs[].{VpcId:VpcId,Name:Tags[?Key=='Name']|[0].Value}\" \\"
    echo "       --output table"
    exit 1
  fi
done

# The two private subnets must be in different AZs: the RDS/Aurora subnet groups
# built from them require at least two AZs, and CloudFormation would otherwise
# fail well into the deploy. Cheap to catch now.
if [ "$PrivSUBNET1" = "$PrivSUBNET2" ]; then
  echo ""
  echo "❌ PRIV_SUBNET_ID_1 and PRIV_SUBNET_ID_2 are the same subnet ($PrivSUBNET1)."
  echo "   RDS and Aurora subnet groups need two distinct availability zones."
  exit 1
fi

PRIV_AZS=$(aws ec2 describe-subnets --region "$REGION" \
  --subnet-ids "$PrivSUBNET1" "$PrivSUBNET2" \
  --query "Subnets[].AvailabilityZone" --output text 2>/dev/null || echo "")
if [ -n "$PRIV_AZS" ]; then
  UNIQUE_AZS=$(echo "$PRIV_AZS" | tr '\t' '\n' | sort -u | wc -l | tr -d ' ')
  if [ "$UNIQUE_AZS" -lt 2 ]; then
    echo ""
    echo "❌ The private subnets are both in the same AZ ($PRIV_AZS)."
    echo "   RDS and Aurora subnet groups need two distinct availability zones."
    exit 1
  fi
  echo "  Private subnet AZs: $PRIV_AZS"
fi

###############################################################################
# Step 5: Deploy the stack
###############################################################################
echo ""
echo "Step 5: Deploying CloudFormation stack..."
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
    ParameterKey=DemoScriptsBucket,ParameterValue="$BUCKET_NAME" \
    ParameterKey=DemoScriptsPrefix,ParameterValue="$DEMO_SCRIPTS_PREFIX" \
    ParameterKey=CodeBucket,ParameterValue="$CODE_BUCKET" \
    ${CODE_URL_PARAM[@]+"${CODE_URL_PARAM[@]}"} \
    ParameterKey=AdminPassword,ParameterValue="$ADMIN_PASSWORD" \
    ParameterKey=VSCodeUser,ParameterValue=awsmma \
    ParameterKey=Db2EngineVersion,ParameterValue="$DB2_ENGINE_VERSION" \
    ParameterKey=Db2InstanceClass,ParameterValue="$DB2_INSTANCE_CLASS" \
    ${EXTRA_PARAMETERS[@]+"${EXTRA_PARAMETERS[@]}"} \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
  --disable-rollback \
  --region "$REGION"

echo ""
echo "✅ Stack deployment initiated!"
echo ""
echo "=== Deployment Information ==="
echo "Stack Name:  $STACK_PREFIX"
echo "S3 Bucket:   $BUCKET_NAME"
echo "Region:      $REGION"
echo "Db2 engine:  db2-ce $DB2_ENGINE_VERSION on $DB2_INSTANCE_CLASS"
echo ""
echo "Monitor stack creation:"
echo "  aws cloudformation describe-stacks --stack-name $STACK_PREFIX --region $REGION --query 'Stacks[0].StackStatus'"
echo ""
echo "Wait for completion:"
echo "  aws cloudformation wait stack-create-complete --stack-name $STACK_PREFIX --region $REGION"
echo ""
echo "=== DMS Schema Conversion ==="
echo "With DeployDemoInfra=true the stack ALREADY runs assessment, conversion and"
echo "export-to-target automatically (SSM document <prefix>-dms-schema-conversion,"
echo "step RunDMSSchemaConversion). You do not need to run anything by hand."
echo ""
echo "The commands below are for re-running a step manually, or for"
echo "DeployDemoInfra=false. Selection rules take the source server name and"
echo "schema (pattern from dms-sc-project-db2.yaml):"
echo ""
echo '  DB2_HOST=$(aws cloudformation describe-stacks --stack-name '"$STACK_PREFIX"' \'
echo "    --region $REGION --query \"Stacks[0].Outputs[?OutputKey=='DemoDb2Endpoint'].OutputValue\" --output text)"
echo '  RULES='"'"'{"rules":[{"rule-type":"selection","rule-id":"1","rule-name":"1",'
echo '           "object-locator":{"server-name":"'"'"'"$DB2_HOST"'"'"'","schema-name":"DEMO"},'
echo '           "rule-action":"explicit"}]}'"'"''
echo ""
echo "  aws dms start-metadata-model-import     --migration-project-identifier <arn> --selection-rules \"\$RULES\" --origin SOURCE"
echo "  aws dms start-metadata-model-assessment --migration-project-identifier <arn> --selection-rules \"\$RULES\""
echo "  aws dms start-metadata-model-conversion --migration-project-identifier <arn> --selection-rules \"\$RULES\""
echo ""
echo "NOTE: the schema is DEMO - it holds the demo objects, and matches the"
echo "SourceSchema default in the conversion document. DB2INST1 is the master"
echo "user's implicit schema and contains none of them."
echo ""
echo "NOTE: the automated document runs assessment -> conversion -> export but"
echo "does NOT call start-metadata-model-import first (the Oracle track behaves"
echo "the same way). If assessment fails for want of a source metadata model,"
echo "run the import command above, then re-run the SSM document."
