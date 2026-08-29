-- =============================================================================
-- Bob's Used Books workshop - Amazon RDS for Db2 setup, PHASE 2 of 3
-- Runs against BOBSDB as the MASTER user (db2inst1)
-- =============================================================================
-- Continues 01_user_rds_db2_phase1.sql. Run phase1 first: the DEMO user and the
-- DEMOTBS tablespace referenced below are created there.
--
-- Everything here is ordinary SQL, but it is a separate file from phase1 because
-- phase1 needs the RDSADMIN database and this needs BOBSDB, and Db2ScriptRunner
-- takes one JDBC URL per invocation.
--
-- KEY DIFFERENCE FROM SELF-MANAGED Db2 (01_user_db2.sql): privileges are granted
-- to GROUPS, not to users. RDS authorization is group-based - the user was
-- attached to DEMOGRP by rdsadmin.add_user in phase1, and DEMOGRP receives the
-- privileges here.
--
-- Run:
--   java -cp jcc.jar Db2ScriptRunner.java \
--     "jdbc:db2://$HOST:50000/BOBSDB" db2inst1 "$MASTER_PASS" \
--     01_user_rds_db2_phase2.sql
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Oracle: CREATE USER demo ... DEFAULT TABLESPACE demotbs
-- Db2: the user exists (phase1); create its object namespace.
-- The master user holds DBADM, which is sufficient to create a schema owned by
-- another authorization ID.
-- -----------------------------------------------------------------------------
CREATE SCHEMA DEMO AUTHORIZATION DEMO;


-- -----------------------------------------------------------------------------
-- RDS-specific: built-in roles.
--
-- RDS creates DB instances in RESTRICTIVE mode, so an ordinary user cannot run
-- CLP or dynamic SQL, nor use tablespaces, until these three roles are granted.
-- They have no counterpart in the self-managed script, which instead issues
-- GRANT USE OF TABLESPACE directly.
--   ROLE_NULLID_PACKAGES - EXECUTE on the NULLID packages (dynamic SQL / JDBC)
--   ROLE_TABLESPACES     - USAGE on tablespaces
--   ROLE_PROCEDURES      - EXECUTE on SYSIBM procedures
-- -----------------------------------------------------------------------------
GRANT ROLE ROLE_NULLID_PACKAGES TO GROUP DEMOGRP;
GRANT ROLE ROLE_TABLESPACES     TO GROUP DEMOGRP;
GRANT ROLE ROLE_PROCEDURES      TO GROUP DEMOGRP;

GRANT ROLE ROLE_NULLID_PACKAGES TO GROUP DB2ROGRP;
GRANT ROLE ROLE_TABLESPACES     TO GROUP DB2ROGRP;
GRANT ROLE ROLE_PROCEDURES      TO GROUP DB2ROGRP;

-- Also required in RESTRICTIVE mode before any non-master user can connect.
GRANT USAGE ON WORKLOAD SYSDEFAULTUSERWORKLOAD TO PUBLIC;


-- -----------------------------------------------------------------------------
-- Oracle: GRANT CREATE SESSION, RESOURCE TO demo
-- BINDADD is additionally required on RDS so the JDBC/CLP packages can bind.
-- -----------------------------------------------------------------------------
GRANT CONNECT, BINDADD, CREATETAB, IMPLICIT_SCHEMA ON DATABASE TO GROUP DEMOGRP;

-- Needed so DEMO can create/alter/drop its own tables, routines and triggers.
GRANT CREATEIN, ALTERIN, DROPIN ON SCHEMA DEMO TO GROUP DEMOGRP;

-- 04_complex_schema_db2_v2.sql creates MQTs (the Db2 stand-in for materialized
-- views). Oracle needed GRANT CREATE MATERIALIZED VIEW; in Db2 an MQT is a
-- table, so CREATETAB above already covers it.

-- Oracle: ALTER USER demo QUOTA UNLIMITED ON demotbs
-- ROLE_TABLESPACES above is the documented RDS mechanism and should cover this.
-- UNVERIFIED: the AWS docs state ROLE_TABLESPACES grants usage on tablespaces
-- "created by the CREATE DATABASE command". DEMOTBS was created afterwards by
-- rdsadmin.create_tablespace, and whether the role extends to it is not stated.
-- If 02_schema_db2_v2.sql fails with SQL0298N or an insufficient-privilege error
-- naming DEMOTBS, uncomment these two lines and re-run this file:
-- GRANT USE OF TABLESPACE DEMOTBS TO GROUP DEMOGRP;
-- GRANT USE OF TABLESPACE DEMOTMP TO GROUP DEMOGRP;

-- Read-only user needs to connect, and to use the temporary tablespace so that
-- its comparison queries can sort and hash-join.
GRANT CONNECT, BINDADD ON DATABASE TO GROUP DB2ROGRP;

-- Schema-level SELECT for the read-only user.
--
-- SELECTIN is a Db2 11.1+ schema privilege that applies to every table in the
-- schema, INCLUDING tables created later. Granting it here - before 02/03/04
-- create the tables - is therefore intentional and is the mechanism that should
-- make phase3's per-table grants redundant. It is the closest Db2 equivalent to
-- PostgreSQL's ALTER DEFAULT PRIVILEGES, which the Aurora side of this workshop
-- uses for the same purpose.
--
-- UNVERIFIED against a live RDS for Db2 12.1 instance. phase3 grants the same
-- privileges explicitly per table as a fallback; if SELECTIN behaves as
-- documented, phase3 is harmless duplication.
GRANT SELECTIN ON SCHEMA DEMO TO GROUP DB2ROGRP;


-- -----------------------------------------------------------------------------
-- Oracle grants with NO Db2 equivalent -- intentionally NOT converted:
--
--   GRANT CREATE JOB TO demo;
--   GRANT scheduler_admin TO demo;
--       Db2 has no DBMS_SCHEDULER. The nearest equivalent is the Administrative
--       Task Scheduler (CALL SYSPROC.ADMIN_TASK_ADD), which needs
--       DB2_ATS_ENABLE=YES set at the instance level via db2set -- not possible
--       on RDS, where there is no shell and the registry is managed. Use
--       EventBridge Scheduler or cron on the EC2 box instead. No job is created
--       by these workshop scripts, so nothing is lost.
--
--   GRANT EXECUTE ON dbms_lock TO demo;
--       No Db2 equivalent for DBMS_LOCK.SLEEP / user-defined lock handles.
--
--   GRANT CREATE DATABASE LINK TO demo;
--       Db2 requires the Federation feature (CREATE SERVER + CREATE USER
--       MAPPING + CREATE NICKNAME), which is not available on RDS for Db2.
--       Not converted (nothing uses it).
--
--   GRANT CREATE PUBLIC SYNONYM TO demo;
--   GRANT CREATE SYNONYM TO demo;
--       Db2 uses CREATE ALIAS. There is no PUBLIC synonym concept -- an alias
--       lives in a schema and is reached via CURRENT PATH or schema
--       qualification. Covered by CREATEIN above.
--
--   GRANT CREATE ANY DIRECTORY TO demo;
--       Db2 has no DIRECTORY object.
-- -----------------------------------------------------------------------------


-- -----------------------------------------------------------------------------
-- Verify (run manually; not part of the automated deployment)
-- -----------------------------------------------------------------------------
-- SELECT TBSPACE, PAGESIZE, TBSPACETYPE FROM SYSCAT.TABLESPACES
--  WHERE TBSPACE LIKE 'DEMO%';
-- SELECT SCHEMANAME, OWNER FROM SYSCAT.SCHEMATA WHERE SCHEMANAME = 'DEMO';
-- Then confirm DEMO can actually create objects:
--   java -cp jcc.jar Db2ScriptRunner.java \
--     "jdbc:db2://$HOST:50000/BOBSDB" DEMO "$DEMO_PASS" /dev/stdin <<'EOF'
--   CREATE TABLE DEMO.T1(C1 INT NOT NULL) IN DEMOTBS;
--   DROP TABLE DEMO.T1;
--   EOF
-- -----------------------------------------------------------------------------
