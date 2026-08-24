# Db2 to Aurora PostgreSQL - One-Click Deployment

Provisions **Amazon RDS for Db2** (Community Edition, `Engine: db2-ce`, 12.1,
bring-your-own-licence) as the migration source — **not** self-managed Db2 LUW on
EC2. There is no Db2 engine on the EC2 instance; that box runs the Test Manager,
the MCP servers, and the deployment tooling only.

Also deploys the MMA Test Manager, the Db2 and PostgreSQL MCP servers, and an
optional demo source/target pair for migration testing.

Adapted from `one-click-deployment/oracle-to-postgres/`. The structure, nested
stacks, and SSM automation are unchanged; only the source engine differs.

### Why "LUW" still appears

RDS for Db2 *is* the Db2 LUW (Linux/UNIX/Windows) engine lineage, delivered as a
managed service. So LUW naming is correct in the places it survives, and is not a
leftover from a self-managed design:

- `db2-luw-client-mcp` — the MCP server package name, and its
  `db2-luw-mcp-server-*.jar` artifact.
- DMS engine name `db2`, which means Db2 LUW as distinct from `db2zos` (z/OS).
- `01_user_db2.sql`, referred to as the "self-managed variant" — a script this
  stack deliberately does **not** use.

### Why the setup is RDS-shaped

The managed service withholds `SYSADM`/`SYSCTRL` from the master user, which
drives most of what looks unusual here:

| Self-managed Db2 | RDS for Db2 (this stack) |
|---|---|
| `CREATE BUFFERPOOL` | `CALL rdsadmin.create_bufferpool(...)` |
| `CREATE TABLESPACE` | `CALL rdsadmin.create_tablespace(...)` |
| `useradd` + OS authentication | `CALL rdsadmin.add_user(...)` |
| `CREATE DATABASE` | `DBName: BOBSDB`, provisioned by RDS |

`rdsadmin.*` procedures only resolve while connected to the `RDSADMIN` database,
whereas `CREATE SCHEMA` and `GRANT` must run against `BOBSDB`. That is why the
Oracle track's single `01_user.sql` became three `01_user_rds_db2_phase*.sql`
files here.

> **⚠️ Not yet deployed end-to-end.** The engine-specific RDS and DMS values are
> resolved and the demo schema scripts now ship in this repo, so
> `DeployDemoInfra=true` has everything it needs — but no deployment has been
> attempted. See [Validation status](#validation-status).

## Resolved from reference templates

Engine values were taken from two templates that have been applied against a
real account — `rds-db2-ce.yaml` (RDS for Db2 CE provisioning) and
`dms-sc-project-db2.yaml` (DMS Schema Conversion for a Db2 source):

| Setting | Was | Now |
|---|---|---|
| `EngineVersion` | `12.1` | `12.1.4.0.sb00085812.r1` (parameter `Db2EngineVersion`) |
| `DBInstanceClass` | `db.m6i.large` | `db.m7i.large`, fallback `db.t3.small` (parameter `Db2InstanceClass`) |
| `EnableCloudwatchLogsExports` | `diag`, `notify` | `diag.log`, `notify.log` |
| DMS data provider settings key | `Db2LuwSettings` | `IbmDb2LuwSettings` |
| `S3BucketPath` | `!Ref DmsS3Bucket` (name only) | `!Sub 's3://${DmsS3Bucket}'` |
| Source endpoint `Username` | hardcoded `db2inst1` | resolved from the admin secret |
| `Port` | implicit | `50000`, stated explicitly |

`EngineVersion: '12.1'` would never have matched — RDS requires the
fully-qualified build string. `DatabaseInsightsMode: advanced` and Performance
Insights were removed; the reference template does not set them for `db2-ce`.

`deploy-with-demo-infra.sh` now runs pre-flight checks before creating the
stack, since it deploys with `--disable-rollback` and takes up to 60 minutes.
It validates the admin password against the stack's own `AllowedPattern`, looks
for the License Manager configuration, verifies the engine version and instance
class are actually orderable in the target region (auto-falling back to
`db.t3.small`), and checks DMS reports a `db2` source endpoint type. Bypass with
`SKIP_PREFLIGHT=1`.

The IBM licence IDs are deliberately *not* checked by the script — they are
CloudFormation parameters it never reads. A template `Rule` enforces them
instead, which also covers console and `deploy` invocations that bypass the
script entirely.

## One prerequisite, outside the stack

### IBM Community Edition registration

RDS for Db2 CE (`Engine: db2-ce`, 12.1 only) is **bring-your-own-licence** —
Marketplace licensing is not offered for `db2-ce`. The licence itself is free
from IBM; you pay only for RDS infrastructure. Register to obtain two IDs,
which go into a custom parameter group (`rds.ibm_customer_id`,
`rds.ibm_site_id`, family `db2-ce-12.1`).

Register free at <https://www.ibm.com/account/reg/us-en/signup?formid=urx-54367>.
Without valid IDs the instance fails to create, and AWS may terminate instances
whose licence cannot be verified.

The two IDs are **CloudFormation parameters**, `Db2CustomerId` and `Db2SiteId`
— not environment variables. Both default to empty and are `NoEcho`, with an
`AllowedPattern` of `^[0-9]*$`. Supply them at deploy time (see [Deploy](#deploy)).

A template `Rule` in `mma-apps-main-stack.yaml` asserts both are non-empty when
`DeployDemoInfra=true`, so a missing ID is rejected during validation before
any resource is created. Without it the failure surfaces around 10 minutes in
on the Db2 instance, and since the deploy script runs with `--disable-rollback`
that leaves a partially built environment to clean up by hand.

### License Manager configuration — created in-flight, no longer manual

RDS for Db2 CE also needs a License Manager **self-managed licence
configuration** whose RDS product filter matches `Engine Edition = db2-ce`.
Matching RDS instances associate automatically; nothing attaches it to the
instance. Without it the Db2 instance cannot verify its licence.

`mma-nested-stacks/license-manager-stack.yaml` now creates this **during
deployment**, so a fresh workshop account needs no manual setup.

CloudFormation has no resource type for a self-managed licence configuration —
`AWS::LicenseManager::License` and `::Grant` model licences *granted* to you,
typically Marketplace-delivered, which is a different thing. So this is a
Lambda-backed custom resource wrapping `CreateLicenseConfiguration`, following
the same shape as the existing `ssm-waiter-stack.yaml`.

Because a licence configuration is **regional and account-wide**, the resource is
written to converge rather than assume an empty account:

| Situation | Behaviour |
|---|---|
| No `db2-ce` configuration in the region | Creates one named `db2-ce-byol` |
| One already exists | **Adopts** it — no duplicate, no failure |
| Two stacks race in one region | Create fails, re-checks, adopts the winner |
| Configurations for other engines exist | Ignored; only `db2-ce` filters match |
| Stack delete, configuration was created by it | Deletes it |
| Stack delete, configuration was **adopted** | **Leaves it in place** |
| Stack delete, the delete API errors | Logs a warning and still succeeds |

That last row is deliberate: an orphaned licence configuration is harmless and
free, whereas a stack stuck in `DELETE_FAILED` is not. Created-versus-adopted is
encoded in the `PhysicalResourceId` (`created:` / `adopted:` prefix), because a
Delete event receives only that id — not the data returned on create.

`LicenseManagerStack` is gated on `DeployDemo`, and `DemoInfraStack` declares
`DependsOn: [ComputeStack, LicenseManagerStack]` so the configuration exists
before RDS provisions Db2. Two stack outputs report the result:
`Db2LicenseConfigurationArn` and `Db2LicenseConfigurationCreated`
(`true` = this stack created it, `false` = adopted).

The deploy role needs `license-manager:CreateLicenseConfiguration`,
`ListLicenseConfigurations`, `GetLicenseConfiguration` and
`DeleteLicenseConfiguration`. The pre-flight check now only *reports* whether a
configuration exists — it no longer fails, since failing would have blocked the
very deploy that creates it.

### Demo schema scripts (in the repo, no asset to publish)

The Db2 demo scripts are **committed in this repo** at
`one-click-deployment/db2-to-postgres/demo-scripts/`. They arrive on the
instance inside the `CodeURL` code archive that the stack already downloads, and
are read straight from `/workshop/MMA-Test-Manager/`. There is no separate
scripts archive to build, host or keep in sync, and the scripts cannot drift
from the `Db2ScriptRunner` and SSM document that consume them.

`DemoSQLScriptsURL` now defaults to **empty**, meaning "use the committed
scripts". It previously pointed at a `db2-scripts.zip` on the Oracle track's
asset bucket that was never published, so `DeployDemoInfra=true` built an empty
database. The parameter is retained purely as an override: set it to a zip URL
to test revised scripts without rebuilding the code archive.

Committed scripts:

| File | Runs against | As |
|---|---|---|
| `01_user_rds_db2_phase1.sql` | `RDSADMIN` | master — bufferpool, tablespaces, users |
| `01_user_rds_db2_phase2.sql` | `BOBSDB` | master — schema and privileges |
| `02_schema_db2_v2.sql` | `BOBSDB` | `DEMO` — tables |
| `03_data_db2_v2.sql` | `BOBSDB` | `DEMO` — data |
| `04_complex_schema_db2_v2.sql` | `BOBSDB` | `DEMO` — routines, triggers |
| `01_user_rds_db2_phase3.sql` | `BOBSDB` | master — read-only grants, runs last |

`demo_cleanup_db2.sql` and `reload_data_db2.sql` are included for interactive
workshop use; the stack does not run them.

The self-managed `01_user_db2.sql` is deliberately **not** included: it issues
native `CREATE BUFFERPOOL`/`CREATE TABLESPACE`, which RDS rejects because the
master user lacks `SYSADM`/`SYSCTRL`.

`SetupEnvironmentDocument` stages the scripts and verifies all six required
files are present and non-empty before `DeployDb2SchemaDocument` runs, so an
incomplete set fails early with an explicit list rather than midway through
deployment.

**Repo download is now its own step.** `DownloadCode` was split out of
`ApplicationDeploymentDocument` into a new `RepoDownloadDocument`, with a
`RunRepoDownload` waiter that both downstream branches depend on:

```
RunRepoDownload
├── RunApplicationDeployment          (build-all.sh, the slow Maven build)
└── RunDemoSetupEnvironment → RunDemoDb2Schema
```

Previously the download lived inside the application branch, which runs in
*parallel* with the demo branch — so the demo branch's reads of
`/workshop/MMA-Test-Manager` raced against the unzip. That race already affected
`Db2ScriptRunner.java`; hosting the SQL scripts in the repo would have widened
it. The build stays parallel with schema deployment, so no wall-clock time is
added.

`DeployDb2SchemaDocument` executes the scripts over JDBC using
`db2-luw-client-mcp/tools/Db2ScriptRunner.java`, so no IBM Db2 client install is
required — which matters because the IBM Data Server Driver Package needs an IBM
account and cannot be fetched unattended. The document has four steps:

1. **`prepareScripts`** — downloads the JCC driver from Maven Central, copies in
   `Db2ScriptRunner.java`, strips `CONNECT` lines (JDBC has no `CONNECT`
   statement), and prepends `--#SET TERMINATOR #` to `04`.
2. **`deployRdsSetup`** — `phase1` against `RDSADMIN`, then `phase2` against
   `BOBSDB`, both as the master user.
3. **`deployDemoSchema`** — `02`/`03`/`04` as `DEMO`, then `phase3` as master.
4. **`updateSecrets`** — patches `host`/`engine` into the secrets.

Two things to know before the first live run:

- **`Db2ScriptRunner` continues past failures.** Each statement commits
  individually; a failure is logged with SQLSTATE, rolled back, and execution
  proceeds, so one "already exists" does not abort a deployment. The process
  still exits non-zero, so the SSM step fails loudly — but a broken `phase2`
  will not stop `02` from running and cascading errors. Read the step output
  rather than trusting the exit code alone.
- **No credential is committed.** The scripts carry a `USING <DEMO_PASSWORD>`
  placeholder rather than a password. For `02`/`03`/`04` the placeholder is
  removed along with their `CONNECT` lines, since `Db2ScriptRunner` authenticates
  from its own arguments; for the phase files it is substituted at deploy time.
  The `DEMO` password is currently set in `deployRdsSetup` — a throwaway account
  in a private subnet. To source it from Secrets Manager instead, add a `DEMO`
  secret alongside `DemoDb2TestUserSecret` and read it there.
  <br>Note the two helper scripts (`demo_cleanup_db2.sql`, `reload_data_db2.sql`)
  are **not** run by the stack, so their placeholder is never substituted — replace
  it, or connect first, before running them by hand. Each carries a header note.

Performance note: `03_data_db2_v2.sql` is ~2.6 MB / ~576 statements and
`Db2ScriptRunner` commits per statement, so expect ~576 round-trips plus commits
against the step's 600 s timeout. If it proves too slow, batching or enabling
autocommit for the data script would be the fix.

### DMS Schema Conversion source support

The data-provider settings key and the `db2` engine name are confirmed, and the
pre-flight check verifies DMS accepts a `db2` **source endpoint**. Schema
Conversion for **Db2 LUW → Aurora PostgreSQL** is a separate capability —
confirm it has launched in your region before relying on Modules 2 and 4, which
are built on schema conversion and generated comparison tests. DMS
distinguishes `db2` (LUW) from `db2zos` (z/OS).

**The conversion steps are automated.** With `DeployDemoInfra=true`,
`DMSSchemaConversionDocument` (`<prefix>-dms-schema-conversion`) runs
assessment → conversion → export-to-target, polling each to completion. It is
wired in as the `RunDMSSchemaConversion` step, gated on `DeployDemo` and
depending on both the Db2 and PostgreSQL schema deployments. Nothing needs to be
run by hand for the demo path — this mirrors the Oracle track exactly.

The deploy scripts print the equivalent CLI calls on completion, for re-running
an individual step or for `DeployDemoInfra=false`. Selection rules use
`schema-name: DEMO`, matching the `SourceSchema` default in the document and the
schema the demo scripts actually create. (`DB2INST1`, the master user's implicit
schema, holds none of the demo objects — an earlier revision of the printed
guidance named it, which would have produced an empty conversion.)

> **⚠️ Known gap: `start-metadata-model-import` is never called.** The document
> runs `-assessment`, `-conversion` and `-export-to-target`, but not the import
> that populates the source metadata model first. **The Oracle track has the
> identical omission**, so this is inherited rather than Db2-specific, and the
> Oracle path is reported to work — which suggests assessment may trigger the
> import implicitly. That has not been confirmed from a run. If assessment fails
> for want of a source metadata model, run the printed
> `start-metadata-model-import ... --origin SOURCE` command and re-run the SSM
> document.

### Db2 client on the EC2 instance (optional, not installed)

This is about an optional *client* for interactive SQL access. The Db2 **engine**
runs in RDS; nothing installs a Db2 server on the EC2 instance.

The MCP server needs **no** Db2 client — the IBM JCC driver is bundled in its
fat JAR from Maven Central. A client is only useful for interactive shell
access, the role SQLCL/SQLPlus plays in the Oracle track.

`compute-stack.yaml` currently **skips** the client install. The IBM Data Server
Driver Package needs an IBM account, so it can't be fetched unattended; host it
in S3 and uncomment the Option (a) block if you want it.

### Your own RDS for Db2 template

To swap in your own, replace the `Db2DB` resource and `Db2ParameterGroup` with
a nested stack reference, keeping the `Db2Endpoint`, `Db2AdminSecretArn`, and
`Db2TestUserSecretArn` outputs so `application-setup.yaml` still wires the MCP
configuration.

## What is already wired up

- `db2-luw-client-mcp` added to `build-all.sh`, so provisioning builds the JAR. Unlike `sybase-client-mcp`, no manual driver install is needed — the JCC driver resolves from Maven Central.
- `docs/sample-db2-postgres-mma-agent-for-kiro-cli.json` created and copied to `~/.kiro/agents/mma-agent.json` by `application-setup.yaml`, with `MMA-Samples` rewritten to `MMA-Test-Manager`.
- Secret ARNs are `sed`-ed into `db2-luw-client-mcp/application-secretsmanager.properties` at provision time.
- Secrets carry `host`, `port` 50000, `dbname` `BOBSDB`, `username` `db2inst1`, `engine` `db2` — matching what `DatabaseConfig` reads. `mcp.db.connection.schema` is set to `DEMO` so unqualified names resolve to the demo schema rather than to `db2inst1`.
- Security groups open TCP 50000 instead of 1521.
- Test Manager `mma.sourcedb.*` points at the Db2 secret.

## Deploy

```bash
export ADMIN_PASSWORD='<8+ chars, upper/lower/digit/special, no $ # { }>'
./deploy-with-demo-infra.sh <stack-prefix> \
  ParameterKey=Db2CustomerId,ParameterValue=<your IBM customer ID> \
  ParameterKey=Db2SiteId,ParameterValue=<your IBM site ID>
```

Arguments after the stack prefix are forwarded verbatim to `create-stack
--parameters`, so the same mechanism overrides any other parameter without
editing the template. The licence IDs are passed this way rather than through
the environment so they live in one place — the template's parameter
declarations — and appear in the stack's parameter list.

Alternatively set `Default` values on `Db2CustomerId` / `Db2SiteId` in
`mma-apps-main-stack.yaml` and run `./deploy-with-demo-infra.sh <stack-prefix>`
with no extra arguments. Do that only for a private throwaway copy: committing
licence IDs into a shared template checks them into version control.

Use `deploy-with-demo-infra_cloudfront.sh` to front the Test Manager with
CloudFront. Optional environment overrides: `AWS_REGION`, `DB2_ENGINE_VERSION`,
`DB2_INSTANCE_CLASS`, `SKIP_PREFLIGHT=1`.

### RDS deletion protection (off by default)

`EnableDeletionProtection` controls `DeletionProtection` on all three RDS
resources — the Test Manager repository cluster, the demo Db2 instance, and the
demo Aurora cluster. It **defaults to `false`** so the stack tears down with a
single `delete-stack`.

The Oracle and SQL Server tracks hardcode this to `true`, which means teardown
there fails with:

```
Cannot delete protected Cluster <prefix>-testmgr-repo-cluster,
please disable deletion protection and try again
```

and each resource has to be cleared by hand before the delete succeeds.

> **⚠️ With `false`, deleting the stack destroys the repository database and
> everything in it** — test definitions, run history, comparison results — with
> no confirmation prompt. That is usually what you want for a disposable
> workshop environment. Set `EnableDeletionProtection=true` for anything whose
> contents you would miss:
>
> ```bash
> ./deploy-with-demo-infra.sh <prefix> \
>   ParameterKey=EnableDeletionProtection,ParameterValue=true ...
> ```

To clear protection on an existing protected stack before deleting it (note
`modify-db-cluster` for the Aurora clusters, `modify-db-instance` for Db2):

```bash
REGION=<region>; PREFIX=<stack-prefix>
aws rds modify-db-cluster  --region $REGION --db-cluster-identifier  ${PREFIX}-testmgr-repo-cluster    --no-deletion-protection --apply-immediately
aws rds modify-db-cluster  --region $REGION --db-cluster-identifier  ${PREFIX}-demo-aurora-pg-cluster  --no-deletion-protection --apply-immediately
aws rds modify-db-instance --region $REGION --db-instance-identifier ${PREFIX}-demo-db2-source         --no-deletion-protection --apply-immediately
```

A resource still mid-creation returns `InvalidDBClusterStateFault`; wait for
`available` and retry.

### Network: an existing VPC is a prerequisite

**No template in this repo creates a VPC.** `VpcId`, `Ec2SubnetId`,
`PrivSubnetId1` and `PrivSubnetId2` are typed CloudFormation parameters
(`AWS::EC2::VPC::Id` / `AWS::EC2::Subnet::Id`) that consume an existing network;
`network-stack.yaml` only creates a security group. The workshop environment
normally supplies the VPC, which is why none of the three tracks provisions one.

By default the deploy scripts discover the MMA demo VPC by tag. Override either
the IDs or the tag names through the environment:

| Variable | Default | Purpose |
|---|---|---|
| `VPC_ID` | discovered by tag | Use this VPC, skip discovery |
| `EC2_SUBNET_ID` | discovered by tag | Public subnet for EC2 |
| `PRIV_SUBNET_ID_1` | discovered by tag | Private subnet, AZ 1 |
| `PRIV_SUBNET_ID_2` | discovered by tag | Private subnet, AZ 2 |
| `VPC_NAME_TAG` | `MMA-vpc` | Tag to search for instead |
| `EC2_SUBNET_NAME_TAG` | `MMA-subnet-public1-AvailabilityZone1` | |
| `PRIV_SUBNET_1_NAME_TAG` | `MMA-subnet-private1-AvailabilityZone1` | |
| `PRIV_SUBNET_2_NAME_TAG` | `MMA-subnet-private2-AvailabilityZone2` | |

Fully explicit:

```bash
export ADMIN_PASSWORD='<8+ chars, upper/lower/digit/special, no $ # { }>'
AWS_REGION=eu-west-1 \
VPC_ID=vpc-0123456789abcdef0 \
EC2_SUBNET_ID=subnet-public1 \
PRIV_SUBNET_ID_1=subnet-private1 \
PRIV_SUBNET_ID_2=subnet-private2 \
./deploy-with-demo-infra.sh <stack-prefix> \
  ParameterKey=Db2CustomerId,ParameterValue=<id> \
  ParameterKey=Db2SiteId,ParameterValue=<id>
```

Or keep discovery and just retarget the tags:

```bash
VPC_NAME_TAG=my-vpc EC2_SUBNET_NAME_TAG=my-public-subnet \
PRIV_SUBNET_1_NAME_TAG=my-private-a PRIV_SUBNET_2_NAME_TAG=my-private-b \
./deploy-with-demo-infra.sh <stack-prefix> ...
```

Mixing works too — an explicit `VPC_ID` with tag-discovered subnets, for example,
since the subnet lookups filter on whichever `VPC_ID` is in effect.

Pass network values **only** through these variables, not as trailing
`ParameterKey=VpcId,...` arguments: `create-stack` already sets those four
explicitly and CloudFormation rejects a parameter supplied twice.

The scripts validate before creating the stack — a missed lookup prints the tags
it tried and the three ways to fix it, and the two private subnets are checked
to be distinct **and** in different AZs, which the RDS and Aurora subnet groups
require. Both failures cost seconds instead of surfacing minutes into a deploy
that runs with `--disable-rollback`.

To see what exists in a region:

```bash
aws ec2 describe-vpcs --region <region> \
  --query "Vpcs[].{VpcId:VpcId,Name:Tags[?Key=='Name']|[0].Value}" --output table
```

## Validation status

**Verified:** both deploy scripts pass `bash -n`; the password-complexity,
subnet-resolution, engine-version matching, and extra-argument forwarding logic
(including the empty-array case under `set -u`) were unit-tested against valid
and invalid inputs; the licence-ID `Rule` truth table was checked across all six
`DeployDemoInfra`/ID combinations; `mma-apps-main-stack.yaml`,
`application-setup.yaml`, `demo-infrastructure.yaml` and `compute-stack.yaml`
parse as YAML; the `Rule` references only declared parameters and the empty
`Default` satisfies each `AllowedPattern`; nested-stack parameter wiring is
consistent (no undeclared or missing required parameters) and every
`ParameterKey` the script passes exists in the main stack.

For the repo-hosted scripts change specifically: every rewritten SSM shell body
passes `bash -n`; the `RunRepoDownload` → application/demo `DependsOn` chain was
asserted from the parsed template; the six required filenames referenced by the
templates all exist in `demo-scripts/`; the `04` terminator guard was verified to
fire on the current file and to be idempotent once the directive is present; and
no live reference to the old `db2-scripts.zip` asset remains.

For the License Manager custom resource: the inline Lambda compiles under
`py_compile`, and the handler was unit-tested offline against a stubbed
`license-manager` client across eight scenarios — create in an empty account,
adopt a pre-existing configuration, delete one it created, refuse to delete an
adopted one, tolerate a delete API error, recover from a create race by adopting,
ignore configurations for other engines, and treat Update as a no-op. The
`DependsOn` ordering placing it before `DemoInfraStack` was asserted from the
parsed template.

**Not verified:** no `cfn-lint`, no `validate-template`, and no deployment
attempt. The engine values come from templates reported as working, not from a
run in your account — which is what the pre-flight checks exist to catch. The
`IbmDb2LuwSettings` key and `db2-ce` version string were not independently
confirmed against the live CloudFormation or RDS API. The Db2 schema deployment
has never been executed against a real RDS for Db2 instance, so the SQL itself
and the `03_data` runtime against the 600 s step timeout remain unproven.

DMS Schema Conversion is unproven for this source engine: whether
`start-metadata-model-assessment` succeeds without a preceding
`start-metadata-model-import` (see the gap noted above) can only be settled by a
run, and Db2 LUW → Aurora PostgreSQL conversion availability is region-dependent.

The License Manager custom resource has never run against the real
`license-manager` API. The `Engine Edition` / `db2-ce` filter shape matches what
the console and CLI produce, but whether RDS then associates the Db2 instance
with the configuration — and whether the licence actually verifies — is exactly
what a first deployment will establish.
