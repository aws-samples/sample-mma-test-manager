# db2-to-postgres — status as of 2026-08-19

Written at the end of a day of deployment attempts. Read this before the README
if you are picking the work back up: the README describes how the stack is
*meant* to work, this describes what has actually been proven.

## Bottom line

**Root cause of the repeated `RunEC2BaseSetup` failure is identified and fixed:**
the VPC's SSM interface endpoint security group did not allow the workshop
instance on 443. See the blocker section below - it needs reapplying for each new
stack.

**No deployment has completed.** The furthest any attempt reached was
`RunEC2BaseSetup`, the first SSM step, roughly 10 minutes in. Everything past
that point — Db2 schema deployment, `rdsadmin.*` phases, licence verification,
DMS schema conversion, data migration — has never executed.

All template changes are lint-clean (`cfn-lint`, exit 0) and unit-tested offline
where testable. None is validated against a live deployment.

## THE OPEN BLOCKER: SSM agent cannot reach the VPC interface endpoint

**ROOT CAUSE IDENTIFIED** from the instance console output. Not guesswork:

```
SSM Agent unable to acquire credentials: <error>... Error: RequestError: send request failed
caused by: Post "https://ssm.eu-west-1.amazonaws.com/": dial tcp 10.0.41.71:443: i/o timeout</error>
```

`10.0.41.71` is a **private RFC1918 address**. `ssm.eu-west-1.amazonaws.com` is
resolving to a **VPC interface endpoint inside the VPC**, not to the public AWS
endpoint — and that endpoint is not reachable from the instance. The agent is
running and trying; the TCP connection to the endpoint times out, so it can never
register, so `SendCommand` fails with `InvalidInstanceId`.

Everything else was verified healthy on the failing instance
(`i-0e409b97ba97a7043`):

| Check | Result |
|---|---|
| cloud-init | finished cleanly in 8.55s |
| Public IP | `18.201.61.216` assigned |
| Instance profile | `mma-db2-EC2InstanceProfile-vJrGlbeTKyBd` attached |
| Subnet internet route | `igw-03c53ff0ae2037b6c` present |
| Security group egress | no egress rules, so default allow-all |
| SSM agent | running, actively attempting to register |

This is why the earlier subnet and IGW checks all looked correct: with private DNS
enabled on an interface endpoint, the agent never attempts the internet path at
all. The IGW is irrelevant to this failure.

### The fix — CONFIRMED

The VPC has all three required SSM interface endpoints (`ssm`, `ssmmessages`,
`ec2messages`) plus `ec2`, `rds` and `secretsmanager`, all with private DNS
enabled, all guarded by one security group: **`sg-04dfc0d2c14dbddfe`**.

That group allowed TCP 443 from only four specific security groups — none of them
the MMA group — so the workshop instance could not reach the endpoint its DNS
resolved to. Nothing was open to the VPC CIDR.

Fix applied 2026-08-19, `Return: true`:

```bash
MMA_SG=$(aws ec2 describe-security-groups --region eu-west-1 \
  --filters "Name=tag:Name,Values=<prefix>-mma-apps-sg" \
  --query "SecurityGroups[0].GroupId" --output text)

aws ec2 authorize-security-group-ingress --region eu-west-1 \
  --group-id sg-04dfc0d2c14dbddfe \
  --protocol tcp --port 443 --source-group $MMA_SG
```

**THIS MUST BE REDONE FOR EVERY NEW STACK.** The rule references the MMA security
group by ID, and each deployment creates a new group with a new ID. The rule added
on 2026-08-19 pointed at `sg-04a5d53c465ea5107`, which belonged to the stack that
was being deleted at the time — so it is almost certainly stale already.

Three options, in increasing order of robustness:

1. **Re-run the command above after every stack create.** Works, but it is a
   manual step between `CREATE_IN_PROGRESS` and the first SSM waiter, which is a
   race — the waiter starts polling as soon as the instance exists. In practice the
   registration wait gives roughly 14 minutes of slack, so there is time.
2. **Allow 443 from the VPC CIDR** on `sg-04dfc0d2c14dbddfe` instead of from
   specific groups. One rule, permanent, no per-stack action. Broader than
   least-privilege but the endpoints are already private to the VPC.
3. **Do not use the endpoint path at all.** If the EC2 subnet has an IGW route
   (it does: `igw-03c53ff0ae2037b6c`), the agent would reach SSM over the internet
   if private DNS were not overriding the name. Disabling `PrivateDnsEnabled` on
   the SSM endpoints would achieve that, but it affects every other consumer of
   those endpoints in the VPC and is not a safe unilateral change.

Option 2 is the pragmatic choice for a workshop VPC. Option 1 is fine for a
one-off test.

### Why the templates cannot fix this

`sg-04dfc0d2c14dbddfe` and the endpoints are **pre-existing VPC infrastructure**,
created outside this repo. No template here manages them, and the stack has no
handle on them. The same stack would deploy unchanged in a VPC without SSM
endpoints, because the instance would then reach SSM over the IGW.

This belongs in the documented prerequisites next to the VPC requirement: a
workshop VPC that has SSM interface endpoints with private DNS enabled must also
permit the workshop instance to reach them on 443.

### Disproven hypotheses (do not revisit)

- Step timeout in the SSM document
- Wrong or private subnet (`igw-` route confirmed present)
- Boot delay from `yum update -y` in UserData — removed, and the instance still
  failed after 841s of polling

## Second blocker: CodeURL

`CodeURL` (`mma-apps-main-stack.yaml`) defaults to a published Workshop Studio
asset that **predates all Db2 work**. Verified by download: no
`db2-luw-client-mcp/`, no `demo-scripts/`, `one-click-deployment/` contains only
the oracle and sqlserver tracks, and `build-all.sh` has zero Db2 references.

`RunDemoSetupEnvironment` will fail on the missing `demo-scripts/` directory
until this is replaced.

For testing, build a zip from the working tree and override the parameter:

```bash
cd <repo root>
zip -r /tmp/mma-apps.zip . -x '*/target/*' '*.DS_Store' '*/.git/*' '*.zip'
# upload, then:
./deploy-with-demo-infra.sh <prefix> \
  ParameterKey=CodeURL,ParameterValue=https://<bucket>.s3.<region>.amazonaws.com/mma-apps.zip ...
```

Files must be at the **top level** of the zip — no wrapper directory — because
the SSM document does `unzip -d /workshop/MMA-Test-Manager`. A GitHub archive URL
will not work as-is for this reason.

**Handover consequence worth raising in the PR:** because the demo SQL now lives
in the repo, the code archive must be rebuilt whenever those scripts change.
Merging the PR alone does not produce a working Db2 track — someone has to
republish `mma-apps.zip`. No script in the repo automates this.

## Changes made today

All in `one-click-deployment/db2-to-postgres/` unless noted. Each has a comment
in-place explaining why; this is the index.

| Change | Files | Verified how |
|---|---|---|
| Demo SQL moved into the repo (`demo-scripts/`), replacing the unpublished `db2-scripts.zip` | `demo-infrastructure.yaml`, `mma-apps-main-stack.yaml` | offline; filename cross-check |
| Repo download split into its own SSM document + `RunRepoDownload` waiter, so the demo branch cannot race the unzip | `application-setup.yaml`, `mma-apps-main-stack.yaml` | `DependsOn` asserted from parsed template |
| Hardcoded `Welcome123_` replaced with `<DEMO_PASSWORD>` placeholder in committed SQL | `demo-scripts/*.sql` | grep clean; strip logic re-verified |
| License Manager `db2-ce` configuration created in-flight via custom resource | `license-manager-stack.yaml` (new) | 8 offline unit tests |
| `EnableDeletionProtection` parameter, default `false` | `database-stack.yaml`, `demo-infrastructure.yaml`, main stack | parsed-template assertions |
| Three security groups merged into one | `network-stack.yaml`, `database-stack.yaml`, `demo-infrastructure.yaml`, `application-setup.yaml` | asserted exactly 1 SG, no dangling refs |
| SSM waiter: budgeted registration wait instead of fixed 4-minute retry | `ssm-waiter-stack.yaml` | 7 offline unit tests |
| OS patching moved from UserData to an `ApplySecurityUpdates` SSM step | `compute-stack.yaml` | offline |
| DMS engine version pinned to `3.6.1` | `demo-infrastructure.yaml`, main stack | **confirmed orderable in eu-west-1 via live API** |
| `jq` added to package install | `compute-stack.yaml` | offline |
| `.DS_Store` added to `.gitignore` | repo root | — |
| `cfn-lint` config added | `.cfnlintrc.yaml` (new) | `cfn-lint` exit 0 |

## Operational notes

**Run `cfn-lint` before every deploy.** From this directory, no arguments needed:

```bash
cfn-lint
```

Two deploy cycles were lost today to constraint violations that parse as valid
YAML and only fail at `CreateStack`: a template `Description` over 1024
characters, and `->` in a security group rule description (EC2 rejects `>`).
Both are one-second catches. `.cfnlintrc.yaml` suppresses only a stale linter
spec (`E3691`, `db2-ce` is a real engine) and pre-existing stylistic warnings
shared with the other two tracks.

**Teardown needs manual steps** for stacks created before the security group
merge:

```bash
REGION=eu-west-1; PREFIX=<prefix>
# secrets: names are held for 30 days unless force-deleted
for s in testmgr-db-secret demo-secret-db2-admin demo-secret-postgres-admin \
         demo-db2-ro-user demo-postgres-ro-user; do
  aws secretsmanager delete-secret --region $REGION \
    --secret-id ${PREFIX}-$s --force-delete-without-recovery
done
```

Security groups may also block deletion with "has a dependent object" — check for
leftover ENIs and for other groups whose rules reference the stuck group. The
merge should prevent this for newly created stacks but does not help existing
ones. Using a fresh stack prefix avoids the secret cleanup entirely.

**Deploy scripts run with `--disable-rollback`**, so a failure leaves the
environment standing for inspection. Use that before deleting — the CloudWatch
log stream for the waiter Lambda is the most useful artefact, and stack deletion
creates a *new* stream that will hide the failing one at the top of
`describe-log-streams --order-by LastEventTime`.

**Iterate on schema changes without redeploying.** Once the stack stands, re-run
the schema document directly against the live instance with
`aws ssm send-command` — minutes rather than an hour.

## Pre-existing issues found but not changed

These affect the oracle and sqlserver tracks identically. Left alone to avoid
diverging from them, but worth knowing:

- **`start-metadata-model-import` is never called.** `DMSSchemaConversionDocument`
  runs assessment, conversion and export-to-target, but not the import that
  populates the source metadata model. Oracle has the same omission and reportedly
  works, which suggests assessment may trigger it implicitly — unconfirmed.
- **`RunEC2BaseSetup` timeout budget is tight.** The SSM waiter Lambda caps at
  900s (AWS maximum), covering registration + send + polling for the whole
  document. Estimated document time is ~520s, leaving ~260s headroom. If steps
  are slower than estimated the fix is splitting the document across two waiters,
  not raising a timeout.
- **`03_data_db2_v2.sql`** is ~2.6 MB / ~576 statements and `Db2ScriptRunner`
  commits per statement, against a 600s step timeout. May need batching.
- **`Db2ScriptRunner` continues past statement failures.** A step can report
  success with individual statements having failed. Grep step output for
  `ERROR SQLSTATE` rather than trusting status.
- **Security group ingress uses `CidrIp: 127.0.0.1/32`** as a placeholder that
  participants are expected to widen. Combined with a public-IP EC2 instance this
  may raise findings in a corporate account.

## Things I got wrong, so they are not re-litigated

- Diagnosed the SSM registration failure as a step timeout, then as a wrong
  subnet, then as the UserData `yum update`. All three wrong. The actual cause was
  a VPC interface endpoint the agent could not reach - found only by reading the
  instance console output, which should have been the FIRST diagnostic rather than
  the last. Two deploy cycles were spent on template changes that could not have
  fixed an environment problem.
- Shipped a template `Description` over the 1024-character limit.
- Shipped `->` in a security group rule description.
- Emptied `DemoSQLScriptsURL` without noticing an Output still exported it, which
  fails with "Exported values must not be empty".
- Corrupted a parameter block twice with the same careless probe edit.

`cfn-lint` would have caught items 2 and 3 immediately. It is now installed and
configured; use it.
