-- =============================================================================
-- Bob's Used Books workshop - Db2 LUW cleanup
-- Converted from demo_cleanup.sql (Oracle)
-- =============================================================================
-- Drops every object in schema DEMO, in dependency order:
--   FK constraints -> MQTs -> views -> triggers -> tables -> sequences
--
-- DESTRUCTIVE. This deletes all data and all objects in schema DEMO. Read it
-- before running it.
--
-- ORACLE -> DB2 MAPPING
--   EXECUTE IMMEDIATE              -> EXECUTE IMMEDIATE (Db2 SQL PL has it too,
--                                     inside a compound-SQL-compiled block)
--   anonymous BEGIN...END; /       -> BEGIN ... END@  (a Db2 "anonymous block",
--                                     supported from Db2 9.7; needs -td@)
--   user_constraints (type 'R')    -> SYSCAT.REFERENCES
--   user_mviews                    -> SYSCAT.TABLES WHERE TYPE = 'S'
--                                     (an MQT/summary table is Db2's
--                                      materialized view)
--   user_views                     -> SYSCAT.VIEWS
--   user_tables                    -> SYSCAT.TABLES WHERE TYPE = 'T'
--   user_sequences (not ISEQ$$%)   -> SYSCAT.SEQUENCES WHERE SEQTYPE = 'S'
--                                     ('S' = real sequence, 'I' = the internal
--                                      generator behind an identity column,
--                                      which is Db2's analogue of Oracle's
--                                      ISEQ$$ objects and must not be dropped
--                                      directly -- it disappears with its table)
--   DROP TABLE t CASCADE CONSTRAINTS -> DROP TABLE t
--                                     (Db2 always drops dependent FKs, views
--                                      and triggers with the table; there is no
--                                      CASCADE CONSTRAINTS clause)
--   PURGE RECYCLEBIN               -> NOT CONVERTED. Db2 has no recycle bin;
--                                     DROP is immediate and space is returned
--                                     to the tablespace. Nothing to purge.
--   DBMS_OUTPUT.PUT_LINE           -> dropped (needs the Oracle compatibility
--                                     vector). Progress is instead visible from
--                                     the verification counts at the end.
--
-- Note the loops below use SYSCAT rather than SYSIBM.SYSTABLES: SYSCAT is the
-- supported catalog view layer, SYSIBM is internal.
--
-- Db2 CURSOR + WHILE is used rather than a FOR loop because a FOR loop holds
-- an open cursor over the catalog while the body mutates that same catalog,
-- which Db2 may reject with SQLSTATE 57007 (object in use).
--
-- Run as DEMO:  db2 -td@ -vf demo_cleanup_db2.sql
--
-- PLACEHOLDER: the CONNECT below uses <DEMO_PASSWORD>, not a real password.
-- This script is NOT run by the one-click stack, so nothing substitutes it for
-- you. Before running interactively, either replace the placeholder with the
-- DEMO password or drop the CONNECT line and connect first:
--   db2 connect to BOBSDB user DEMO using "$DEMO_PASSWORD"
-- =============================================================================

CONNECT TO BOBSDB USER DEMO USING <DEMO_PASSWORD>@
SET CURRENT SCHEMA DEMO@

-- -----------------------------------------------------------------------------
-- Step 1: drop all foreign key constraints
-- -----------------------------------------------------------------------------
BEGIN
    DECLARE V_STMT   VARCHAR(1000);
    DECLARE V_TAB    VARCHAR(128);
    DECLARE V_CONST  VARCHAR(128);
    DECLARE V_DONE   INTEGER DEFAULT 0;

    DECLARE C_FK CURSOR FOR
        SELECT TABNAME, CONSTNAME
          FROM SYSCAT.REFERENCES
         WHERE TABSCHEMA = 'DEMO';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_DONE = 1;
    -- Ignore "constraint does not exist" (SQLSTATE 42704): a constraint may
    -- already have gone when its table was dropped by an earlier partial run.
    DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END;

    OPEN C_FK;
    WHILE V_DONE = 0 DO
        FETCH C_FK INTO V_TAB, V_CONST;
        IF V_DONE = 0 THEN
            SET V_STMT = 'ALTER TABLE "' || V_TAB || '" DROP CONSTRAINT "' || V_CONST || '"';
            EXECUTE IMMEDIATE V_STMT;
        END IF;
    END WHILE;
    CLOSE C_FK;
END@

-- -----------------------------------------------------------------------------
-- Step 2: drop all MQTs (Oracle materialized views)
-- -----------------------------------------------------------------------------
-- An MQT is dropped with DROP TABLE, not DROP MATERIALIZED VIEW -- in Db2 it
-- IS a table (TYPE = 'S' for "summary table").
BEGIN
    DECLARE V_STMT VARCHAR(1000);
    DECLARE V_TAB  VARCHAR(128);
    DECLARE V_DONE INTEGER DEFAULT 0;

    DECLARE C_MQT CURSOR FOR
        SELECT TABNAME FROM SYSCAT.TABLES
         WHERE TABSCHEMA = 'DEMO' AND TYPE = 'S';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_DONE = 1;
    DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END;

    OPEN C_MQT;
    WHILE V_DONE = 0 DO
        FETCH C_MQT INTO V_TAB;
        IF V_DONE = 0 THEN
            SET V_STMT = 'DROP TABLE "' || V_TAB || '"';
            EXECUTE IMMEDIATE V_STMT;
        END IF;
    END WHILE;
    CLOSE C_MQT;
END@

-- -----------------------------------------------------------------------------
-- Step 3: drop all views
-- -----------------------------------------------------------------------------
-- Views may depend on each other, so a view dropped earlier in the loop can
-- invalidate a later one. The 42704 handler absorbs the resulting "not found".
BEGIN
    DECLARE V_STMT VARCHAR(1000);
    DECLARE V_VIEW VARCHAR(128);
    DECLARE V_DONE INTEGER DEFAULT 0;

    DECLARE C_VIEW CURSOR FOR
        SELECT VIEWNAME FROM SYSCAT.VIEWS WHERE VIEWSCHEMA = 'DEMO';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_DONE = 1;
    DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END;

    OPEN C_VIEW;
    WHILE V_DONE = 0 DO
        FETCH C_VIEW INTO V_VIEW;
        IF V_DONE = 0 THEN
            SET V_STMT = 'DROP VIEW "' || V_VIEW || '"';
            EXECUTE IMMEDIATE V_STMT;
        END IF;
    END WHILE;
    CLOSE C_VIEW;
END@

-- -----------------------------------------------------------------------------
-- Step 4: drop all triggers
-- -----------------------------------------------------------------------------
-- The Oracle script had no equivalent step because DROP TABLE CASCADE
-- CONSTRAINTS took the triggers with it. Db2 also drops a table's triggers with
-- the table, so this step is belt-and-braces for triggers left behind by a
-- partially failed earlier run.
BEGIN
    DECLARE V_STMT VARCHAR(1000);
    DECLARE V_TRIG VARCHAR(128);
    DECLARE V_DONE INTEGER DEFAULT 0;

    DECLARE C_TRIG CURSOR FOR
        SELECT TRIGNAME FROM SYSCAT.TRIGGERS WHERE TRIGSCHEMA = 'DEMO';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_DONE = 1;
    DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END;

    OPEN C_TRIG;
    WHILE V_DONE = 0 DO
        FETCH C_TRIG INTO V_TRIG;
        IF V_DONE = 0 THEN
            SET V_STMT = 'DROP TRIGGER "' || V_TRIG || '"';
            EXECUTE IMMEDIATE V_STMT;
        END IF;
    END WHILE;
    CLOSE C_TRIG;
END@

-- -----------------------------------------------------------------------------
-- Step 5: drop all tables
-- -----------------------------------------------------------------------------
BEGIN
    DECLARE V_STMT VARCHAR(1000);
    DECLARE V_TAB  VARCHAR(128);
    DECLARE V_DONE INTEGER DEFAULT 0;

    DECLARE C_TAB CURSOR FOR
        SELECT TABNAME FROM SYSCAT.TABLES
         WHERE TABSCHEMA = 'DEMO' AND TYPE = 'T';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_DONE = 1;
    DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END;

    OPEN C_TAB;
    WHILE V_DONE = 0 DO
        FETCH C_TAB INTO V_TAB;
        IF V_DONE = 0 THEN
            SET V_STMT = 'DROP TABLE "' || V_TAB || '"';
            EXECUTE IMMEDIATE V_STMT;
        END IF;
    END WHILE;
    CLOSE C_TAB;
END@

-- -----------------------------------------------------------------------------
-- Step 6: drop user-created sequences only
-- -----------------------------------------------------------------------------
-- Oracle filtered out ISEQ$$% (the hidden sequences behind identity columns).
-- Db2's equivalent filter is SEQTYPE: 'S' = a sequence created with CREATE
-- SEQUENCE, 'I' = the internal generator owned by an identity column. Dropping
-- an 'I' entry directly is not permitted; it is removed with its table in
-- step 5.
BEGIN
    DECLARE V_STMT VARCHAR(1000);
    DECLARE V_SEQ  VARCHAR(128);
    DECLARE V_DONE INTEGER DEFAULT 0;

    DECLARE C_SEQ CURSOR FOR
        SELECT SEQNAME FROM SYSCAT.SEQUENCES
         WHERE SEQSCHEMA = 'DEMO' AND SEQTYPE = 'S';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_DONE = 1;
    DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END;

    OPEN C_SEQ;
    WHILE V_DONE = 0 DO
        FETCH C_SEQ INTO V_SEQ;
        IF V_DONE = 0 THEN
            SET V_STMT = 'DROP SEQUENCE "' || V_SEQ || '"';
            EXECUTE IMMEDIATE V_STMT;
        END IF;
    END WHILE;
    CLOSE C_SEQ;
END@

-- -----------------------------------------------------------------------------
-- Step 7 (OPTIONAL): drop routines
-- -----------------------------------------------------------------------------
-- The Oracle script did NOT drop packages/procedures/functions, so the
-- converted routines from 04_complex_schema_db2_v2.sql would survive a cleanup
-- and be left invalid (pointing at dropped tables). Uncomment to drop them too.
-- SPECIFICNAME is used rather than ROUTINENAME because two routines here are
-- overloaded (get_low_stock_count, get_top_books) and only SPECIFICNAME is
-- unique.
--
-- BEGIN
--     DECLARE V_STMT VARCHAR(1000);
--     DECLARE V_SPEC VARCHAR(128);
--     DECLARE V_TYPE CHAR(1);
--     DECLARE V_DONE INTEGER DEFAULT 0;
--
--     DECLARE C_RTN CURSOR FOR
--         SELECT SPECIFICNAME, ROUTINETYPE FROM SYSCAT.ROUTINES
--          WHERE ROUTINESCHEMA = 'DEMO';
--
--     DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_DONE = 1;
--     DECLARE CONTINUE HANDLER FOR SQLSTATE '42704' BEGIN END;
--
--     OPEN C_RTN;
--     WHILE V_DONE = 0 DO
--         FETCH C_RTN INTO V_SPEC, V_TYPE;
--         IF V_DONE = 0 THEN
--             SET V_STMT = CASE V_TYPE
--                            WHEN 'P' THEN 'DROP SPECIFIC PROCEDURE "' || V_SPEC || '"'
--                            WHEN 'F' THEN 'DROP SPECIFIC FUNCTION "'  || V_SPEC || '"'
--                            ELSE NULL
--                          END;
--             IF V_STMT IS NOT NULL THEN
--                 EXECUTE IMMEDIATE V_STMT;
--             END IF;
--         END IF;
--     END WHILE;
--     CLOSE C_RTN;
-- END@

COMMIT@

-- =============================================================================
-- Verify cleanup -- every count should be 0
-- =============================================================================
SELECT 'Tables'         AS OBJECT_TYPE, COUNT(*) AS REMAINING FROM SYSCAT.TABLES     WHERE TABSCHEMA  = 'DEMO' AND TYPE = 'T'
UNION ALL SELECT 'MQTs',               COUNT(*) FROM SYSCAT.TABLES     WHERE TABSCHEMA  = 'DEMO' AND TYPE = 'S'
UNION ALL SELECT 'Views',              COUNT(*) FROM SYSCAT.VIEWS      WHERE VIEWSCHEMA = 'DEMO'
UNION ALL SELECT 'Triggers',           COUNT(*) FROM SYSCAT.TRIGGERS   WHERE TRIGSCHEMA = 'DEMO'
UNION ALL SELECT 'FK constraints',     COUNT(*) FROM SYSCAT.REFERENCES WHERE TABSCHEMA  = 'DEMO'
UNION ALL SELECT 'User sequences',     COUNT(*) FROM SYSCAT.SEQUENCES  WHERE SEQSCHEMA  = 'DEMO' AND SEQTYPE = 'S'
UNION ALL SELECT 'Identity sequences', COUNT(*) FROM SYSCAT.SEQUENCES  WHERE SEQSCHEMA  = 'DEMO' AND SEQTYPE = 'I'@

-- Routines are NOT dropped by default (see step 7); expect 24 unless enabled.
SELECT 'Routines' AS OBJECT_TYPE, COUNT(*) AS REMAINING
  FROM SYSCAT.ROUTINES WHERE ROUTINESCHEMA = 'DEMO'@

CONNECT RESET@
