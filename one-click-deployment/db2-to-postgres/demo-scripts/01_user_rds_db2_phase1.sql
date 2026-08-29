-- =============================================================================
-- Bob's Used Books workshop - Amazon RDS for Db2 setup, PHASE 1 of 3
-- Runs against the RDSADMIN database as the MASTER user (db2inst1)
-- =============================================================================
-- RDS variant of 01_user_db2.sql. Split into three files because the work has
-- to happen across two different database connections and at two different
-- points in the deployment:
--
--   phase1 (this file)  -> RDSADMIN, master user   : bufferpool, tablespaces, users
--   phase2              -> BOBSDB,   master user   : schema + privileges
--   phase3              -> BOBSDB,   master user   : read-only grants, AFTER
--                                                    02/03/04 have created the
--                                                    tables they grant on
--
-- Why not one file: rdsadmin.* procedures only resolve while connected to the
-- RDSADMIN database, whereas CREATE SCHEMA and GRANT must run against BOBSDB.
-- There is also no CONNECT statement in JDBC, so Db2ScriptRunner takes one URL
-- per invocation - hence one file per target database.
--
-- WHAT DIFFERS FROM SELF-MANAGED Db2 (01_user_db2.sql):
--   CREATE BUFFERPOOL      -> CALL rdsadmin.create_bufferpool(...)
--   CREATE TABLESPACE      -> CALL rdsadmin.create_tablespace(...)
--   sudo useradd           -> CALL rdsadmin.add_user(...)
-- The first two are rejected on RDS because they need SYSADM/SYSCTRL, which the
-- master user does not hold. The third has no shell to run in.
--
-- NOTE: the RDS_UTILITIES.CREATE_USER call suggested in the comments of
-- 01_user_db2.sql does not exist. There is no RDS_UTILITIES schema; the correct
-- procedure is rdsadmin.add_user, used below.
--
-- BOBSDB itself is NOT created here. The one-click stack sets it as the DB
-- instance's initial database (DBName: BOBSDB in demo-infrastructure.yaml), so
-- RDS provisions it. Neither CREATE DATABASE nor rdsadmin.create_database is
-- needed or possible for it.
--
-- PAGE SIZE 32K: matches 01_user_db2.sql so both tracks share one DEMOTBS
-- definition and 02_schema_db2_v2.sql needs no change. The widest row in this
-- schema is BOOKS at ~3.1 KB, so 8K or 16K would also fit; 32K is headroom for
-- later column widening or VARCHAR(n CODEUNITS32).
--   RDS caveat: write atomicity is guaranteed only for 4/8/16 KiB pages. 32 KiB
--   pages risk torn writes, and AWS recommends automated backups plus PITR when
--   using them. The stack sets BackupRetentionPeriod: 1 on the Db2 instance, so
--   that recommendation is already satisfied.
--
-- PLACEHOLDERS: <DEMO_PASSWORD> and <RO_PASSWORD> are substituted at deploy
-- time from Secrets Manager. Do not commit real values.
--
-- Run:
--   java -cp jcc.jar Db2ScriptRunner.java \
--     "jdbc:db2://$HOST:50000/RDSADMIN" db2inst1 "$MASTER_PASS" \
--     01_user_rds_db2_phase1.sql
--
-- ASYNCHRONY: rdsadmin.* procedures are asynchronous and return a task ID.
-- Under load a create_tablespace can be attempted before its bufferpool is
-- ready. If phase1 reports "bufferpool not found" or similar, poll
-- rdsadmin.get_task_status(task_id) and re-run from the failed statement.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Oracle: CREATE TABLESPACE demotbs DATAFILE SIZE 10M AUTOEXTEND ON
-- Self-managed: CREATE BUFFERPOOL BP32K IMMEDIATE SIZE 8000 PAGESIZE 32K
-- Args: database, bufferpool, size_pages, immediate, automatic, page_size
-- -----------------------------------------------------------------------------
CALL rdsadmin.create_bufferpool('BOBSDB', 'BP32K', 8000, 'Y', 'Y', 32768);

-- User-data tablespace.
-- Args: database, tablespace, bufferpool, page_size, initial_kb, increase_pct,
--       type ('U' user / 'T' user temporary / 'S' system temporary)
-- "MANAGED BY AUTOMATIC STORAGE" has no parameter: RDS always uses automatic
-- storage, so that clause is implicit. "AUTOEXTEND ON" is likewise implicit.
CALL rdsadmin.create_tablespace('BOBSDB', 'DEMOTBS', 'BP32K', 32768, NULL, NULL, 'U');

-- Temporary tablespaces with a matching page size. Without these, large sorts,
-- hash joins and REORGs against these tables fail at runtime. Oracle needed no
-- equivalent because TEMP is database-wide.
-- initial_kb and increase_pct do not apply to temporary tablespaces (Db2 manages
-- them), hence NULL.
CALL rdsadmin.create_tablespace('BOBSDB', 'DEMOSYSTMP', 'BP32K', 32768, NULL, NULL, 'S');
CALL rdsadmin.create_tablespace('BOBSDB', 'DEMOTMP',    'BP32K', 32768, NULL, NULL, 'T');


-- -----------------------------------------------------------------------------
-- Oracle: CREATE USER demo IDENTIFIED BY "<password>"
-- Self-managed Db2: an OS user created with useradd, because Db2 has no
-- CREATE USER and defers authentication to the OS.
-- RDS: no shell, so rdsadmin.add_user creates a database-authenticated user.
--
-- The third argument is a group and is MANDATORY: RDS authorization is
-- group-based. Privileges go to DEMOGRP in phase2, not to the user directly.
-- -----------------------------------------------------------------------------
CALL rdsadmin.add_user('DEMO', '<DEMO_PASSWORD>', 'DEMOGRP');

-- Read-only user used by MMA Test Manager to run comparison queries against the
-- source with no possibility of side effects. The one-click stack expects this
-- username and stores its password in DemoDb2TestUserSecret.
-- The Oracle track created its equivalent with PL/SQL in a separate SSM step.
-- SELECT privileges are granted in phase3, after the tables exist.
CALL rdsadmin.add_user('DB2_RO_USER', '<RO_PASSWORD>', 'DB2ROGRP');
