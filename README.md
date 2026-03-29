# MMA-Test-Manager

## Overview

This repository contains sample tools and infrastructure for AWS Migration and Modernization Acceleration (MMA) database migration testing and validation. It includes MCP servers for database operations, test management applications, and deployment automation.

## Prerequisites

### For Development
- **Java**: JDK 21 or later
- **Maven**: 3.6+ for building the Test Manager application
- **Spring AI**: 1.1.x (for MCP servers)
- **Kiro CLI**: Pro license with LSP support
  - Install: Follow [AWS Kiro CLI documentation](https://docs.aws.amazon.com/kiro/)
  - Login with Identity Center (see Quick Start)

### For Deployment
- **AWS CLI**: v2.x configured with appropriate credentials
- **AWS Account**: With permissions for CloudFormation, EC2, RDS, S3, IAM
- **S3 Bucket**: For storing CloudFormation templates and artifacts

## Components

### MCP Servers for Database Operations

Model Context Protocol (MCP) servers that enable AI-assisted database operations through Kiro CLI:

1. **postgres-client-mcp** - PostgreSQL database operations
2. **sqlserver-client-mcp** - SQL Server database operations  
3. **oracle-client-mcp** - Oracle database operations

All servers use the **stdio protocol** for stable communication with Kiro CLI.

### Test Manager Application

**mma-test-manager** - Web application for managing and executing database migration tests. See [mma-test-manager/README.md](mma-test-manager/README.md) for details.

### Deployment Infrastructure

One-click deployment solutions for different migration scenarios:

**one-click-deployment/oracle-to-postgres/**
- Main stack: `mma-apps-main-stack.yaml`
- Nested stacks: `mma-nested-stacks/` (network, compute, database, demo-infrastructure, application-setup)
- Deployment scripts: `deploy-mma-apps.sh`, `deploy-with-demo-infra.sh`, `deploy-with-demo-infra_cloudfront.sh`

**one-click-deployment/sqlserver-to-postgres/**
- Main stack: `mma-apps-main-stack.yaml`
- Nested stacks: `mma-nested-stacks/` (network, compute, database, demo-infrastructure, application-setup)
- Deployment scripts: `deploy-mma-apps.sh`, `deploy-with-demo-infra.sh`, `deploy-with-demo-infra_cloudfront.sh`

## Quick Start

### 1. Setup Kiro CLI

Login to Kiro CLI with your Identity Center:

```bash
kiro-cli login --use-device-flow --license pro --region us-east-1 --identity-provider https://d-9067e745cc.awsapps.com/start
```

Replace `--identity-provider` with your own Identity Center URL.

Follow the prompts to authorize the device in your browser.

### 2. Verify Kiro CLI Agent Configuration

Verify that `~/.kiro/agents/mma-agent.json` was auto-configured during CFN provisioning. See [docs/sample-mma-agent-for-kiro-cli.json](docs/sample-*-mma-agent-for-kiro-cli.json) for reference.

### 3. Start Kiro CLI
```bash
kiro-cli chat --agent mma-agent
```

## Documentation
- Lab instructions from MMA Workshop
  - [Oracle to PostgreSQL](https://catalog.workshops.aws/mma-oracle-pg/en-US/6-modernization-validation/test-manager)
  - [SQL Server to PostgreSQL](https://catalog.workshops.aws/mma-mssql-pg/en-US/test-manager)
- Individual server READMEs in each MCP directory

