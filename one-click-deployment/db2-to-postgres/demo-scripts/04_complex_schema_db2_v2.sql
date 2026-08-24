-- =============================================================================
-- Bob's Used Books workshop - Db2 LUW routines
-- Converted from 04_complex_schema_v2.sql (Oracle PL/SQL)
-- =============================================================================
-- Source inventory: 5 PACKAGES (book_pkg, order_pkg, inventory_pkg,
-- reporting_pkg, validation_pkg) contributing 17 routines, + 5 standalone
-- FUNCTIONs + 5 standalone PROCEDUREs = 27 callable routines.
-- This file creates 29: the extra 2 are 0-arg overloads standing in for Oracle
-- function parameter defaults, which Db2 functions cannot express.
--
-- PACKAGES: Db2 does have CREATE MODULE, but tooling support (including AWS
-- DMS Schema Conversion introspection) is thin, and Oracle package state has no
-- Db2 analogue. Every packaged routine is therefore flattened to a STANDALONE
-- routine named <package>_<routine>, preserving provenance and avoiding
-- collisions with the application schema's routines of the same short name:
--
--   book_pkg.get_available_quantity   -> BOOK_PKG_GET_AVAILABLE_QUANTITY
--   book_pkg.get_avg_price            -> BOOK_PKG_GET_AVG_PRICE
--   book_pkg.is_in_stock              -> BOOK_PKG_IS_IN_STOCK
--   book_pkg.get_book_details         -> BOOK_PKG_GET_BOOK_DETAILS
--   order_pkg.calculate_order_total   -> ORDER_PKG_CALCULATE_ORDER_TOTAL
--   order_pkg.get_customer_order_count-> ORDER_PKG_GET_CUSTOMER_ORDER_COUNT
--   order_pkg.create_order            -> ORDER_PKG_CREATE_ORDER
--   order_pkg.add_order_item          -> ORDER_PKG_ADD_ORDER_ITEM
--   order_pkg.update_order_status     -> ORDER_PKG_UPDATE_ORDER_STATUS
--   inventory_pkg.reduce_inventory    -> INVENTORY_PKG_REDUCE_INVENTORY
--   inventory_pkg.has_sufficient_inventory -> INVENTORY_PKG_HAS_SUFFICIENT_INV
--   inventory_pkg.get_low_stock_count -> INVENTORY_PKG_GET_LOW_STOCK_COUNT
--   reporting_pkg.get_top_books       -> REPORTING_PKG_GET_TOP_BOOKS (table fn)
--   reporting_pkg.get_customer_stats  -> REPORTING_PKG_GET_CUSTOMER_STATS
--   validation_pkg.is_valid_email     -> VALIDATION_PKG_IS_VALID_EMAIL
--   validation_pkg.is_valid_isbn      -> VALIDATION_PKG_IS_VALID_ISBN
--   validation_pkg.is_valid_price     -> VALIDATION_PKG_IS_VALID_PRICE
--
-- IDIOM MAPPING APPLIED THROUGHOUT
--   NUMBER (param/return)      -> BIGINT for ids/counts, DECIMAL(31,2) for money
--                                 (Db2 has no untyped NUMBER; every parameter
--                                 must commit to a precision)
--   PLS_INTEGER                -> INTEGER
--   col%TYPE                   -> the explicit underlying type (Db2 SQL PL has
--                                 no type anchoring)
--   NVL(a,b)                   -> COALESCE(a,b)
--   SYSTIMESTAMP               -> CURRENT TIMESTAMP
--   SYSDATE                    -> CURRENT DATE (or CURRENT TIMESTAMP where a
--                                 time component was clearly intended)
--   TRUNC(ts)                  -> DATE(ts)
--   RETURNING id INTO v        -> SELECT id INTO v FROM FINAL TABLE (INSERT ...)
--   SELECT ... FOR UPDATE      -> SELECT ... WITH RS USE AND KEEP UPDATE LOCKS
--                                 (Db2 SELECT INTO does not accept FOR UPDATE)
--   RAISE_APPLICATION_ERROR    -> SIGNAL SQLSTATE '75xxx' SET MESSAGE_TEXT
--   NO_DATA_FOUND handler      -> DECLARE CONTINUE HANDLER FOR NOT FOUND
--   SQL%ROWCOUNT               -> GET DIAGNOSTICS v = ROW_COUNT
--   DELETE FROM (subquery)     -> plain DELETE ... WHERE (see archive_old_orders)
--   PIPELINED table function   -> Db2 table function RETURNS TABLE(...)
--   REGEXP_LIKE / REGEXP_REPLACE -> same names (Db2 11.1+, ICU regex engine)
--   DBMS_OUTPUT.PUT_LINE       -> removed (see NOTES ON WHAT WAS DROPPED)
--
-- DEFAULT PARAMETERS: Db2 PROCEDUREs support "IN p T DEFAULT v" (11.1+), so
-- those are converted directly. Db2 FUNCTIONs do NOT support defaults, so each
-- defaulted function is published as an overloaded pair (0-arg wrapper calling
-- the 1-arg form). Db2 resolves overloads by argument count, so both
-- get_low_stock_count() and get_low_stock_count(3) keep working.
--
-- STATEMENT TERMINATOR IS #
--     db2 -td# -vf 04_complex_schema_db2_v2.sql
--
-- A non-semicolon terminator is required because routine bodies contain
-- semicolons. The usual choice is @, but validation_pkg.is_valid_email
-- contains an email regex with a literal '@' in it, and the Db2 CLP splits on
-- the terminator character WITHOUT respecting string literals -- so -td@ would
-- chop that CREATE FUNCTION in half. '#' does not appear anywhere in this file.
-- (The application's 03_routines_db2.sql has no such literal and still uses @.)
-- =============================================================================

CONNECT TO BOBSDB USER DEMO USING <DEMO_PASSWORD>#

-- Oracle: ALTER SESSION SET CURRENT_SCHEMA=demo
SET CURRENT SCHEMA DEMO#
SET PATH = SYSIBM, SYSFUN, SYSPROC, SYSIBMADM, DEMO#

-- =============================================================================
-- PRE-EXISTING DATA INCONSISTENCY IN THE ORACLE SOURCE (preserved, not fixed)
-- =============================================================================
-- Five routines filter on LISTING_TYPE = 'STORE':
--   book_pkg.get_available_quantity, book_pkg.get_avg_price,
--   inventory_pkg.get_low_stock_count, set_book_featured
--   (and book_pkg.is_in_stock / get_book_details transitively).
--
-- But 03_data_oracle_jpa_v2.sql seeds LISTING_TYPE as 'SYSTEM' (55 rows) and
-- 'CUSTOMER' (1 row) -- never 'STORE'. So against a freshly loaded demo
-- database these routines return 0 / 0.00 / no rows, on Oracle exactly as much
-- as on Db2. Only process_customer_offer('APPROVE') ever writes 'STORE'.
--
-- This is NOT a conversion defect and has deliberately not been "fixed" -- the
-- converted routines behave identically to the originals. If the demo is
-- supposed to show non-zero inventory out of the box, the fix belongs in the
-- seed data (or the predicates should read
--     LISTING_TYPE IN ('STORE','SYSTEM')
-- ), and it should be applied to both dialects together.
-- =============================================================================

-- =============================================================================
-- BOOK_PKG - Book Management Operations
-- =============================================================================

-- book_pkg.get_available_quantity
-- v_qty was listings.quantity%TYPE -> INTEGER
CREATE OR REPLACE FUNCTION BOOK_PKG_GET_AVAILABLE_QUANTITY(P_BOOK_ID BIGINT)
    RETURNS INTEGER
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_QTY INTEGER DEFAULT 0;
    SELECT COALESCE(SUM(QUANTITY), 0)
      INTO V_QTY
      FROM LISTINGS
     WHERE BOOK_ID = P_BOOK_ID
       AND STATUS = 1
       AND LISTING_TYPE = 'STORE';
    RETURN V_QTY;
END#

-- book_pkg.get_avg_price
-- v_avg was listings.price%TYPE -> DECIMAL(31,2).
-- BEHAVIOUR NOTE: Oracle's NUMBER kept the full average (e.g. 10.9533...).
-- DECIMAL(31,2) rounds it to 2 dp. Widen the return type to DECIMAL(31,6) if
-- callers depend on sub-cent precision.
CREATE OR REPLACE FUNCTION BOOK_PKG_GET_AVG_PRICE(P_BOOK_ID BIGINT)
    RETURNS DECIMAL(31,2)
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_AVG DECIMAL(31,2) DEFAULT 0;
    SELECT COALESCE(AVG(PRICE), 0)
      INTO V_AVG
      FROM LISTINGS
     WHERE BOOK_ID = P_BOOK_ID
       AND STATUS = 1
       AND LISTING_TYPE = 'STORE';
    RETURN V_AVG;
END#

-- book_pkg.is_in_stock
-- Returns 0/1 rather than a BOOLEAN, exactly as the Oracle original did
-- (which is fortunate: Db2 cannot return BOOLEAN to a JDBC caller).
CREATE OR REPLACE FUNCTION BOOK_PKG_IS_IN_STOCK(P_BOOK_ID BIGINT)
    RETURNS SMALLINT
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    RETURN CASE WHEN BOOK_PKG_GET_AVAILABLE_QUANTITY(P_BOOK_ID) > 0 THEN 1 ELSE 0 END;
END#

-- book_pkg.get_book_details
-- Oracle OUT VARCHAR2 (unsized) must become a sized OUT VARCHAR in Db2.
-- Sizes follow the BOOKS column definitions.
-- NOTE: if p_book_id does not exist, the Oracle version raised NO_DATA_FOUND to
-- the caller. Db2 raises SQLSTATE 02000 (not found) the same way, so behaviour
-- matches -- no handler added.
CREATE OR REPLACE PROCEDURE BOOK_PKG_GET_BOOK_DETAILS(
    IN  P_BOOK_ID   BIGINT,
    OUT P_TITLE     VARCHAR(255),
    OUT P_AUTHOR    VARCHAR(255),
    OUT P_TOTAL_QTY INTEGER,
    OUT P_AVG_PRICE DECIMAL(31,2))
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    SELECT TITLE, AUTHOR
      INTO P_TITLE, P_AUTHOR
      FROM BOOKS
     WHERE ID = P_BOOK_ID;

    SET P_TOTAL_QTY = BOOK_PKG_GET_AVAILABLE_QUANTITY(P_BOOK_ID);
    SET P_AVG_PRICE = BOOK_PKG_GET_AVG_PRICE(P_BOOK_ID);
END#

-- =============================================================================
-- ORDER_PKG - Order Processing Operations
-- =============================================================================

-- order_pkg.calculate_order_total
-- ARITHMETIC NOTE: BOOK_PRICE is DECIMAL(31,2) (was NUMBER(38,2)). In Db2,
-- DECIMAL(31,2) * INTEGER produces DECIMAL(31,2) -- precision is capped at 31,
-- so extreme values overflow at runtime rather than widening as Oracle's
-- NUMBER would. Not reachable with this data set.
CREATE OR REPLACE FUNCTION ORDER_PKG_CALCULATE_ORDER_TOTAL(P_ORDER_ID BIGINT)
    RETURNS DECIMAL(31,2)
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_TOTAL DECIMAL(31,2) DEFAULT 0;
    SELECT COALESCE(SUM(BOOK_PRICE * QUANTITY - COALESCE(DISCOUNT, 0)), 0)
      INTO V_TOTAL
      FROM ORDER_ITEMS
     WHERE ORDER_ID = P_ORDER_ID;
    RETURN V_TOTAL;
END#

-- order_pkg.get_customer_order_count
CREATE OR REPLACE FUNCTION ORDER_PKG_GET_CUSTOMER_ORDER_COUNT(P_CUSTOMER_ID BIGINT)
    RETURNS INTEGER
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_COUNT INTEGER DEFAULT 0;
    SELECT COUNT(*)
      INTO V_COUNT
      FROM ORDERS
     WHERE CUSTOMER_ID = P_CUSTOMER_ID;
    RETURN V_COUNT;
END#

-- order_pkg.create_order
-- Oracle: INSERT ... RETURNING id INTO v_order_id
-- Db2: SELECT ... FROM FINAL TABLE (INSERT ...) -- the data-change-table
-- reference, which returns the row as it exists after all triggers fire.
--
-- *** CONVERTED FROM FUNCTION TO PROCEDURE. *** Db2 SQL functions are declared
-- READS SQL DATA at most; a function cannot INSERT. Callers must change from
--     v := order_pkg.create_order(1, 2);
-- to
--     CALL ORDER_PKG_CREATE_ORDER(1, 2, ?);
CREATE OR REPLACE PROCEDURE ORDER_PKG_CREATE_ORDER(
    IN  P_CUSTOMER_ID BIGINT,
    IN  P_ADDRESS_ID  BIGINT,
    OUT P_ORDER_ID    BIGINT)
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    SELECT ID INTO P_ORDER_ID
      FROM FINAL TABLE (
        INSERT INTO ORDERS (CUSTOMER_ID, ADDRESS_ID, CREATED_ON, STATUS, TOTAL_AMOUNT)
        VALUES (P_CUSTOMER_ID, P_ADDRESS_ID, CURRENT TIMESTAMP, 'PENDING', 0)
      );
END#

-- order_pkg.add_order_item
-- v_book_id was listings.book_id%TYPE -> BIGINT
-- v_price   was listings.price%TYPE   -> DECIMAL(31,2)
CREATE OR REPLACE PROCEDURE ORDER_PKG_ADD_ORDER_ITEM(
    IN P_ORDER_ID   BIGINT,
    IN P_LISTING_ID BIGINT,
    IN P_QUANTITY   INTEGER)
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    DECLARE V_BOOK_ID BIGINT;
    DECLARE V_PRICE   DECIMAL(31,2);

    SELECT BOOK_ID, PRICE
      INTO V_BOOK_ID, V_PRICE
      FROM LISTINGS
     WHERE ID = P_LISTING_ID;

    INSERT INTO ORDER_ITEMS (ORDER_ID, BOOK_ID, LISTING_ID, QUANTITY, BOOK_PRICE)
    VALUES (P_ORDER_ID, V_BOOK_ID, P_LISTING_ID, P_QUANTITY, V_PRICE);

    UPDATE ORDERS
       SET TOTAL_AMOUNT = ORDER_PKG_CALCULATE_ORDER_TOTAL(P_ORDER_ID),
           UPDATED_ON   = CURRENT TIMESTAMP
     WHERE ID = P_ORDER_ID;
END#

-- order_pkg.update_order_status
-- Oracle assigned SYSDATE (a date+time) into DATE columns. Db2 DATE has no time
-- component, so CURRENT DATE is the faithful conversion for these columns --
-- the time-of-day Oracle stored was already being ignored by every reader.
--
-- The CASE expressions have no ELSE, so a non-matching status writes NULL over
-- any previously set date. That is Oracle's behaviour too and is preserved.
CREATE OR REPLACE PROCEDURE ORDER_PKG_UPDATE_ORDER_STATUS(
    IN P_ORDER_ID BIGINT,
    IN P_STATUS   VARCHAR(255))
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    UPDATE ORDERS
       SET STATUS         = P_STATUS,
           UPDATED_ON     = CURRENT TIMESTAMP,
           SHIPPED_DATE   = CASE WHEN P_STATUS = 'SHIPPED'   THEN CURRENT DATE END,
           DELIVERED_DATE = CASE WHEN P_STATUS = 'DELIVERED' THEN CURRENT DATE END,
           CANCELLED_DATE = CASE WHEN P_STATUS = 'CANCELLED' THEN CURRENT DATE END
     WHERE ID = P_ORDER_ID;
END#

-- =============================================================================
-- INVENTORY_PKG - Inventory Management
-- =============================================================================

-- inventory_pkg.reduce_inventory
-- TWO conversions worth reading:
--
--  1. SELECT quantity INTO v FROM listings WHERE id = ? FOR UPDATE
--     Db2's SELECT INTO does not accept FOR UPDATE. The equivalent
--     lock-on-read is the isolation clause
--         WITH RS USE AND KEEP UPDATE LOCKS
--     which takes an update lock held to end of transaction, giving the same
--     protection against a concurrent reader also decrementing the row.
--
--  2. RAISE_APPLICATION_ERROR(-20001, 'Insufficient inventory')
--     -> SIGNAL SQLSTATE '75001'. Db2 requires a 5-character SQLSTATE that does
--     not start with '00', '01' or '02'; class 75 is unassigned and
--     conventionally used for application errors. Callers that trapped
--     Oracle error -20001 must now trap SQLSTATE 75001.
CREATE OR REPLACE PROCEDURE INVENTORY_PKG_REDUCE_INVENTORY(
    IN P_LISTING_ID BIGINT,
    IN P_QUANTITY   INTEGER)
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    DECLARE V_CURRENT_QTY INTEGER;

    SELECT QUANTITY INTO V_CURRENT_QTY
      FROM LISTINGS
     WHERE ID = P_LISTING_ID
      WITH RS USE AND KEEP UPDATE LOCKS;

    IF V_CURRENT_QTY < P_QUANTITY THEN
        SIGNAL SQLSTATE '75001' SET MESSAGE_TEXT = 'Insufficient inventory';
    END IF;

    UPDATE LISTINGS
       SET QUANTITY   = QUANTITY - P_QUANTITY,
           UPDATED_ON = CURRENT TIMESTAMP
     WHERE ID = P_LISTING_ID;
END#

-- inventory_pkg.has_sufficient_inventory
CREATE OR REPLACE FUNCTION INVENTORY_PKG_HAS_SUFFICIENT_INV(
    P_LISTING_ID BIGINT,
    P_QUANTITY   INTEGER)
    RETURNS SMALLINT
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_CURRENT_QTY INTEGER;

    SELECT QUANTITY INTO V_CURRENT_QTY
      FROM LISTINGS
     WHERE ID = P_LISTING_ID;

    RETURN CASE WHEN V_CURRENT_QTY >= P_QUANTITY THEN 1 ELSE 0 END;
END#

-- inventory_pkg.get_low_stock_count(p_threshold NUMBER DEFAULT 5)
-- Db2 FUNCTIONs cannot declare parameter defaults, so the default is expressed
-- as an overload. v_count was PLS_INTEGER -> INTEGER.
CREATE OR REPLACE FUNCTION INVENTORY_PKG_GET_LOW_STOCK_COUNT(P_THRESHOLD INTEGER)
    RETURNS INTEGER
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_COUNT INTEGER;
    SELECT COUNT(*)
      INTO V_COUNT
      FROM LISTINGS
     WHERE STATUS = 1
       AND LISTING_TYPE = 'STORE'
       AND QUANTITY <= P_THRESHOLD;
    RETURN V_COUNT;
END#

CREATE OR REPLACE FUNCTION INVENTORY_PKG_GET_LOW_STOCK_COUNT()
    RETURNS INTEGER
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    RETURN INVENTORY_PKG_GET_LOW_STOCK_COUNT(5);
END#

-- =============================================================================
-- STANDALONE FUNCTIONS
-- =============================================================================

-- get_customer_lifetime_value
CREATE OR REPLACE FUNCTION GET_CUSTOMER_LIFETIME_VALUE(P_CUSTOMER_ID BIGINT)
    RETURNS DECIMAL(31,2)
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_TOTAL DECIMAL(31,2) DEFAULT 0;
    SELECT COALESCE(SUM(TOTAL_AMOUNT), 0)
      INTO V_TOTAL
      FROM ORDERS
     WHERE CUSTOMER_ID = P_CUSTOMER_ID
       AND STATUS NOT IN ('CANCELLED');
    RETURN V_TOTAL;
END#

-- get_genre_name
-- Oracle: EXCEPTION WHEN NO_DATA_FOUND THEN RETURN 'Unknown'
-- Db2: a CONTINUE HANDLER FOR NOT FOUND, which fires on SQLSTATE 02000.
-- v_name was genres.name%TYPE -> VARCHAR(255)
CREATE OR REPLACE FUNCTION GET_GENRE_NAME(P_GENRE_ID BIGINT)
    RETURNS VARCHAR(255)
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_NAME VARCHAR(255);
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_NAME = 'Unknown';

    SELECT NAME INTO V_NAME
      FROM GENRES
     WHERE ID = P_GENRE_ID;

    RETURN COALESCE(V_NAME, 'Unknown');
END#

-- get_condition_name
CREATE OR REPLACE FUNCTION GET_CONDITION_NAME(P_CONDITION_ID BIGINT)
    RETURNS VARCHAR(255)
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_NAME VARCHAR(255);
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_NAME = 'Unknown';

    SELECT NAME INTO V_NAME
      FROM CONDITIONS
     WHERE ID = P_CONDITION_ID;

    RETURN COALESCE(V_NAME, 'Unknown');
END#

-- calculate_discount_pct
-- DETERMINISTIC   -> DETERMINISTIC (same meaning)
-- PARALLEL_ENABLE -> no equivalent. Db2 has no ALLOW PARALLEL clause for
--                    inlined SQL scalar functions; the optimiser decides.
--                    NO EXTERNAL ACTION is added because it is what actually
--                    lets Db2 cache/reorder calls, which is the practical
--                    benefit Oracle's DETERMINISTIC + PARALLEL_ENABLE gave.
CREATE OR REPLACE FUNCTION CALCULATE_DISCOUNT_PCT(
    P_ORIGINAL_PRICE   DECIMAL(31,2),
    P_DISCOUNTED_PRICE DECIMAL(31,2))
    RETURNS DECIMAL(7,2)
    LANGUAGE SQL
    CONTAINS SQL
    DETERMINISTIC
    NO EXTERNAL ACTION
BEGIN
    IF P_ORIGINAL_PRICE = 0 THEN
        RETURN 0;
    END IF;
    -- Cast to a float-scale decimal before dividing: Db2 integer/decimal
    -- division truncates scale aggressively, so without the cast a 2 dp result
    -- would lose the cents that Oracle's NUMBER kept.
    RETURN ROUND(((P_ORIGINAL_PRICE - P_DISCOUNTED_PRICE) / CAST(P_ORIGINAL_PRICE AS DECFLOAT)) * 100, 2);
END#

-- get_customer_full_name
-- NULL CONCATENATION: Oracle treats NULL as '' in ||, Db2 propagates NULL. A
-- customer with a NULL LAST_NAME returned 'John ' in Oracle but would return
-- NULL here, so both parts are wrapped in COALESCE.
CREATE OR REPLACE FUNCTION GET_CUSTOMER_FULL_NAME(P_CUSTOMER_ID BIGINT)
    RETURNS VARCHAR(500)
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    DECLARE V_NAME VARCHAR(500);
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET V_NAME = 'Unknown Customer';

    SELECT COALESCE(FIRST_NAME, '') || ' ' || COALESCE(LAST_NAME, '')
      INTO V_NAME
      FROM CUSTOMERS
     WHERE ID = P_CUSTOMER_ID;

    RETURN COALESCE(V_NAME, 'Unknown Customer');
END#

-- =============================================================================
-- STANDALONE PROCEDURES
-- =============================================================================

-- process_customer_offer
-- RAISE_APPLICATION_ERROR(-20002, ...) -> SIGNAL SQLSTATE '75002'.
-- DEFAULT NULL on p_notes is supported for Db2 procedures (11.1+).
--
-- COMMIT INSIDE A ROUTINE: Db2 permits it in an SQL PL procedure, but only if
-- the procedure is not invoked from within a trigger, a function, or an ATOMIC
-- compound statement -- Db2 raises SQLSTATE 2D522 in those contexts. The Oracle
-- original had the same design smell (a service procedure committing on behalf
-- of its caller); it is preserved so the demo behaves identically.
CREATE OR REPLACE PROCEDURE PROCESS_CUSTOMER_OFFER(
    IN P_LISTING_ID BIGINT,
    IN P_ACTION     VARCHAR(20),
    IN P_ADMIN_ID   BIGINT,
    IN P_NOTES      VARCHAR(1000) DEFAULT NULL)
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    IF P_ACTION = 'APPROVE' THEN
        UPDATE LISTINGS
           SET STATUS       = 1,
               LISTING_TYPE = 'STORE',
               PROCESSED_AT = CURRENT TIMESTAMP,
               PROCESSED_BY = P_ADMIN_ID,
               ADMIN_NOTES  = P_NOTES,
               UPDATED_ON   = CURRENT TIMESTAMP
         WHERE ID = P_LISTING_ID;
    ELSEIF P_ACTION = 'REJECT' THEN
        UPDATE LISTINGS
           SET STATUS       = 2,
               PROCESSED_AT = CURRENT TIMESTAMP,
               PROCESSED_BY = P_ADMIN_ID,
               ADMIN_NOTES  = P_NOTES,
               UPDATED_ON   = CURRENT TIMESTAMP
         WHERE ID = P_LISTING_ID;
    ELSE
        SIGNAL SQLSTATE '75002' SET MESSAGE_TEXT = 'Invalid action. Use APPROVE or REJECT';
    END IF;
    COMMIT;
END#

-- set_book_featured
-- p_is_featured was NUMBER -> SMALLINT, matching the LISTINGS.IS_FEATURED
-- column and its CHECK (0,1) constraint.
CREATE OR REPLACE PROCEDURE SET_BOOK_FEATURED(
    IN P_BOOK_ID     BIGINT,
    IN P_IS_FEATURED SMALLINT)
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    UPDATE LISTINGS
       SET IS_FEATURED = P_IS_FEATURED,
           UPDATED_ON  = CURRENT TIMESTAMP
     WHERE BOOK_ID = P_BOOK_ID
       AND LISTING_TYPE = 'STORE'
       AND STATUS = 1;
    COMMIT;
END#

-- clear_shopping_cart
CREATE OR REPLACE PROCEDURE CLEAR_SHOPPING_CART(IN P_CUSTOMER_ID BIGINT)
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    DELETE FROM SHOPPING_CART_ITEMS
     WHERE CUSTOMER_ID = P_CUSTOMER_ID
       AND IS_WISHLIST_ITEM = 0;
    COMMIT;
END#

-- generate_sales_report
-- DATE PARAMETERS + TIMESTAMP COLUMN: created_on is TIMESTAMP(6) but the
-- parameters are DATE. Db2 promotes the DATE to midnight, so
--     created_on BETWEEN DATE '2026-01-01' AND DATE '2026-01-31'
-- excludes everything after 00:00:00 on the 31st. Oracle behaved identically
-- (its DATE literal is also midnight), so the boundary bug is faithfully
-- preserved rather than silently fixed. To include the whole end day, callers
-- should pass p_end_date + 1, or the predicate can be changed to
--     CREATED_ON >= P_START_DATE AND CREATED_ON < P_END_DATE + 1 DAY
CREATE OR REPLACE PROCEDURE GENERATE_SALES_REPORT(
    IN  P_START_DATE      DATE,
    IN  P_END_DATE        DATE,
    OUT P_TOTAL_ORDERS    INTEGER,
    OUT P_TOTAL_REVENUE   DECIMAL(31,2),
    OUT P_AVG_ORDER_VALUE DECIMAL(31,2))
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    SELECT COUNT(*),
           COALESCE(SUM(TOTAL_AMOUNT), 0),
           COALESCE(AVG(TOTAL_AMOUNT), 0)
      INTO P_TOTAL_ORDERS, P_TOTAL_REVENUE, P_AVG_ORDER_VALUE
      FROM ORDERS
     WHERE CREATED_ON BETWEEN P_START_DATE AND P_END_DATE
       AND STATUS NOT IN ('CANCELLED');
END#

-- archive_old_orders
-- FOUR conversions here:
--
--  1. v_cutoff_date was orders.created_on%TYPE -> TIMESTAMP.
--     SYSDATE - p_days_old -> CURRENT TIMESTAMP - P_DAYS_OLD DAYS.
--     (Oracle's "date minus number" means days; Db2 requires the DAYS label.)
--
--  2. DELETE FROM (SELECT * FROM orders WHERE ...) -- an Oracle updatable
--     inline view, which the source comments flag as "causes DMS SC issue
--     5068". Db2 has no such construct; the subquery is inlined into an
--     ordinary DELETE ... WHERE, which is semantically identical and is what
--     the Oracle statement compiled down to anyway.
--
--  3. SQL%ROWCOUNT -> GET DIAGNOSTICS V_ROWS = ROW_COUNT. This must be the
--     statement immediately after the DELETE; any intervening statement resets
--     the diagnostics area.
--
--  4. DBMS_OUTPUT.PUT_LINE of the row count is DROPPED and replaced with an
--     OUT parameter. See NOTES ON WHAT WAS DROPPED for why. Callers change
--     from CALL archive_old_orders(365) to CALL ARCHIVE_OLD_ORDERS(365, ?).
--
-- SAFETY NOTE: as in the original, this DELETES rather than archives. There is
-- no archive table. Read the WHERE clause before running it against anything
-- you care about.
CREATE OR REPLACE PROCEDURE ARCHIVE_OLD_ORDERS(
    IN  P_DAYS_OLD INTEGER DEFAULT 365,
    OUT P_ROWS_ARCHIVED INTEGER)
    LANGUAGE SQL
    MODIFIES SQL DATA
BEGIN
    DECLARE V_CUTOFF_DATE TIMESTAMP;
    DECLARE V_ROWS        INTEGER DEFAULT 0;

    SET V_CUTOFF_DATE = CURRENT TIMESTAMP - P_DAYS_OLD DAYS;

    DELETE FROM ORDERS
     WHERE CREATED_ON < V_CUTOFF_DATE
       AND STATUS IN ('DELIVERED', 'CANCELLED');

    GET DIAGNOSTICS V_ROWS = ROW_COUNT;
    SET P_ROWS_ARCHIVED = V_ROWS;

    COMMIT;
END#

-- =============================================================================
-- REPORTING_PKG - Reporting and Analytics
-- =============================================================================

-- reporting_pkg.get_top_books -- Oracle PIPELINED table function
--
-- Oracle used a package-level TYPE RECORD + TABLE OF + PIPE ROW in a loop.
-- Db2 has no PIPE ROW; a table function declares its shape with
-- RETURNS TABLE(...) and returns a single result set, which is both simpler
-- and faster (no row-at-a-time pipelining).
--
-- FETCH FIRST p_limit ROWS ONLY with a *variable* limit is accepted by Db2
-- 11.1+, but it is rejected by older releases and by some parsers, so the
-- limit is applied with ROW_NUMBER() instead -- portable across all versions.
--
-- The Oracle ORDER BY total_sold DESC only ordered the rows fed into the pipe;
-- a table function's output order is not guaranteed by either engine, so
-- callers should add their own ORDER BY:
--     SELECT * FROM TABLE(REPORTING_PKG_GET_TOP_BOOKS(10)) ORDER BY TOTAL_SOLD DESC;
--
-- NOTE ON SYNTAX: a Db2 SQL table function body is a bare `RETURN <select>`
-- directly after the options, NOT wrapped in BEGIN ... END. Wrapping it fails
-- with SQLCODE=-104 (SQLSTATE 42601) at the RETURN, because a compound statement
-- may only return a scalar. This differs from the scalar functions above, which
-- do use BEGIN ... END.
CREATE OR REPLACE FUNCTION REPORTING_PKG_GET_TOP_BOOKS(P_LIMIT INTEGER)
    RETURNS TABLE (
        BOOK_ID    BIGINT,
        TITLE      VARCHAR(255),
        TOTAL_SOLD INTEGER,
        REVENUE    DECIMAL(31,2))
    LANGUAGE SQL
    READS SQL DATA
    RETURN
        SELECT BOOK_ID, TITLE, TOTAL_SOLD, REVENUE
          FROM (
            SELECT B.ID                                        AS BOOK_ID,
                   B.TITLE                                     AS TITLE,
                   SUM(OI.QUANTITY)                            AS TOTAL_SOLD,
                   SUM(OI.BOOK_PRICE * OI.QUANTITY)            AS REVENUE,
                   ROW_NUMBER() OVER (ORDER BY SUM(OI.QUANTITY) DESC) AS RN
              FROM BOOKS B
              JOIN ORDER_ITEMS OI ON B.ID = OI.BOOK_ID
              JOIN ORDERS O       ON OI.ORDER_ID = O.ID
             WHERE O.STATUS NOT IN ('CANCELLED')
             GROUP BY B.ID, B.TITLE
          ) AS RANKED
         WHERE RN <= P_LIMIT#

-- Overload supplying the Oracle DEFAULT 10 (Db2 functions have no defaults).
-- Db2 table-function overloads on argument count resolve correctly.
CREATE OR REPLACE FUNCTION REPORTING_PKG_GET_TOP_BOOKS()
    RETURNS TABLE (
        BOOK_ID    BIGINT,
        TITLE      VARCHAR(255),
        TOTAL_SOLD INTEGER,
        REVENUE    DECIMAL(31,2))
    LANGUAGE SQL
    READS SQL DATA
    RETURN SELECT BOOK_ID, TITLE, TOTAL_SOLD, REVENUE
             FROM TABLE(REPORTING_PKG_GET_TOP_BOOKS(10))#

-- reporting_pkg.get_customer_stats
-- MAX(TRUNC(created_on)) -> MAX(DATE(created_on)). Oracle's TRUNC on a
-- timestamp zeroes the time; Db2's DATE() casts it away, which is the same
-- result in a DATE-typed OUT parameter.
CREATE OR REPLACE PROCEDURE REPORTING_PKG_GET_CUSTOMER_STATS(
    IN  P_CUSTOMER_ID    BIGINT,
    OUT P_TOTAL_ORDERS   INTEGER,
    OUT P_TOTAL_SPENT    DECIMAL(31,2),
    OUT P_AVG_ORDER      DECIMAL(31,2),
    OUT P_LAST_ORDER_DATE DATE)
    LANGUAGE SQL
    READS SQL DATA
BEGIN
    SELECT COUNT(*),
           COALESCE(SUM(TOTAL_AMOUNT), 0),
           COALESCE(AVG(TOTAL_AMOUNT), 0),
           MAX(DATE(CREATED_ON))
      INTO P_TOTAL_ORDERS, P_TOTAL_SPENT, P_AVG_ORDER, P_LAST_ORDER_DATE
      FROM ORDERS
     WHERE CUSTOMER_ID = P_CUSTOMER_ID
       AND STATUS NOT IN ('CANCELLED');
END#

-- =============================================================================
-- VALIDATION_PKG - Data Validation
-- =============================================================================

-- validation_pkg.is_valid_email
-- Db2 11.1+ provides REGEXP_LIKE. The regex engine is ICU rather than Oracle's
-- POSIX-plus-extensions, but this pattern (character classes, escaped dot,
-- {2,} bound, ^ and $ anchors) is identical in both dialects.
--
-- Db2 REGEXP_LIKE is a predicate, so it is legal in a CASE search condition.
-- A NULL input yields NULL from REGEXP_LIKE, which falls through to ELSE 0 --
-- same as Oracle.
CREATE OR REPLACE FUNCTION VALIDATION_PKG_IS_VALID_EMAIL(P_EMAIL VARCHAR(255))
    RETURNS SMALLINT
    LANGUAGE SQL
    CONTAINS SQL
    DETERMINISTIC
    NO EXTERNAL ACTION
BEGIN
    RETURN CASE
        WHEN REGEXP_LIKE(P_EMAIL, '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
        THEN 1
        ELSE 0
    END;
END#

-- validation_pkg.is_valid_isbn
-- REGEXP_REPLACE(x, '[^0-9X]', '') -- Db2's signature is
-- REGEXP_REPLACE(source, pattern, replacement, start, occurrence, flags);
-- omitting the trailing arguments replaces all occurrences, matching Oracle.
CREATE OR REPLACE FUNCTION VALIDATION_PKG_IS_VALID_ISBN(P_ISBN VARCHAR(255))
    RETURNS SMALLINT
    LANGUAGE SQL
    CONTAINS SQL
    DETERMINISTIC
    NO EXTERNAL ACTION
BEGIN
    RETURN CASE
        WHEN LENGTH(REGEXP_REPLACE(P_ISBN, '[^0-9X]', '')) IN (10, 13)
        THEN 1
        ELSE 0
    END;
END#

-- validation_pkg.is_valid_price
CREATE OR REPLACE FUNCTION VALIDATION_PKG_IS_VALID_PRICE(P_PRICE DECIMAL(31,2))
    RETURNS SMALLINT
    LANGUAGE SQL
    CONTAINS SQL
    DETERMINISTIC
    NO EXTERNAL ACTION
BEGIN
    RETURN CASE
        WHEN P_PRICE > 0 AND P_PRICE < 10000
        THEN 1
        ELSE 0
    END;
END#

-- =============================================================================
-- NOTES ON WHAT WAS DROPPED
-- =============================================================================
-- 1. DBMS_OUTPUT.PUT_LINE (14 calls: 1 in archive_old_orders, 13 in the
--    trailing verification block).
--    Db2 does ship an Oracle-compatible DBMS_OUTPUT module, but only when the
--    instance is started with DB2_COMPATIBILITY_VECTOR set to enable it, and
--    it is unavailable on some managed offerings. Referencing it
--    unconditionally would make this entire script fail at CREATE time on a
--    stock instance, so:
--      * in archive_old_orders the value is returned via a new OUT parameter
--        (strictly more useful -- a caller can act on it);
--      * the verification block below is documented as manual master-user
--        queries rather than executed here, because DEMO has no SELECT on the
--        system catalog on RDS for Db2;
--
-- 2. Package specifications. Nothing is lost functionally: Db2 controls
--    visibility with GRANT EXECUTE per routine rather than with a package
--    spec/body split. None of these packages held package-level state
--    (no variables outside the routine bodies), so no state emulation via a
--    session global temporary table is needed.
--
-- 3. reporting_pkg's TYPE book_sales_rec / book_sales_tab. Subsumed by the
--    table function's RETURNS TABLE(...) clause.
--
-- 4. PARALLEL_ENABLE on calculate_discount_pct -- no Db2 clause exists for
--    inlined SQL scalar functions.
--
-- SIGNATURE CHANGES CALLERS MUST ABSORB
--   order_pkg.create_order  FUNCTION -> PROCEDURE with an OUT parameter
--                           (a Db2 function cannot INSERT)
--   archive_old_orders      gained OUT P_ROWS_ARCHIVED
--   inventory_pkg.get_low_stock_count, reporting_pkg.get_top_books
--                           defaults expressed as 0-arg overloads
--   error -20001 / -20002   now SQLSTATE '75001' / '75002'
-- =============================================================================

-- =============================================================================
-- VERIFICATION (replaces the Oracle DBMS_OUTPUT block)
-- =============================================================================
-- The two SYSCAT.ROUTINES queries that used to live here are gone: on RDS for
-- Db2, DEMO has no SELECT on the system catalog, so they failed with
-- SQLCODE=-551 (SQLSTATE 42501) and - because Db2ScriptRunner exits non-zero if
-- ANY statement errored - failed the whole deployment step even though all 29
-- routines had been created successfully.
--
-- Granting DEMO SELECT on SYSCAT.ROUTINES is not an option: the master user on
-- RDS for Db2 cannot grant on the system catalog. Verification therefore belongs
-- outside this script, run as the master user, e.g.
--
--   SELECT ROUTINENAME, ROUTINETYPE, VALID FROM SYSCAT.ROUTINES
--    WHERE ROUTINESCHEMA = 'DEMO' ORDER BY ROUTINETYPE, ROUTINENAME;
--   SELECT ROUTINENAME, VALID FROM SYSCAT.ROUTINES
--    WHERE ROUTINESCHEMA = 'DEMO' AND VALID <> 'Y';
--
-- Expect 29 rows: 18 functions + 11 procedures. The Oracle source had 27
-- callable routines (17 packaged + 10 standalone). The count rises to 29 because
-- two defaulted functions gained 0-arg overloads (get_low_stock_count,
-- get_top_books). One routine also changed kind: order_pkg.create_order went
-- from FUNCTION to PROCEDURE.
-- =============================================================================

CONNECT RESET#
