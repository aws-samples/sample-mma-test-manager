-- =============================================================================
-- Bob's Used Books workshop - Amazon RDS for Db2 setup, PHASE 3 of 3
-- Runs against BOBSDB as the MASTER user (db2inst1)
-- =============================================================================
-- Read-only grants for DB2_RO_USER, used by MMA Test Manager to run comparison
-- queries against the source database with no possibility of side effects.
--
-- *** RUN ORDER MATTERS: this file must run AFTER 02/03/04. ***
-- GRANT SELECT names specific tables, so the tables must already exist.
--
-- This file is a FALLBACK. phase2 already issues GRANT SELECTIN ON SCHEMA DEMO,
-- which per the Db2 documentation covers current and future tables alike. If
-- that behaves as documented on RDS for Db2 12.1, this whole file is redundant
-- and every statement is a harmless re-grant. It exists because SELECTIN could
-- not be verified against a live instance, and a read-only user silently lacking
-- SELECT would break MMA Test Manager comparisons in a confusing way.
--
-- Db2 has no "GRANT SELECT ANY TABLE" as in Oracle, and no ALTER DEFAULT
-- PRIVILEGES as in PostgreSQL, which is why the Aurora side of this workshop can
-- do its equivalent up front and DeployPostgresSchemaDocument needs no third phase.
--
-- The Oracle track did this with PL/SQL looping over all_tables in a dedicated
-- createTestUser SSM step. Db2 has no anonymous block that can issue DDL this
-- way, so the grants are written out explicitly. If you add a table to
-- 02_schema_db2_v2.sql, add it here too.
--
-- Run:
--   java -cp jcc.jar Db2ScriptRunner.java \
--     "jdbc:db2://$HOST:50000/BOBSDB" db2inst1 "$MASTER_PASS" \
--     01_user_rds_db2_phase3.sql
-- =============================================================================


-- Lets DB2_RO_USER's comparison queries sort and hash-join, which need the
-- user temporary tablespace. ROLE_TABLESPACES in phase2 should already cover
-- this; repeated here because a read-only user failing on a large ORDER BY is
-- an obscure symptom to diagnose.
GRANT USE OF TABLESPACE DEMOTMP TO GROUP DB2ROGRP;


-- -----------------------------------------------------------------------------
-- SELECT on the 14 tables created by 02_schema_db2_v2.sql
-- -----------------------------------------------------------------------------
GRANT SELECT ON TABLE DEMO.BOOK_TYPES            TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.CONDITIONS            TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.GENRES                TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.PUBLISHERS            TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.BOOKS                 TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.BOOKS_COVER           TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.CUSTOMERS             TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.ADDRESSES             TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.LISTINGS              TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.ORDERS                TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.ORDER_ITEMS           TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.SHOPPING_CART_ITEMS   TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.PASSWORD_RESET_TOKENS TO GROUP DB2ROGRP;
GRANT SELECT ON TABLE DEMO.PERSISTENT_LOGINS     TO GROUP DB2ROGRP;

-- Schema-level SELECT is granted in phase2 via GRANT SELECTIN ON SCHEMA, which
-- per the Db2 documentation covers future tables too. These per-table grants are
-- a belt-and-braces fallback in case SELECTIN does not behave as documented on
-- RDS for Db2 12.1. If SELECTIN works, every statement below is a harmless
-- no-op re-grant.


-- -----------------------------------------------------------------------------
-- Views and MQTs from 04_complex_schema_db2_v2.sql
-- -----------------------------------------------------------------------------
-- Deliberately NOT enumerated here. The names depend on 04, which may be
-- iterated on, and a missing object would produce a confusing SQL0204N in the
-- middle of deployment. Db2ScriptRunner continues past failures and exits
-- non-zero, so a wrong name here would make the whole step report failure
-- without stopping it.
--
-- If MMA Test Manager needs to read the views or MQTs, add explicit
-- GRANT SELECT ON TABLE DEMO.<name> TO GROUP DB2ROGRP; lines once 04 is
-- confirmed against a live instance. Comparison queries normally target base
-- tables, so this is not needed for the default workshop flow.

-- EXECUTE on routines is likewise omitted: a read-only user should not invoke
-- procedures, several of which in 04 perform DML.


-- -----------------------------------------------------------------------------
-- Verify (run manually)
-- -----------------------------------------------------------------------------
-- Confirm the read-only user can read but not write:
--   java -cp jcc.jar Db2ScriptRunner.java \
--     "jdbc:db2://$HOST:50000/BOBSDB" DB2_RO_USER "$RO_PASS" /dev/stdin <<'EOF'
--   SELECT COUNT(*) FROM DEMO.BOOKS;
--   EOF
-- An INSERT should fail with SQLSTATE 42501.
-- -----------------------------------------------------------------------------
