# MMA Test Manager — Minimal Deployment (Oracle → PostgreSQL)

A stripped-down deployment for customers who **already run** the migration
platform and only need to add the **VS Code (code-server)** workspace and the
**MMA Test Manager** on a single EC2 host.

It reuses the workshop layout — the sample repo is cloned into
**`/workshop/MMA-Samples/`** — so it stays aligned with the MMA workshop guide.

## How this differs from `../oracle-to-postgres`

| | Full one-click (`oracle-to-postgres`) | This minimal deployment |
|---|---|---|
| VPC / subnets | Can create / expects specific layout | **Bring your own** (existing) |
| Test Manager repo DB | Creates Aurora Serverless v2 | **Bring your own** (secret ARN) |
| Source / target DBs | Optional demo Oracle + Aurora + DMS | **Bring your own** (secret ARNs) |
| Sample / demo data | Optional demo infrastructure | **None** |
| Public access | nginx + optional CloudFront, public IP | **None** — private subnet, SSM only |
| TLS proxy (nginx) | Yes | **Yes** — single port 443, self-signed TLS, reverse-proxies both apps |
| OS handling | AL2023 | AL2023 only (unchanged, explicit) |
| App source | Workshop asset zip (`CodeURL`) | **`git clone`** from GitHub into `/workshop/MMA-Samples` |
| DB clients installed | Oracle + PostgreSQL + SQL Server | **Oracle + PostgreSQL only** |
| Nested stacks | network, compute, database, demo, app-setup, ssm-waiter | **compute, application-setup, ssm-waiter** |

## Contents

```
oracle-to-postgres-minimal/
├── mma-apps-main-stack.yaml         # Main stack (IAM, SG, orchestration)
├── mma-nested-stacks/
│   ├── compute-stack.yaml           # EC2 (AL2023, private) + base-setup SSM doc
│   ├── application-setup.yaml       # clone/build + configure + start SSM docs
│   └── ssm-waiter-stack.yaml        # Lambda custom resource (runs SSM docs in order)
├── deploy-mma-apps-minimal.sh       # Upload templates + create/update the stack
├── PREREQUISITES.md                 # What you must have before deploying
└── ACCESS-AND-TROUBLESHOOTING.md    # How to reach the apps + fix issues
```

## Architecture

```
Your workstation ──SSM port-forward──► EC2 (private subnet, no public IP)
                                         └─ nginx (TLS) : 443
                                              ├─ /          → code-server      : 8080
                                              └─ /testmgr/  → mma-test-manager : 8082
EC2 ──► Secrets Manager (repo / source Oracle / target PG)
EC2 ──► S3 (DMS Schema Conversion output)
EC2 ──► your databases (repo PG 5432, Oracle 1521, target PG 5432)
```

CloudFormation provisions the host and then runs four SSM documents in order via
a Lambda waiter: **base setup → clone & build → configure secrets → start
service + configure nginx**.

## Security notes

- **Do not deploy on a public subnet.** The host is designed for a **private
  subnet** with no public IP. Access is via SSM (default) or an in-VPC client
  (`AllowedClientCidr`). Putting it on a public subnet — especially opening 443
  to `0.0.0.0/0` — exposes the workspace and databases to the internet and is
  strongly discouraged.
- **TLS is on by default** via nginx with a **self-signed** certificate. This
  encrypts traffic in transit; it does not authenticate the server, so it does
  not stop an active man-in-the-middle — but it is meaningfully better than
  plaintext. Replace it with a CA-issued/ACM certificate for anything beyond a
  personal sandbox (see `ACCESS-AND-TROUBLESHOOTING.md`, section 8).
- **No inbound by default.** The security group has no ingress unless you set
  `AllowedClientCidr`; reach the host through SSM port forwarding.
- **Least-privilege IAM.** The host role can read only the three secrets and the
  one DMS S3 bucket you name, plus `kms:Decrypt` on your CMK when supplied.

## Ideas to improve the customer experience

- **Bring-your-own TLS / internal ALB.** Front the host with an internal ALB +
  ACM cert for CA-trusted TLS and a stable DNS name, still without public exposure.
- **Cost control.** This is a single-user tooling host — stop it when idle
  (`aws ec2 stop-instances`) and start it before a session; the systemd services
  auto-start on boot.
- **Scoped SSM access.** Grant `ssm:StartSession` on just this instance to the
  operators who need it, rather than account-wide.
- **Reuse existing secrets.** The DMS SC source/target data-provider secrets can
  double as `SourceOracleSecretArn` / `TargetPostgresSecretArn` — no new secrets
  needed.
- **Config drift.** Secrets are read at app start; after rotating a secret,
  `systemctl restart mma-test-manager` (no redeploy).

## Deploy

1. Read **`PREREQUISITES.md`** and collect the required values (VPC, private
   subnet, three secret ARNs, optional KMS CMK, DMS S3 path/bucket).
2. Export the configuration and run the script:

```bash
cd one-click-deployment/oracle-to-postgres-minimal

export AWS_PROFILE=workshop
export AWS_REGION=ap-southeast-2
export STACK_NAME=mma-testmgr-minimal

export VPC_ID=vpc-xxxxxxxx
export EC2_SUBNET_ID=subnet-xxxxxxxx            # PRIVATE subnet with NAT egress

# Prompt for the admin password instead of hard-coding it. This keeps a real
# credential out of the repo and out of shell history, and avoids tripping
# secret scanners (gitleaks/trufflehog) on a committed literal.
read -rsp 'Set an admin password: ' ADMIN_PASSWORD; echo; export ADMIN_PASSWORD

export REPO_DB_SECRET_ARN=arn:aws:secretsmanager:...:secret:my-repo-db-xxxx
export SOURCE_ORACLE_SECRET_ARN=arn:aws:secretsmanager:...:secret:oracle-admin-xxxx
export TARGET_POSTGRES_SECRET_ARN=arn:aws:secretsmanager:...:secret:pg-admin-xxxx
export SECRETS_KMS_KEY_ARN=                     # blank = AWS managed key
export S3_DMS_PROJECT_PATH=s3://my-dms-bucket/my-project
export S3_DMS_PROJECT_BUCKET=my-dms-bucket

./deploy-mma-apps-minimal.sh validate   # optional: validate templates only
./deploy-mma-apps-minimal.sh deploy
```

The script creates an S3 bucket for the nested templates (if needed), uploads
them, validates, and creates/updates the stack. It prints the outputs — including
the exact SSM port-forwarding commands — when finished.

You can also deploy the main template directly in the CloudFormation console;
upload the three files in `mma-nested-stacks/` to an S3 bucket first and set
`TemplateS3Bucket` / `TemplateS3Prefix` accordingly.

## Access

Both apps sit behind nginx on a single HTTPS port (**443**): `/` = VS Code,
`/testmgr/` = Test Manager. Login uses the admin password you set (Test Manager
user is `admin`). The certificate is self-signed, so browsers show a warning the
first time — accept it (see `ACCESS-AND-TROUBLESHOOTING.md` §8 to replace it).

### Step 1 — get the URLs, instance id, and private DNS
```bash
aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --query "Stacks[0].Outputs[].{Key:OutputKey,Value:OutputValue}" --output table
```
This prints `InstanceId`, `PrivateIp`, `StartTunnelCommand`, `TestManagerURL`,
`CodeServerURL`, and (if you set `AllowedClientCidr`) `InVpcTestManagerURL`.

To get the **private DNS hostname** of the host:
```bash
aws ec2 describe-instances --instance-ids <InstanceId> \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --query "Reservations[0].Instances[0].PrivateDnsName" --output text
# e.g. ip-10-1-3-176.<region>.compute.internal
```

### Option A — VPN / Direct Connect (browse the EC2 hostname directly)

This path connects straight to the host's private address, so it needs an
**inbound 443 rule**. It is only present if you deployed with `AllowedClientCidr`
set. Enable it one of two ways:

**Persistent — via the stack** (allow your VPC CIDR or VPN pool, e.g. `10.0.0.0/16`):
```bash
aws cloudformation update-stack --stack-name "$STACK_NAME" \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --use-previous-template \
  --capabilities CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
  --parameters \
    ParameterKey=AllowedClientCidr,ParameterValue=10.0.0.0/16 \
    $(for p in VpcId Ec2SubnetId AdminPassword VSCodeUser InstanceType \
        InstanceVolumeSize KeyPairName CodeRepoUrl CodeRepoBranch StackPrefix \
        TemplateS3Bucket TemplateS3Prefix RepoDBSecretArn SourceOracleSecretArn \
        TargetPostgresSecretArn SecretsKmsKeyArn S3DMSProjectPath \
        S3DMSProjectBucket; do echo ParameterKey=$p,UsePreviousValue=true; done)
```

**Quick test — add the rule directly to the created security group:**
```bash
SG=$(aws cloudformation describe-stack-resources --stack-name "$STACK_NAME" \
  --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --logical-resource-id InstanceSecurityGroup \
  --query "StackResources[0].PhysicalResourceId" --output text)
aws ec2 authorize-security-group-ingress --group-id "$SG" \
  --protocol tcp --port 443 --cidr 10.0.0.0/16 \
  --region "$AWS_REGION" --profile "$AWS_PROFILE"
```

Then browse from a machine on the VPN/DC:
- VS Code: `https://<PrivateDnsName>/?folder=/workshop/MMA-Samples`
- Test Manager: `https://<PrivateDnsName>/testmgr/`  (user `admin` / your admin password)

Notes:
- The private hostname **only resolves if your VPN/DC uses the VPC's DNS**
  (Route 53 Resolver / the VPC `.2` resolver). If it does not resolve, use the
  private IP instead: `https://<PrivateIp>/?folder=/workshop/MMA-Samples` and
  `https://<PrivateIp>/testmgr/`.
- The self-signed cert's name won't match the hostname/IP, so the browser
  warning is expected. For CA-trusted TLS with a real name, front the host with
  an internal ALB + ACM certificate.
- Open 443 only to a specific CIDR — **never `0.0.0.0/0`, and never on a public
  subnet.**

### Option B — SSM port forwarding (no VPN needed; recommended default)

Works with the default no-ingress security group — SSM connects to the host's
`localhost:443` from the inside.

One-time local setup:
1. AWS CLI v2 with credentials for the account.
2. Install the **Session Manager plugin**:
   <https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html>
   (verify with `session-manager-plugin --version`).
3. Your IAM principal needs `ssm:StartSession` on the instance (the instance
   role's managed policies already cover the agent side).

Start the tunnel (local `8443` → host `443`) — this is the `StartTunnelCommand`
output:
```bash
aws ssm start-session --region "$AWS_REGION" --profile "$AWS_PROFILE" \
  --target <InstanceId> \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["443"],"localPortNumber":["8443"]}'
```
Leave it running, then open (accept the self-signed cert warning):
- VS Code: <https://localhost:8443/?folder=/workshop/MMA-Samples>
- Test Manager: <https://localhost:8443/testmgr/>  (user `admin` / your admin password)

Press `Ctrl-C` to end the session. If local port `8443` is busy, change
`localPortNumber` (e.g. `9443`) and use that in the URLs.

### Which to use
`Option B (SSM)` needs no security-group changes and is the recommended default.
`Option A (VPN/DC)` gives direct hostname/IP access without a per-user tunnel but
requires opening 443 to your client CIDR.

See **`ACCESS-AND-TROUBLESHOOTING.md`** for service management and diagnostics.

> **Region:** deploy in the same region as your DMS Schema Conversion S3 bucket.
> If the bucket is elsewhere, copy it into a bucket in the deployment region and
> point the stack at the local copy (see `PREREQUISITES.md`, section 5).

## Clean up

```bash
./deploy-mma-apps-minimal.sh delete
```
This removes only the resources created by this stack (EC2, SG, IAM role,
Lambda, SSM documents). Your VPC, subnets, databases, secrets, KMS key, and DMS
project are left untouched.
