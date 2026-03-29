# MMA Test Manager

Spring Boot application for managing database migration testing with AI-powered test generation and code fixing.

## Features

- **Multi-Engine Support**: Oracle, SQL Server → PostgreSQL
- **S3 Integration**: Load DMS Schema Conversion data into PostgreSQL repository
- **Three Test Types**:
  - Comparison Test: Compare source vs target execution results
  - Unit Test: Execute target database tests independently
  - DDL Management: View, edit, deploy, and validate DDL changes
- **AI-Powered**: Generate tests, fix failures, convert DDL using Kiro CLI
- **Knowledge Base**: Store and reuse migration patterns
- **Security**: Read-only test users + automatic rollback

## Quick Start

### 1. Setup Database

Refer to one of the following stacks in the repository for database setup:
 - one-click-deployment/oracle-to-postgres/mma-nested-stacks/database-stack.yaml
 - one-click-deployment/sqlserver-to-postgres/mma-nested-stacks/database-stack.yaml

### 2. Configure Connections

Edit `mma-test-manager/application-secretsmanager.properties`:

```properties
# Repository datasource
mma.repo.type=secretsmanager
mma.repo.secret=arn:aws:secretsmanager:region:account:secret:mma-repo

# Source database (Oracle/SQL Server/MySQL)
mma.sourcedb.connection.type=secretsmanager
mma.sourcedb.connection.secret=arn:aws:secretsmanager:region:account:secret:source-admin
mma.sourcedb.test.type=secretsmanager
mma.sourcedb.test.secret=arn:aws:secretsmanager:region:account:secret:source-test

# Target database (PostgreSQL)
mma.targetdb.connection.type=secretsmanager
mma.targetdb.connection.secret=arn:aws:secretsmanager:region:account:secret:target-admin
mma.targetdb.test.type=secretsmanager
mma.targetdb.test.secret=arn:aws:secretsmanager:region:account:secret:target-test

# S3 path
mma.s3.default-path=s3://your-bucket/dms-sc-project
```

For development, use password mode:
```properties
mma.repo.type=password
mma.repo.secret=username:password@hostname:port/database
```

### 3. Build and Run

```bash
mvn clean package
mvn spring-boot:run

# Or use run script
./run.sh start
```

Access UI: http://localhost:8082

## Usage

### Load Project
1. Enter S3 path to DMS SC project
2. Click "Load from S3"
3. System auto-detects source/target engines

### Run Comparison Tests
1. Navigate to object's comparison test page
2. Generate test cases with AI
3. Execute source tests
4. Convert SQL to target syntax
5. Execute target tests
6. Compare results
7. Fix failures with AI

### Run Unit Tests
1. Navigate to object's unit test page
2. Generate test cases for target
3. Execute tests
4. Fix failures with AI

### Manage DDL
1. View source/target DDL side-by-side
2. Retrieve latest DDL from databases
3. Edit with syntax highlighting
4. Convert DDL with AI
5. Deploy to target
6. Track deployment history

## Key Capabilities

- **Multi-Statement Execution**: Intelligently splits SQL respecting PL/SQL, T-SQL, and PostgreSQL blocks
- **DDL Retrieval**: Extracts constraints, indexes, views, functions, procedures from live databases
- **Engine-Agnostic**: Dynamic engine detection and routing
- **Best Available DDL**: Prioritizes user edits → DB retrieval → S3 fallback

## Technology Stack

- Spring Boot 4.0.3 with Thymeleaf
- PostgreSQL repository
- AWS S3 SDK, Secrets Manager
- Kiro CLI integration
- Multi-engine JDBC support

## Configuration Properties

```properties
server.port=8082
mma.repo.type=secretsmanager|password
mma.repo.secret=<arn-or-connection-string>
mma.sourcedb.connection.type=secretsmanager|password
mma.sourcedb.connection.secret=<arn-or-connection-string>
mma.sourcedb.test.type=secretsmanager|password
mma.sourcedb.test.secret=<arn-or-connection-string>
mma.targetdb.connection.type=secretsmanager|password
mma.targetdb.connection.secret=<arn-or-connection-string>
mma.targetdb.test.type=secretsmanager|password
mma.targetdb.test.secret=<arn-or-connection-string>
mma.s3.default-path=s3://bucket/prefix
kiro.cli.path=/usr/local/bin/kiro-cli
kiro.cli.agent=mma-agent
```

Connection string format: `username:password@hostname:port/database`

## Troubleshooting

- **Connection Issues**: Verify AWS credentials, Secrets Manager permissions, security groups
- **S3 Loading**: Check bucket permissions and DMS SC project structure
- **Test Execution**: Verify test user is read-only, use "Fix with AI" for failures
- **DDL Deployment**: Ensure admin credentials, check syntax, verify schema exists
