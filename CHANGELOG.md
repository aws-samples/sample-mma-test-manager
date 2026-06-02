# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-06-02

### Added

#### Test Manager
- **Sybase ASE source support** — Sybase ASE can now be used as a migration source
  database, including `SybaseCommonService`, routing in the DDL, Dependency, and
  Comparison services, S3 project loading with system-object (`sys*`) filtering,
  `SQL_SCALAR_FUNCTION` object-type handling, and a Sybase icon.
- **Batch operations** — Operation-first batch workflow (DDL, Comparison, Unit Test,
  Dependencies) backed by `BatchExecutorService` with a global concurrency priority
  queue and configurable throttling (`mma.batch.throttle-ms`, default 1000ms).
  Includes `BatchJob` / `BatchJobItem` entities and repositories, batch and
  batch-detail pages, progress tracking, pagination (5 per page), and startup
  recovery for stuck jobs.
- **Knowledge base enhancements** — Added a parameter-preservation rule and
  Sybase-to-PostgreSQL conversion best practices to the knowledge base.

#### MCP Servers
- **`sybase-client-mcp`** — New stdio MCP server (Spring AI 1.1.7) for Sybase ASE
  database operations, including `DatabaseConfig`, `SybaseMcpTools`,
  `McpConfiguration`, README, and Maven wrapper.

### Changed

#### Test Manager
- UI: loading overlays for long-running operations (convert, generate, execute),
  headers and logout buttons on batch pages, `submittedBy`/time display, preserved
  search filters across pagination, and "Global Knowledge Base" renamed to "Global KB".
- Removed alert popups on the comparison and unit test pages in favor of inline reload.
- Bumped application version to **1.2.0**.

#### MCP Servers
- Made the Sybase `jconn42` dependency optional via an auto-activated Maven profile.
- Upgraded dependencies across all MCP servers: Spring Boot 4.0.6, Java 21,
  Spring AI 1.1.7, AWS SDK 2.29.0, Oracle JDBC 23.8, MSSQL JDBC 13.4,
  and `ojdbc8` → `ojdbc11`.

#### Configuration
- Set the default model to `claude-opus-4.8` in agent configs.
- Added Sybase-to-PostgreSQL agent configuration and properties; DMS Schema
  Conversion settings (`SchemaNameTemplate=SCHEMA`, `CaseSensitivityNames=false`).

### Fixed

#### Test Manager
- Batch race condition: items are inserted under a `CREATING` status and only set to
  `QUEUED` after all items are saved.
- Batch lazy loading: `processItem` is wrapped in a `TransactionTemplate` for a valid
  JPA session in the background thread.
- Batch progress: remaining `PENDING` items are marked `SKIPPED` on job completion and
  counted in `failedItems`; `processJob` catches `Throwable` so no item is left silently pending.
- Batch processing now uses `findByBatchJobIdOrderById` to avoid missing items in the loop.
- Batch `message` column changed to `TEXT` to allow long error messages.
- Batch search uses an unsorted pageable for the native query to avoid column-name mismatch.
- Scheduler is stopped on restart via `@PreDestroy`, with JS submit error handling.
- Thymeleaf template fixes (use `gt` instead of `>`, separate `th:style` for the progress bar).
