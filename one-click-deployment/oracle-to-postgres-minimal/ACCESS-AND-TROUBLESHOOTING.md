# Access & Troubleshooting — MMA Test Manager (Minimal Deployment)

The EC2 host runs in a **private subnet with no public IP and no CloudFront**.
You reach both applications through **AWS Systems Manager (SSM) port
forwarding**. An **nginx reverse proxy on the host terminates TLS and exposes a
single port (443)**, following the `vscode-server-vpc-v2` pattern: `/` serves
VS Code (code-server) and `/testmgr/` serves the Test Manager. One port keeps
SSM tunnels and any firewall/SG rules simple.

| Application | Path behind 443 | Local URL after tunnel |
|---|---|---|
| VS Code (code-server) | `/` | `https://localhost:8443/?folder=/workshop/MMA-Samples` |
| MMA Test Manager | `/testmgr/` | `https://localhost:8443/testmgr/` |

Login for both uses the `AdminPassword` you supplied (Test Manager username is
`admin`).

> **TLS note:** the host generates a **self-signed** certificate, so your
> browser will show a warning the first time — accept it. A self-signed cert
> encrypts traffic in transit but does **not** prove server identity, so it does
> not stop an active man-in-the-middle. For anything beyond a personal sandbox,
> replace it with a CA-issued certificate (see section 8).

---

## 1. One-time local setup

Install the **Session Manager plugin** for the AWS CLI:
<https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html>

Confirm you can see the instance (it must show `Online` under SSM):
```bash
aws ssm describe-instance-information --region <region> \
  --query "InstanceInformationList[].{Id:InstanceId,Ping:PingStatus}" --output table
```
The instance id is in the stack outputs (`InstanceId`).

---

## 2. Open the tunnel

The stack output `StartTunnelCommand` is ready to run:

```bash
aws ssm start-session --region <region> --target <instance-id> \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["443"],"localPortNumber":["8443"]}'
```

Then open:
- VS Code: <https://localhost:8443/?folder=/workshop/MMA-Samples>
- Test Manager: <https://localhost:8443/testmgr/>

> Add `--profile <name>` if you use a named profile. If local port 8443 is busy,
> change `localPortNumber` and adjust the URL. In-VPC clients (bastion/VPN) that
> have `AllowedClientCidr` opened can browse `https://<private-ip>/testmgr/` and
> `https://<private-ip>/?folder=/workshop/MMA-Samples` directly without a tunnel.

---

## 3. Interactive shell on the host

```bash
aws ssm start-session --region <region> --target <instance-id>
sudo su - <VSCodeUser>   # default: awsmma
```

Key paths:
- Repository / workspace: `/workshop/MMA-Samples`
- Test Manager module: `/workshop/MMA-Samples/mma-test-manager`
- App config: `/workshop/MMA-Samples/mma-test-manager/application-secretsmanager.properties`
- Kiro agent: `~/.kiro/agents/mma-agent.json`
- Logs: `/var/log/mma-apps/` (`build.log`, `mma-test-manager.log`, `mma-test-manager-error.log`)

---

## 4. Service management

```bash
sudo systemctl status  mma-test-manager
sudo systemctl restart mma-test-manager
sudo journalctl -u mma-test-manager -f
tail -f /var/log/mma-apps/mma-test-manager.log
```

code-server runs as a per-user service:
```bash
sudo systemctl status code-server@<VSCodeUser>
sudo systemctl restart code-server@<VSCodeUser>
```

nginx (the single-port TLS reverse proxy):
```bash
sudo nginx -t                      # test config
sudo systemctl restart nginx
sudo cat /etc/nginx/conf.d/mma-apps.conf
```

---

## 5. Deployment-time troubleshooting

The CloudFormation deployment runs four SSM documents in order via a Lambda
"waiter". If the stack fails, the failing custom resource names the step:

| Custom resource | SSM document | What it does |
|---|---|---|
| `RunEC2BaseSetup` | `<prefix>-setup-ec2-software` | Installs code-server, Java, Maven, DB clients, Kiro CLI |
| `RunApplicationDeployment` | `<prefix>-application-deploy` | `git clone` into `/workshop/MMA-Samples` + `build-all.sh` |
| `RunConfigureSecrets` | `<prefix>-configure-secrets` | Writes Test Manager + MCP config |
| `RunApplicationPostDeployment` | `<prefix>-application-post-deploy` | Registers/starts the systemd service and configures the nginx TLS reverse proxy (443) |

Inspect the actual command output:
```bash
# Find recent invocations for a document
aws ssm list-command-invocations --region <region> --details \
  --filters key=DocumentName,value=<prefix>-application-deploy \
  --query "CommandInvocations[].{Cmd:CommandId,Status:Status}" --output table

# Read a specific invocation's output
aws ssm get-command-invocation --region <region> \
  --command-id <command-id> --instance-id <instance-id>
```
You can also read the Lambda logs in CloudWatch Logs group
`/aws/lambda/<prefix>-ssm-waiter`.

### Common deployment failures

| Symptom | Likely cause | Fix |
|---|---|---|
| `RunEC2BaseSetup` / `RunApplicationDeployment` times out | No outbound internet from the private subnet | Ensure a NAT gateway (or package mirror) is reachable |
| Instance never becomes `Online` in SSM | Missing SSM egress/endpoints or role | Verify NAT or `ssm`/`ssmmessages`/`ec2messages` endpoints and the instance profile |
| `build-all.sh` fails | Maven cannot reach Maven Central | Check egress; read `/var/log/mma-apps/build.log` |
| `git clone` fails | Wrong `CodeRepoUrl`/branch or no egress to GitHub | Verify the URL/branch and outbound access |
| `AccessDenied` reading a secret | Secret ARN not in the granted list, or CMK not permitted | Check the three secret ARNs and the KMS key policy/`SecretsKmsKeyArn` |

---

## 6. Runtime troubleshooting

| Symptom | Check |
|---|---|
| Test Manager won't start | `tail -n 100 /var/log/mma-apps/mma-test-manager-error.log` |
| DB connection errors | Confirm the DB security groups allow the host SG on 1521/5432; test with `psql`/`sqlplus` from the host |
| `AccessDeniedException` (KMS) | If secrets use a CMK, confirm `SecretsKmsKeyArn` was set **and** the key policy allows this account via `secretsmanager.<region>.amazonaws.com` |
| Secret decodes but app fails | Verify the repo secret JSON has `host`, `port`, `dbname`, `username`, `password` |
| S3 / DMS path errors | Confirm `S3DMSProjectPath` and that `S3DMSProjectBucket` matches the bucket in that path |
| Kiro agent not found | `ls ~/.kiro/agents/mma-agent.json`; jars exist under `/workshop/MMA-Samples/*/target/` |

### Manually re-apply configuration
If you rotate a secret or change the S3 path, edit
`/workshop/MMA-Samples/mma-test-manager/application-secretsmanager.properties`
and `systemctl restart mma-test-manager`. MCP wiring lives in
`oracle-client-mcp/application-secretsmanager.properties` and
`postgres-client-mcp/application-secretsmanager.properties`.

---

## 7. Quick verification from the host

```bash
# Applications + proxy listening?
sudo ss -ltnp | grep -E ':443|8080|8082'

# Proxy answering locally (self-signed, so -k)?
curl -ksS -o /dev/null -w '%{http_code}\n' https://localhost/            # VS Code (root)
curl -ksS -o /dev/null -w '%{http_code}\n' https://localhost/testmgr/    # Test Manager

# Reach the databases (uses clients installed during setup)
psql "host=<repo-host> port=5432 dbname=testmgr_repo user=<user>"  # repo DB
psql "host=<target-host> port=5432 dbname=<db> user=<user>"        # target PG
sqlplus <user>/<pwd>@//<oracle-host>:1521/<service>                # source Oracle
```

If 443/8080/8082 are listening on the host and the tunnel is up but the browser
can't connect, the local port is probably already in use — pick a different
`localPortNumber`.

---

## 8. Replacing the self-signed certificate (recommended for non-sandbox)

The self-signed cert encrypts traffic but does not authenticate the server. To
use a real certificate:

1. Copy your cert/key onto the host (or reference ACM via a load balancer):
   ```bash
   sudo cp fullchain.pem /etc/nginx/ssl/nginx-selfsigned.crt
   sudo cp privkey.pem   /etc/nginx/ssl/nginx-selfsigned.key
   sudo nginx -t && sudo systemctl restart nginx
   ```
2. For a proper hostname + managed cert, front the host with an internal
   Application Load Balancer that terminates TLS with an **ACM** certificate and
   forwards to the host on 443 (or 8082/8080). This gives CA-trusted TLS and a
   stable DNS name without exposing the host publicly.

---

## 9. VS Code "WebSocket close 1006" and "can't connect" issues

**"can't connect" to a URL:** the URL port must match your SSM tunnel's
`localPortNumber`. The default `StartTunnelCommand` forwards local **8443**, so
browse `https://localhost:8443/` and `https://localhost:8443/testmgr/login` —
not `https://localhost/...` (that is port 443, where nothing is listening
locally). If you prefer port-less URLs, forward local `443` instead (needs
`sudo` for a privileged port): set `"localPortNumber":["443"]`.

**VS Code workbench "WebSocket close 1006":** code-server validates that the
request `Host` matches the browser `Origin`. Reached over an SSM tunnel the
Origin includes the local port (e.g. `https://localhost:8443`), so nginx must
forward the **full host including port**. This deployment sets
`proxy_set_header Host $http_host;` (not `$host`, which drops the port) plus
`proxy_http_version 1.1;` and the `Connection: upgrade` map — which makes the
workbench WebSocket connect. Verify on the host:
```bash
grep -n 'proxy_set_header Host' /etc/nginx/conf.d/mma-apps.conf   # expect $http_host
sudo journalctl -u code-server@<VSCodeUser> -n 30 | grep -i connection
# a working session logs: [ManagementConnection] New connection established.
```
If you front the host with an ALB/CloudFront instead, keep the client-visible
host consistent end to end so the Origin/Host check still passes.
