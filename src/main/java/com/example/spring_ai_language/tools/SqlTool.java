package com.example.spring_ai_language.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Text-to-SQL Tool：LLM 通过 Function Calling 调用此类执行 SQL 查询
 * 只允许 SELECT 语句，防止恶意操作
 */
@Component
@Slf4j
public class SqlTool {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 执行安全校验后的 SELECT 查询
     *
     * @param sql 自然语言转换来的 SQL（由 LLM 生成）
     * @return JSON 格式的查询结果
     */

    @Tool(description = "执行SQL查询并返回结果，仅允许SELECT语句")
    public String executeQuery(@ToolParam(description = "要执行SQL语句，必须以SELECT开头")String sql){
        String trimmed=sql.trim().toUpperCase();
        if(!trimmed.startsWith("SELECT")){
            log.info("[SQL安全拦截]非SELECT语句被拒绝: {}", trimmed);
            throw new SecurityException("只允许执行SELECT查询，拒绝其他操作");
        }
        try{
            List<Map<String,Object>> rows=jdbcTemplate.queryForList(sql);
            return rows.isEmpty()?"[]":rows.toString();
        }catch (Exception e){
            log.error("[SQL执行失败] sql={} err={}", sql, e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取关键业务表的字段结构（提供给 LLM 生成正确 SQL 使用）
     */
    @Tool(description = "获取数据库中指定表的字段结构信息（列名、类型、注释）")
    public String getTableInfo(@ToolParam(description = "表名，如：sales_order, employee 等") String tableName) {
        try {
            List<Map<String,Object>> cols=jdbcTemplate.queryForList(
                    "SHOW FULL COLUMNS FROM"+tableName);
            return cols.toString();
        }catch (Exception e){
            return "{\"error\":\"获取表结构失败: " + e.getMessage() + "\"}";
        }
    }
    /**
     * 列出所有业务表（给 LLM 了解有哪些表可用）
     */
    @Tool(description = "列出数据库中所有用户业务表（不含系统表）")
    public String listTables() {
        try{
            List<Map<String,Object>> tables=jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME, TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'");
            return tables.toString();
        }catch (Exception e){
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public String getSchema() {
        StringBuilder sb = new StringBuilder();
        try {
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'");
            for (Map<String, Object> t : tables) {
                String name = t.get("TABLE_NAME").toString();
                List<Map<String, Object>> ddl = jdbcTemplate.queryForList(
                        "SHOW CREATE TABLE `" + name + "`");
                Map<String, Object> row = ddl.get(0);
                sb.append(row.get("Create Table")).append(";\n\n");
            }
        } catch (Exception e) {
            log.error("[getSchema失败]", e);
        }
        return sb.toString();
    }
}
