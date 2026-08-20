package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpArg;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class Db2LuwMcpTools {

    private static final Logger logger = LoggerFactory.getLogger(Db2LuwMcpTools.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @McpTool(name = "db2_execute_sql", description = "Execute SQL statements and return results for Db2 LUW database operations")
    public Map<String, Object> executeSql(@McpArg(description = "SQL statement to execute") String sql) {
        logger.info("Executing SQL: {}", sql);
        Map<String, Object> result = new HashMap<>();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    List<Map<String, Object>> rows = mapRows(rs);
                    result.put("success", true);
                    result.put("data", rows);
                    result.put("rowCount", rows.size());
                    logger.info("SQL query executed successfully, returned {} rows", rows.size());
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                result.put("success", true);
                result.put("updateCount", updateCount);
                logger.info("SQL update executed successfully, affected {} rows", updateCount);
            }

        } catch (Exception e) {
            logger.error("SQL execution failed: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    @McpTool(name = "db2_execute_testcase_readonly", description = "Execute test case SQL statements with execution timing and guaranteed no side effects through read-only connections")
    public Map<String, Object> executeTestCaseReadOnly(@McpArg(description = "SQL statement to execute") String sql) {
        logger.info("Executing read-only test case SQL: {}", sql);
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            executeReadOnly(sql, result);

            long duration = System.currentTimeMillis() - startTime;
            result.put("success", true);
            result.put("executionTimeMs", duration);
            logger.info("Read-only test case executed successfully in {} ms", duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Read-only test case execution failed: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("executionTimeMs", duration);
        }

        return result;
    }

    @McpTool(name = "db2_execute_testcase_rollback", description = "Execute test case SQL statements with execution timing and guaranteed no side effects through transactional rollback")
    public Map<String, Object> executeTestCaseRollback(@McpArg(description = "SQL statement to execute") String sql) {
        logger.info("Executing rollback test case SQL: {}", sql);
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            executeWithRollback(sql, result);

            long duration = System.currentTimeMillis() - startTime;
            result.put("success", true);
            result.put("executionTimeMs", duration);
            logger.info("Rollback test case executed successfully in {} ms", duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Rollback test case execution failed: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("executionTimeMs", duration);
        }

        return result;
    }

    private void executeReadOnly(String sql, Map<String, Object> result) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                boolean hasResultSet = stmt.execute(sql);
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        List<Map<String, Object>> rows = mapRows(rs);
                        result.put("data", rows);
                        result.put("rowCount", rows.size());
                    }
                } else {
                    result.put("updateCount", stmt.getUpdateCount());
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
                conn.setReadOnly(false);
            }
        }
    }

    private void executeWithRollback(String sql, Map<String, Object> result) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                boolean hasResultSet = stmt.execute(sql);
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        List<Map<String, Object>> rows = mapRows(rs);
                        result.put("data", rows);
                        result.put("rowCount", rows.size());
                    }
                } else {
                    result.put("updateCount", stmt.getUpdateCount());
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Materialises a ResultSet into a list of ordered maps. Reading from the supplied
     * ResultSet keeps the read-only / rollback guarantees intact, because the rows come
     * from the same connection and transaction as the statement that produced them.
     */
    private static List<Map<String, Object>> mapRows(ResultSet rs) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String label = metaData.getColumnLabel(i);
                if (label == null || label.isEmpty()) {
                    label = metaData.getColumnName(i);
                }
                row.put(label, rs.getObject(i));
            }
            rows.add(row);
        }

        return rows;
    }
}
