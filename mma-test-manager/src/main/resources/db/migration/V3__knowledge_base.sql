-- V5: Knowledge Base - Sample data only (tables created by JPA)
-- Insert sample knowledge bases after JPA creates the tables

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'Oracle to PostgreSQL DDL Conversion Best Practices',
    'General guidelines for converting Oracle DDL to PostgreSQL',
    'oracle',
    'postgres',
    'Best Practices for Oracle to PostgreSQL DDL Conversion:

1. Data Type Mappings:
   - NUMBER → NUMERIC or INTEGER (based on precision)
   - VARCHAR2 → VARCHAR
   - CLOB → TEXT
   - BLOB → BYTEA
   - DATE → TIMESTAMP (Oracle DATE includes time)

2. Sequence Handling:
   - Convert Oracle sequences to PostgreSQL SERIAL or IDENTITY columns where appropriate
   - Maintain sequence names for compatibility

3. Package Functions:
   - Convert to standalone functions or group in schemas
   - Handle RETURN statements properly

4. Exception Handling:
   - Convert Oracle exceptions to PostgreSQL RAISE statements
   - Map Oracle error codes to PostgreSQL equivalents

5. Preserve all mathematical operators: *, +, -, /, etc.',
    TRUE,
    10,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, 'DDL_CONVERSION' FROM knowledge_bases WHERE name = 'Oracle to PostgreSQL DDL Conversion Best Practices'
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'PostgreSQL Unit Test Guidelines',
    'Guidelines for generating PostgreSQL unit tests',
    'oracle',
    'postgres',
    'PostgreSQL Unit Test Generation Guidelines:

1. Test Coverage:
   - Always include happy path, edge cases, and error scenarios
   - Test NULL handling explicitly
   - Test boundary conditions for numeric inputs

2. Test Data:
   - Use realistic but simple test data
   - Avoid dependencies on external data when possible
   - Clean up test data if needed

3. Assertions:
   - Verify return values match expected results
   - Check for proper error handling
   - Validate data types in results',
    TRUE,
    10,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, 'UNIT_TEST' FROM knowledge_bases WHERE name = 'PostgreSQL Unit Test Guidelines'
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'Comparison Test Best Practices',
    'Guidelines for Oracle-PostgreSQL comparison testing',
    'oracle',
    'postgres',
    'Comparison Test Guidelines:

1. SQL Compatibility:
   - Ensure equivalent logic between Oracle and PostgreSQL versions
   - Account for syntax differences (e.g., NVL vs COALESCE)
   - Handle date/time format differences

2. Result Comparison:
   - Compare result sets, not just row counts
   - Account for floating-point precision differences
   - Handle NULL comparisons properly

3. Error Handling:
   - Map Oracle errors to PostgreSQL equivalents
   - Ensure both versions fail gracefully for invalid inputs',
    TRUE,
    10,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, 'COMPARISON_TEST' FROM knowledge_bases WHERE name = 'Comparison Test Best Practices'
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'Oracle to PostgreSQL Syntax Conversion Guidelines',
    'Common Oracle to PostgreSQL syntax mappings',
    'oracle',
    'postgres',
    'Convert Oracle → PostgreSQL syntax with best practices:
- SYSDATE → CURRENT_TIMESTAMP
- NVL → COALESCE  
- ROWNUM → LIMIT
- DECODE → CASE WHEN
- LISTAGG → STRING_AGG
- ADD_MONTHS → INTERVAL
- TO_DATE → DATE literal',
    TRUE,
    10,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, cat FROM knowledge_bases, (VALUES ('DDL_CONVERSION'), ('UNIT_TEST'), ('COMPARISON_TEST')) AS t(cat)
WHERE name = 'Oracle to PostgreSQL Syntax Conversion Guidelines'
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'SQL Server to PostgreSQL Exception Handling Conversion',
    'Guidelines for converting SQL Server TRY/CATCH to PostgreSQL exception handling',
    'sqlserver',
    'postgres',
    'SQL Server → PostgreSQL Exception Handling Conversion:

1. Exception Block Structure:
   - BEGIN TRY / BEGIN CATCH → EXCEPTION WHEN OTHERS THEN
   - END TRY / END CATCH → END (within function/procedure)

2. Error Information Functions:
   - ERROR_NUMBER() → RETURNED_SQLSTATE (via GET STACKED DIAGNOSTICS)
   - ERROR_MESSAGE() → MESSAGE_TEXT (via GET STACKED DIAGNOSTICS)
   - ERROR_SEVERITY() → Not directly available
   - ERROR_STATE() → RETURNED_SQLSTATE
   - ERROR_PROCEDURE() → Not directly available (use PG_EXCEPTION_CONTEXT)
   - ERROR_LINE() → Included in PG_EXCEPTION_CONTEXT
   - SUSER_SNAME() → CURRENT_USER

3. GET STACKED DIAGNOSTICS (must be in exception handler):
   - RETURNED_SQLSTATE → error code
   - MESSAGE_TEXT → error message
   - PG_EXCEPTION_DETAIL → additional detail
   - PG_EXCEPTION_HINT → hint message
   - PG_EXCEPTION_CONTEXT → call stack trace

4. Row Count Check:
   - @@ROWCOUNT → GET DIAGNOSTICS v_row_count = ROW_COUNT

5. Re-throwing Errors:
   - THROW → RAISE (re-raises current exception)
   - RAISERROR → RAISE EXCEPTION

6. Key Limitation:
   - GET STACKED DIAGNOSTICS must be called directly in the exception handler
   - Cannot delegate to a separate procedure like SQL Server''s uspLogError
   - Capture variables in EXCEPTION block, then pass to helper functions

7. Error Logging Pattern:
   - Capture error details with GET STACKED DIAGNOSTICS in EXCEPTION block
   - Pass captured variables to helper function for INSERT
   - Use COMMIT before RAISE to persist error log',
    TRUE,
    10,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, 'DDL_CONVERSION' FROM knowledge_bases WHERE name = 'SQL Server to PostgreSQL Exception Handling Conversion'
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'Use MCP Servers for Realistic Test Data - Oracle to PostgreSQL',
    'Guidelines for using MCP servers to query actual Oracle and PostgreSQL database values for test cases',
    'oracle',
    'postgres',
    'MCP Server Integration for Test Case Generation:

1. Access to Database Connections:
   - Use oracle-client-mcp to query source Oracle database
   - Use postgres-client-mcp to query target PostgreSQL database
   - Retrieve actual data values to make test cases more realistic and relevant

2. Test Data Strategy:
   - For UPDATE/DELETE operations: Query existing records to use actual IDs/keys that exist in the database
   - For SELECT operations: Use actual filter values that return real data
   - For INSERT operations: Check existing data to avoid constraint violations

3. Happy Path Test Cases:
   - Query sample records from relevant tables to use as test inputs
   - Ensure test values match actual data types and constraints
   - Use real foreign key values that exist in parent tables

4. Example Queries:
   - SELECT a few sample IDs: SELECT id FROM schema.table WHERE ROWNUM <= 3
   - Get valid foreign keys: SELECT DISTINCT fk_column FROM schema.table WHERE fk_column IS NOT NULL AND ROWNUM <= 5
   - Check data ranges: SELECT MIN(column), MAX(column) FROM schema.table

5. Benefits:
   - Tests are more realistic and meaningful
   - Reduces false failures from non-existent test data
   - Validates actual database state and constraints',
    TRUE,
    15,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, cat FROM knowledge_bases, (VALUES ('UNIT_TEST'), ('COMPARISON_TEST')) AS t(cat)
WHERE name = 'Use MCP Servers for Realistic Test Data - Oracle to PostgreSQL'
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'Use MCP Servers for Realistic Test Data - SQL Server to PostgreSQL',
    'Guidelines for using MCP servers to query actual SQL Server and PostgreSQL database values for test cases',
    'sqlserver',
    'postgres',
    'MCP Server Integration for Test Case Generation:

1. Access to Database Connections:
   - Use sqlserver-client-mcp to query source SQL Server database
   - Use postgres-client-mcp to query target PostgreSQL database
   - Retrieve actual data values to make test cases more realistic and relevant

2. Test Data Strategy:
   - For UPDATE/DELETE operations: Query existing records to use actual IDs/keys that exist in the database
   - For SELECT operations: Use actual filter values that return real data
   - For INSERT operations: Check existing data to avoid constraint violations

3. Happy Path Test Cases:
   - Query sample records from relevant tables to use as test inputs
   - Ensure test values match actual data types and constraints
   - Use real foreign key values that exist in parent tables

4. Example Queries:
   - SELECT a few sample IDs: SELECT TOP 3 id FROM schema.table
   - Get valid foreign keys: SELECT DISTINCT TOP 5 fk_column FROM schema.table WHERE fk_column IS NOT NULL
   - Check data ranges: SELECT MIN(column), MAX(column) FROM schema.table

5. Benefits:
   - Tests are more realistic and meaningful
   - Reduces false failures from non-existent test data
   - Validates actual database state and constraints',
    TRUE,
    15,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, cat FROM knowledge_bases, (VALUES ('UNIT_TEST'), ('COMPARISON_TEST')) AS t(cat)
WHERE name = 'Use MCP Servers for Realistic Test Data - SQL Server to PostgreSQL'
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'Procedure Testing with Transaction Rollback - Oracle to PostgreSQL',
    'Test stored procedures by querying data before/after execution with rollback to preserve data integrity',
    'oracle',
    'postgres',
    'Procedure Testing Strategy with Transaction Rollback:

1. Test Pattern for Data-Modifying Procedures:
   - Query relevant tables BEFORE procedure execution to capture initial state
   - Execute the procedure being tested
   - Query the same tables AFTER procedure execution to verify changes
   - ROLLBACK transaction to restore original data state

2. Oracle Syntax for Testing:
   a) Simple query-execute-query pattern (NO variables needed):
      ```sql
      SELECT COUNT(*) as before_count FROM schema.table WHERE condition;
      EXECUTE schema.procedure_name(param1, param2);
      SELECT COUNT(*) as after_count FROM schema.table WHERE condition;
      ROLLBACK;
      ```
   
   b) For procedures that return values or need PL/SQL block:
      ```sql
      DECLARE
          v_before_count NUMBER;
          v_after_count NUMBER;
      BEGIN
          SELECT COUNT(*) INTO v_before_count FROM schema.table WHERE condition;
          schema.procedure_name(param1, param2);
          SELECT COUNT(*) INTO v_after_count FROM schema.table WHERE condition;
          ROLLBACK;
      END;
      /
      ```
   
   c) CRITICAL: Oracle syntax rules:
      - Use EXECUTE or EXEC for standalone procedure calls (not CALL)
      - Use semicolons between statements
      - PL/SQL blocks need DECLARE section for variables
      - PL/SQL blocks must end with END; followed by / on new line
      - Do NOT use BEGIN; (that is PostgreSQL syntax)

3. PostgreSQL Syntax for Testing:
   ```sql
   BEGIN;
   SELECT COUNT(*) as before_count FROM schema.table WHERE condition;
   CALL schema.procedure_name(param1, param2);
   SELECT COUNT(*) as after_count FROM schema.table WHERE condition;
   ROLLBACK;
   ```

4. Implementation Steps:
   a) Use MCP server to query initial data state
   b) Execute the procedure with test parameters
   c) Query data again to verify procedure effects
   d) ROLLBACK to undo all changes

5. Benefits:
   - Validates procedure logic without data corruption
   - Enables repeatable testing on production-like data
   - Verifies actual database impact, not just return values
   - Safe for testing in shared environments

6. Test Case Structure:
   - Test 1: Happy path with valid data
   - Test 2: Edge cases (empty sets, boundary values)
   - Test 3: Error handling (invalid parameters, constraint violations)
   - Each test uses query/execute/query/ROLLBACK pattern',
    TRUE,
    20,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, cat FROM knowledge_bases, (VALUES ('UNIT_TEST'), ('COMPARISON_TEST')) AS t(cat)
WHERE name = 'Procedure Testing with Transaction Rollback - Oracle to PostgreSQL'
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_bases (name, description, source_db_engine, target_db_engine, content, active, priority, created_at, updated_at)
VALUES 
(
    'Procedure Testing with Transaction Rollback - SQL Server to PostgreSQL',
    'Test stored procedures by querying data before/after execution with rollback to preserve data integrity',
    'sqlserver',
    'postgres',
    'Procedure Testing Strategy with Transaction Rollback:

1. Test Pattern for Data-Modifying Procedures:
   - Query relevant tables BEFORE procedure execution to capture initial state
   - Execute the procedure being tested
   - Query the same tables AFTER procedure execution to verify changes
   - ROLLBACK transaction to restore original data state

2. SQL Server Syntax for Testing:
   a) Simple query-execute-query pattern:
      BEGIN TRANSACTION;
      SELECT COUNT(*) as before_count FROM schema.table WHERE condition;
      EXEC schema.procedure_name @param1 = value1, @param2 = value2;
      SELECT COUNT(*) as after_count FROM schema.table WHERE condition;
      ROLLBACK TRANSACTION;
   
   b) With variables:
      BEGIN TRANSACTION;
      DECLARE @before_count INT, @after_count INT;
      SELECT @before_count = COUNT(*) FROM schema.table WHERE condition;
      EXEC schema.procedure_name @param1 = value1, @param2 = value2;
      SELECT @after_count = COUNT(*) FROM schema.table WHERE condition;
      SELECT @before_count as before_count, @after_count as after_count;
      ROLLBACK TRANSACTION;
   
   c) CRITICAL: SQL Server syntax rules:
      - Use EXEC or EXECUTE for procedure calls (not CALL)
      - Use BEGIN TRANSACTION and ROLLBACK TRANSACTION
      - Parameters use @param_name = value syntax
      - Variables declared with DECLARE @var_name datatype
      - Use semicolons to separate statements

3. PostgreSQL Syntax for Testing:
   BEGIN;
   SELECT COUNT(*) as before_count FROM schema.table WHERE condition;
   CALL schema.procedure_name(param1, param2);
   SELECT COUNT(*) as after_count FROM schema.table WHERE condition;
   ROLLBACK;

4. Implementation Steps:
   a) Use MCP server to query initial data state
   b) Execute the procedure with test parameters
   c) Query data again to verify procedure effects
   d) ROLLBACK to undo all changes

5. Benefits:
   - Validates procedure logic without data corruption
   - Enables repeatable testing on production-like data
   - Verifies actual database impact, not just return values
   - Safe for testing in shared environments

6. Test Case Structure:
   - Test 1: Happy path with valid data
   - Test 2: Edge cases (empty sets, boundary values)
   - Test 3: Error handling (invalid parameters, constraint violations)
   - Each test uses query/execute/query/ROLLBACK pattern',
    TRUE,
    20,
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

INSERT INTO knowledge_base_categories (knowledge_base_id, category) 
SELECT id, cat FROM knowledge_bases, (VALUES ('UNIT_TEST'), ('COMPARISON_TEST')) AS t(cat)
WHERE name = 'Procedure Testing with Transaction Rollback - SQL Server to PostgreSQL'
ON CONFLICT DO NOTHING;

