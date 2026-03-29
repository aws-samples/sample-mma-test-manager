package com.mma.testmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

@Service
@Slf4j
public class KiroCliCommonService {

    @Value("${mma.kiro.command}")
    private String kiroCommand;

    public String execute(String prompt) throws Exception {
        log.info("=== KIRO INPUT START ===");
        log.info("{}", prompt);
        log.info("=== KIRO INPUT END ===");
        
        // Safe: kiroCommand is from application.properties (not user input)
        // User input (prompt) is passed via stdin, not in command construction
        ProcessBuilder pb = new ProcessBuilder(kiroCommand.split(" "));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                log.error("Error reading Kiro output", e);
            }
        });
        readerThread.start();
        
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            writer.write(prompt);
            writer.newLine();
            writer.flush();
        }
        
        process.waitFor();
        readerThread.join();
        
        String fullOutput = output.toString();
        log.info("=== KIRO OUTPUT START ===");
        log.info("{}", fullOutput);
        log.info("=== KIRO OUTPUT END ===");
        
        return fullOutput;
    }

    public String extractCodeFromSQLMarker(String output, String fallbackKeyword) {
        return extractCodeFromSQLMarker(output, fallbackKeyword, false);
    }
    
    public String extractCodeFromSQLMarker(String output, String fallbackKeyword, boolean isDdlContext) {
        // Remove ANSI escape codes and terminal control characters
        String cleaned = output.replaceAll("\u001B\\[[;\\d]*[mGKHf]", "")
                               .replaceAll("\\[\\d*[mGKHf]", "")
                               .replaceAll("\\[\\d+[A-Za-z]", "")
                               .replaceAll("\\[\\?\\d+[a-z]", "")
                               .replaceAll("⠀+", "")
                               .replaceAll("[⢀⣴⣶⣦⡀⢰⣿⠋⠈⠙⡆⢸⠀⣇⣼⡿⢀⣾⠇⣴⠟⣼⡟⢻⣧⠸⣷⡄⠹⠘⢻⡇⠻⠿⠟⠁]+", "")
                               .replaceAll("╭[─]+.*?─+╮", "")
                               .replaceAll("╰[─]+.*?─+╯", "")
                               .replaceAll("│.*?│", "")
                               .replaceAll("Model:.*?\\)", "")
                               .replaceAll("▸\\s*Credits:.*?Time:\\s*\\d+[smh]+", "")
                               .replaceAll("▸\\s*Time:\\s*\\d+[smh]+", "")
                               .trim();
        
        // Try to find the LAST ```sql code block (most likely the final answer)
        int lastSqlBlockStart = cleaned.lastIndexOf("```sql");
        if (lastSqlBlockStart != -1) {
            int codeStart = cleaned.indexOf("\n", lastSqlBlockStart) + 1;
            int codeEnd = cleaned.indexOf("```", codeStart);
            if (codeEnd != -1) {
                String result = cleaned.substring(codeStart, codeEnd).trim();
                log.info("Extracted SQL from last ```sql block: {}", result);
                return result;
            }
        }
        
        // Try to find the LAST generic ``` code block
        int lastGenericBlockStart = cleaned.lastIndexOf("```");
        if (lastGenericBlockStart != -1 && lastGenericBlockStart != lastSqlBlockStart) {
            int codeStart = cleaned.indexOf("\n", lastGenericBlockStart) + 1;
            int codeEnd = cleaned.indexOf("```", codeStart);
            if (codeEnd != -1) {
                String result = cleaned.substring(codeStart, codeEnd).trim();
                log.info("Extracted SQL from last ``` block: {}", result);
                return result;
            }
        }
        
        // Try "> sql" marker (higher priority than plain "sql" label)
        int sqlMarker = cleaned.indexOf("> sql");
        if (sqlMarker != -1) {
            String afterMarker = cleaned.substring(sqlMarker + 6).trim();
            afterMarker = afterMarker.split("▸")[0].trim();
            String validated = isDdlContext ? validateAndExtractDDL(afterMarker) : validateAndExtractSQL(afterMarker);
            if (validated != null) {
                log.info("Extracted SQL from > sql marker: {}", validated);
                return validated;
            }
        }
        
        // Try to find "sql" label followed by SQL statements (without backticks)
        int sqlLabelIdx = cleaned.lastIndexOf("\nsql\n");
        if (sqlLabelIdx == -1) {
            sqlLabelIdx = cleaned.lastIndexOf("\nsql ");
        }
        if (sqlLabelIdx != -1) {
            String afterLabel = cleaned.substring(sqlLabelIdx + 5).trim();
            // For DDL context, prioritize DDL keywords (CREATE, ALTER, etc.)
            // For test context, prioritize DML keywords (BEGIN, SELECT, etc.)
            String validated = null;
            if (isDdlContext) {
                validated = validateAndExtractDDL(afterLabel);
                if (validated == null) {
                    validated = validateAndExtractSQL(afterLabel);
                }
            } else {
                validated = validateAndExtractSQL(afterLabel);
                if (validated == null) {
                    validated = validateAndExtractDDL(afterLabel);
                }
            }
            if (validated != null && validated.length() > 20) { // Ensure it's substantial SQL
                log.info("Extracted SQL from 'sql' label: {}", validated);
                return validated;
            }
        }
        
        // Fallback: search for keyword - look for it after removing markdown text
        if (fallbackKeyword != null) {
            // Remove common markdown patterns before keyword search
            String cleanedForFallback = cleaned.replaceAll("(?m)^[a-z]+\\s*$", ""); // Remove single word lines like "sql"
            
            int keywordIdx = cleanedForFallback.indexOf(fallbackKeyword);
            if (keywordIdx != -1) {
                String candidate = cleanedForFallback.substring(keywordIdx).trim();
                String validated = validateAndExtractSQL(candidate);
                if (validated != null) {
                    log.info("Extracted SQL from fallback keyword '{}': {}", fallbackKeyword, validated);
                    return validated;
                }
            }
        }
        
        // Final fallback: search for common keywords based on context
        String validated = isDdlContext ? validateAndExtractDDL(cleaned) : validateAndExtractSQL(cleaned);
        if (validated != null) {
            log.info("Extracted SQL from {} keyword search: {}", isDdlContext ? "DDL" : "SQL", validated);
            return validated;
        }
        
        log.info("Extracted SQL (no processing): {}", cleaned);
        return cleaned;
    }
    
    private String validateAndExtractDDL(String text) {
        String[] ddlKeywords = {"CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE"};
        
        for (String keyword : ddlKeywords) {
            int keywordIdx = text.toUpperCase().indexOf(keyword);
            if (keywordIdx != -1) {
                String fromKeyword = text.substring(keywordIdx);
                
                // Stop at tool execution markers
                int stopIdx = fromKeyword.length();
                String[] stopMarkers = {"\n ⋮", "\nRunning tool", "\n\n ▸"};
                for (String marker : stopMarkers) {
                    int markerIdx = fromKeyword.indexOf(marker);
                    if (markerIdx != -1 && markerIdx < stopIdx) {
                        stopIdx = markerIdx;
                    }
                }
                
                String candidate = fromKeyword.substring(0, stopIdx).trim();
                
                // Check for dollar-quoted strings (PostgreSQL functions)
                if (candidate.contains("$")) {
                    int dollarIdx = candidate.indexOf('$');
                    while (dollarIdx != -1 && dollarIdx < candidate.length() - 1) {
                        int nextDollar = candidate.indexOf('$', dollarIdx + 1);
                        if (nextDollar != -1) {
                            String dollarTag = candidate.substring(dollarIdx, nextDollar + 1);
                            int closingIdx = candidate.indexOf(dollarTag, nextDollar + 1);
                            if (closingIdx != -1) {
                                int endOfBlock = closingIdx + dollarTag.length();
                                // Check for additional statements after the dollar-quoted block
                                String remaining = candidate.substring(endOfBlock);
                                int lastSemicolon = remaining.lastIndexOf(';');
                                if (lastSemicolon != -1) {
                                    return candidate.substring(0, endOfBlock + lastSemicolon + 1).trim();
                                }
                                return candidate.substring(0, endOfBlock).trim();
                            }
                        }
                        dollarIdx = candidate.indexOf('$', dollarIdx + 1);
                    }
                }
                
                // Otherwise look for semicolon
                int lastSemicolon = candidate.lastIndexOf(';');
                if (lastSemicolon != -1) {
                    return candidate.substring(0, lastSemicolon + 1).trim();
                } else {
                    return candidate.trim();
                }
            }
        }
        
        return null;
    }
    
    private String validateAndExtractSQL(String text) {
        String[] sqlKeywords = {"DO $$", "BEGIN", "SELECT", "INSERT", "UPDATE", "DELETE", "CALL", "CREATE", "ALTER", "DROP", "EXEC"};
        
        for (String keyword : sqlKeywords) {
            int keywordIdx = text.toUpperCase().indexOf(keyword);
            if (keywordIdx != -1) {
                String fromKeyword = text.substring(keywordIdx);
                
                // Stop at tool execution markers or explanatory text
                int stopIdx = fromKeyword.length();
                String[] stopMarkers = {"\n ⋮", "\nRunning tool", "\n ▸", "\n\nThe ", "\n\nLet me ", "\n\nI ", "\n\nHere"};
                for (String marker : stopMarkers) {
                    int markerIdx = fromKeyword.indexOf(marker);
                    if (markerIdx != -1 && markerIdx < stopIdx) {
                        stopIdx = markerIdx;
                    }
                }
                
                String candidate = fromKeyword.substring(0, stopIdx).trim();
                
                // Check for dollar-quoted strings (PostgreSQL functions/blocks)
                if (candidate.contains("$")) {
                    int dollarIdx = candidate.indexOf('$');
                    while (dollarIdx != -1 && dollarIdx < candidate.length() - 1) {
                        int nextDollar = candidate.indexOf('$', dollarIdx + 1);
                        if (nextDollar != -1) {
                            String dollarTag = candidate.substring(dollarIdx, nextDollar + 1);
                            int closingIdx = candidate.indexOf(dollarTag, nextDollar + 1);
                            if (closingIdx != -1) {
                                int endOfBlock = closingIdx + dollarTag.length();
                                // Check for additional statements after the dollar-quoted block
                                String remaining = candidate.substring(endOfBlock);
                                int lastSemicolon = remaining.lastIndexOf(';');
                                if (lastSemicolon != -1) {
                                    return candidate.substring(0, endOfBlock + lastSemicolon + 1).trim();
                                }
                                return candidate.substring(0, endOfBlock).trim();
                            }
                        }
                        dollarIdx = candidate.indexOf('$', dollarIdx + 1);
                    }
                }
                
                int lastSemicolon = candidate.lastIndexOf(';');
                
                if (lastSemicolon != -1) {
                    return candidate.substring(0, lastSemicolon + 1).trim();
                } else {
                    return candidate.trim();
                }
            }
        }
        
        return null;
    }

    public String extractJSONArrayFromJSONMarker(String output) {
        // Try JSON markers first
        int startMarker = output.indexOf("<<<JSON_START>>>");
        int endMarker = output.indexOf("<<<JSON_END>>>");
        
        if (startMarker != -1 && endMarker != -1) {
            String result = output.substring(startMarker + 16, endMarker).trim();
            // Remove ANSI escape codes from extracted JSON
            result = result.replaceAll("\u001B\\[[;\\d]*m", "")
                          .replaceAll("\\[\\d+[A-Za-z]", "")
                          .replaceAll("\\[\\?\\d+[a-z]", "")
                          .trim();
            String validated = validateAndExtractJSONArray(result);
            if (validated != null) {
                log.info("Extracted JSON array from markers: {}", validated);
                return validated;
            }
        }
        
        // Fallback: find first JSON array
        String validated = validateAndExtractJSONArray(output);
        if (validated != null) {
            log.info("Extracted JSON array fallback: {}", validated);
            return validated;
        }
        
        log.error("No JSON array found in output");
        throw new RuntimeException("No JSON array found in Kiro output");
    }
    
    private String validateAndExtractJSONArray(String text) {
        int arrayStart = text.indexOf('[');
        
        if (arrayStart != -1) {
            int bracketCount = 1;
            
            // Find matching closing bracket
            for (int i = arrayStart + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '[') {
                    bracketCount++;
                } else if (c == ']') {
                    bracketCount--;
                    if (bracketCount == 0) {
                        return text.substring(arrayStart, i + 1).trim();
                    }
                }
            }
        }
        
        return null;
    }
}
