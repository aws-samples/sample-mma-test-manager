# Prerequisites — MMA Test Manager (Minimal Deployment)

This minimal deployment assumes you **already run** the migration platform and
just want to add the VS Code (code-server) workspace and the MMA Test Manager on
a single private-subnet EC2 host. Unlike the full one-click deployment, it does
**not** create a VPC, a repository database, or any sample/demo Oracle,
PostgreSQL, or DMS resources — you point it at the resources you already own.

Work through the checklist below and collect the values in the
[Values to collect](#values-to-collect) table at the end. You feed those values
to `deploy-mma-apps-minimal.sh` (or directly as CloudFormation parameters).

---

## 1. Networking

| Requirement | Details |
|---|---|
| **VPC** | An existing VPC. Collect its `VpcId`. |
| **Private subnet** | One subnet for the EC2 host. **Use a private subnet** with **outbound internet via a NAT gateway** (or equivalent egress). No public IP is assigned. Deploying on a public subnet is strongly discouraged — it exposes the workspace and the databases it can reach. |
| **DNS** | The VPC must have `enableDnsSupport` and `enableDnsHostnames` enabled so the host can resolve RDS/Secrets Manager/S3 endpoints. |

Why outbound internet is required: during setup the host installs code-server,
nginx, Java 21, Maven, the Oracle/PostgreSQL clients, and the Kiro CLI, and it
clones the Test Manager repository from GitHub. These come from public
endpoints, so a NAT gateway (or a proxy/mirror you provide) is required.

By default the stack creates a security group with **no ingress** — you reach
the applications through AWS Systems Manager (SSM) port forwarding (see
`ACCESS-AND-TROUBLESHOOTING.md`). If in-VPC clients (bastion/VPN/Direct Connect)
need direct browser access, set the optional `AllowedClientCidr` parameter to
open **only 443** from that CIDR. Never set it to `0.0.0.0/0`.

### SSM connectivity

The host must be able to reach the SSM service. Either:
- the NAT gateway above (simplest), **or**
- interface VPC endpoints for `ssm`, `ssmmessages`, and `ec2messages`.

---

## 2. Databases (you provide all three)

| Database | Purpose | Notes |
|---|---|---|
| **Test Manager repository DB** | Stores Test Manager metadata (test cases, runs, results). | PostgreSQL (e.g. Aurora PostgreSQL or RDS PostgreSQL). Must be reachable from the EC2 subnet on 5432. |
| **Source Oracle DB** | The migration source. | Reachable from the EC2 subnet on its listener port (typically 1521). |
| **Target PostgreSQL DB** | The migration target. | Reachable from the EC2 subnet on 5432. |

**Security groups:** add an inbound rule on **each** database's security group
allowing traffic from the EC2 host's security group (created by this stack) on
the relevant port. Because the stack's security group is created at deploy time,
the simplest sequence is:
1. Deploy this stack once.
2. Read the created security group id from the EC2 instance
   (tag `Name = <StackPrefix>-mma-apps-sg`).
3. Add that SG as an allowed source on your three database security groups.

Alternatively, if your databases already allow the whole VPC CIDR, no change is
needed.

---

## 3. Secrets Manager secrets

Create (or reuse) **three** Secrets Manager secrets, all in the **same region as
your deployment**. Collect each secret's ARN. The samples below mirror the shape
the Test Manager and the DMS Schema Conversion data providers use.

> Port may be a number or a string — both work. `engine` is informational.

### 3a. Repository DB secret (`RepoDBSecretArn`)

This is the Test Manager's own metadata database. It is an ordinary PostgreSQL
database and **can live on the same PostgreSQL server you use as the Oracle→
PostgreSQL migration target** — just use a **separate database and a dedicated,
non-master user** (do not reuse the `postgres` master account).

**Step 1 — create a normal user and the database** (run as the master user,
e.g. `postgres`, against your PostgreSQL/Aurora server):
```sql
-- 1. Dedicated login role (choose a strong password)
CREATE ROLE testmgr LOGIN PASSWORD 'ChangeMe_StrongPassword';

-- 2. Dedicated database owned by that role
CREATE DATABASE testmgr_repo OWNER testmgr;

-- 3. Grant privileges on that database only (least privilege)
\connect testmgr_repo
GRANT ALL PRIVILEGES ON DATABASE testmgr_repo TO testmgr;
GRANT ALL ON SCHEMA public TO testmgr;   -- PostgreSQL 15+ needs this explicitly
```
The app uses Flyway to create its own tables on first start, so `testmgr` needs
`CREATE` on schema `public` (granted above). It does **not** need superuser.

**Step 2 — create the secret** (`port` here is the PostgreSQL port):
```bash
aws secretsmanager create-secret \
  --name mma-testmgr-repo \
  --region <region> \
  --secret-string '{
    "username": "testmgr",
    "password": "ChangeMe_StrongPassword",
    "host": "my-aurora.cluster-xxxx.<region>.rds.amazonaws.com",
    "port": 5432,
    "dbname": "testmgr_repo",
    "engine": "postgres"
  }'
```
Use the returned ARN as `RepoDBSecretArn`.

### 3b. Source Oracle secret (`SourceOracleSecretArn`)

Credentials for the Oracle source. Example (matching an RDS/OCI-style Oracle
secret; `dbname` is the SID/service name):
```json
{
  "username": "admin",
  "password": "ChangeMe_OraclePassword",
  "host": "oracledb.example.internal",
  "port": 1521,
  "dbname": "ORCL",
  "engine": "oracle"
}
```
```bash
aws secretsmanager create-secret --name mma-source-oracle --region <region> \
  --secret-string '{"username":"admin","password":"ChangeMe_OraclePassword","host":"oracledb.example.internal","port":1521,"dbname":"ORCL","engine":"oracle"}'
```

### 3c. Target PostgreSQL secret (`TargetPostgresSecretArn`)

Credentials for the migration target PostgreSQL. Example:
```json
{
  "username": "postgres",
  "password": "ChangeMe_PostgresPassword",
  "host": "my-aurora.cluster-xxxx.<region>.rds.amazonaws.com",
  "port": 5432,
  "dbname": "demodb",
  "engine": "postgres"
}
```
```bash
aws secretsmanager create-secret --name mma-target-postgres --region <region> \
  --secret-string '{"username":"postgres","password":"ChangeMe_PostgresPassword","host":"my-aurora.cluster-xxxx.<region>.rds.amazonaws.com","port":5432,"dbname":"demodb","engine":"postgres"}'
```

> **Reuse tip:** your DMS Schema Conversion project already references a **source**
> and **target** data-provider secret. Those are exactly 3b and 3c — reuse their
> ARNs instead of creating new secrets.

> **IAM to create/read secrets:** the operator creating secrets needs
> `secretsmanager:CreateSecret` (and `kms:GenerateDataKey`/`kms:Decrypt` on the
> CMK if you encrypt with a customer managed key). The EC2 host only needs
> `secretsmanager:GetSecretValue`, which the stack grants automatically for the
> three ARNs you pass in.

---

## 4. Encryption key (KMS) — optional

The logic is simple:

- **You provide a CMK** → set `SecretsKmsKeyArn` to your key ARN. The stack
  grants the EC2 role `kms:Decrypt` + `kms:DescribeKey` on that key, and your
  **key policy must also allow this account/role to use the key** (see IAM
  below).
- **You leave it blank** → the secrets are assumed to use the AWS managed key
  `aws/secretsmanager`. No extra KMS grant is created and none is required.

### Required KMS key-policy statement (only when using a CMK)

If your secrets are encrypted with a customer managed key, that key's **key
policy** must permit decryption by principals in this account. A minimal
statement:

```json
{
  "Sid": "AllowAccountDecryptViaSecretsManager",
  "Effect": "Allow",
  "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:root" },
  "Action": ["kms:Decrypt", "kms:DescribeKey"],
  "Resource": "*",
  "Condition": {
    "StringEquals": { "kms:ViaService": "secretsmanager.<region>.amazonaws.com" }
  }
}
```

The stack additionally attaches an **identity** policy to the EC2 role granting
`kms:Decrypt`/`kms:DescribeKey` on the ARN you pass in `SecretsKmsKeyArn`. Both
the key policy (resource side) and the role policy (identity side) must allow
the action.

---

## 5. DMS Schema Conversion project

You should already have a DMS Schema Conversion (SC) project whose output is
stored in S3.

| Value | Example | Used for |
|---|---|---|
| `S3DMSProjectPath` | `s3://mma-dms-sc-694666012041/dms-sc-migration-project` | Written into the Test Manager config (`mma.s3.default-path`). |
| `S3DMSProjectBucket` | `mma-dms-sc-694666012041` | Scopes the EC2 read permission (`s3:GetObject`, `s3:ListBucket`). |

Find the S3 path from your migration project:
```bash
aws dms describe-migration-projects --region <region> \
  --query "MigrationProjects[].SchemaConversionApplicationAttributes.S3BucketPath"
```
The full path is `s3://<that-bucket>/<MigrationProjectName>`.

### Region: keep the bucket in the deployment region (or copy it there)

The Test Manager reads this bucket using a same-region S3 client. **Deploy the
Test Manager in the same region as the DMS SC bucket.** If your bucket is in a
different region than where you want the Test Manager (e.g. bucket in
`ap-southeast-2`, deployment in `ap-southeast-4`), the simplest, most robust
option is to **copy the project into a bucket in the deployment region** and
point the stack at the local copy:

```bash
# Create a bucket in the DEPLOYMENT region (once)
aws s3 mb s3://my-dms-sc-local --region <deployment-region>

# Copy the DMS SC project across regions (run from anywhere with access to both)
aws s3 sync s3://<source-bucket>/<project> s3://my-dms-sc-local/<project> \
  --source-region <bucket-region> --region <deployment-region>
```

Then set `S3DMSProjectPath=s3://my-dms-sc-local/<project>` and
`S3DMSProjectBucket=my-dms-sc-local`. Re-run the copy whenever you regenerate the
Schema Conversion output. This avoids cross-region reads at runtime and keeps
the deployment self-contained.

> **IAM for the copy:** the operator running `aws s3 sync` needs
> `s3:GetObject`/`s3:ListBucket` on the source bucket and
> `s3:PutObject`/`s3:ListBucket` on the destination bucket. The EC2 host role
> only ever reads the **local** bucket you pass as `S3DMSProjectBucket`.

---

## 6. IAM permissions summary

### 6a. Permissions the stack grants to the EC2 host (created for you)
- `AmazonSSMManagedInstanceCore` (managed) — SSM connectivity/session.
- `CloudWatchAgentServerPolicy` (managed) — metrics/logs.
- `secretsmanager:GetSecretValue` on the three secret ARNs you provide.
- `s3:GetObject` + `s3:ListBucket` on the DMS SC bucket you provide.
- `kms:Decrypt` + `kms:DescribeKey` on `SecretsKmsKeyArn` — **only if** you
  supply a CMK.

> **If the DMS SC bucket uses SSE-KMS** with a customer managed key, the host
> role also needs `kms:Decrypt` on **that** key (in the bucket's region). The
> template does not add this automatically — attach it to the created role, or
> use SSE-S3/default encryption on the (local) bucket.

### 6b. Permissions the person running the deployment needs
- CloudFormation: create/update/delete stacks and nested stacks.
- IAM: create role/instance-profile/policy (`CAPABILITY_IAM`).
- EC2: create security group and instance.
- Lambda: create the SSM-waiter function.
- SSM: `SendCommand` and read command invocations.
- S3: create/write the template bucket used for nested stacks.

### 6c. Key-policy permission
- If using a CMK, update the key policy as shown in [section 4](#4-encryption-key-kms--optional).

---

## Values to collect

| Parameter | Your value |
|---|---|
| `VpcId` | |
| `Ec2SubnetId` (private) | |
| `AdminPassword` | |
| `VSCodeUser` (default `awsmma`) | |
| `RepoDBSecretArn` | |
| `SourceOracleSecretArn` | |
| `TargetPostgresSecretArn` | |
| `SecretsKmsKeyArn` (blank if AWS managed key) | |
| `S3DMSProjectPath` | |
| `S3DMSProjectBucket` | |
| `InstanceType` (default `t3.large`) | |
| `InstanceVolumeSize` (default `50`) | |
| `KeyPairName` (optional) | |

Once collected, continue with the deployment steps in `README.md`.
