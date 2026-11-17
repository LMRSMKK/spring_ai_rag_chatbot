package com.example.spring_ai_language.service;

import com.example.spring_ai_language.tools.SqlTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TextToSqlService {
    @Autowired
    private ChatClient client;
    @Autowired
    private SqlTool sqlTool;

    /**
     * 执行 Text-to-SQL 问答
     *
     * @param question  用户自然语言问题
     * @param userId    用户ID（用于权限审计）
     * @return 最终自然语言回答
     */
    public String anwserWithSql(String question,String userId,ChatClient client){
        // ①获取数据库表结构（作为Schema上下文发给LLM）
        String schema = sqlTool.getSchema();
        log.debug("[TextToSql] 获取到表结构，长度={}", schema.length());

        // ②让LLM生成SQL
        String generatePrompt = """
                你是一位精通SQL的数据分析师。根据以下数据库表结构，将用户的自然语言问题转为可执行的 SELECT 语句。\
                要求：\
                1. 只输出SQL，不要任何解释或其他文字\
                2. 确保 SQL 语法正确，字段名与表结构完全一致\
                3. 不要输出分号\
                4. 日期字段使用 YYYY-MM-DD 格式

                【数据库表结构】
                %s

                【用户问题】
                %s
                """.formatted(schema, question);
        String sql;
        try{
            String raw = client.prompt()
                    .user(generatePrompt)
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            // 清理 Ollama/部分 LLM 自动添加的 markdown 代码块包裹：```sql\n...\n```
            sql = raw.strip().replaceAll("(?s)^```\\w*\\s*|\\s*```$","").strip();
            log.debug("[TextToSql] 生成的SQL: {}", sql);
        }catch (Exception e){
            log.error("[TextToSql] SQL生成失败: {}", e.getMessage());
            return "抱歉，SQL 生成过程中出现异常，请联系管理员。";
        }

        // ③执行SQL（通过Tool，Tool内部做了安全校验只允许SELECT）
        String queryResult;
        try{
            queryResult = sqlTool.executeQuery(sql);
        } catch (SecurityException se) {
            log.warn("[TextToSql] SQL安全检查拦截，LLM可能未生成有效SQL: {}", sql.length() > 50 ? sql.substring(0,50)+"..." : sql);
            return "数据库中没有找到相关数据，请尝试换个问法（如'查看航班列表'、'查一下订单'）。";
        } catch (Exception e) {
            log.error("[TextToSql] SQL执行失败: {}", e.getMessage());
            return "查询执行失败：" + e.getMessage();
        }

        // ④将查询结果交给LLM整理成自然语言
        String formatPrompt = """
                根据以下查询结果，用简洁友好的方式回答用户的问题。如果结果为空，请如实告知。

                用户问题：%s
                查询结果：%s
                """.formatted(question, queryResult);

        return client.prompt()
                .user(formatPrompt)
                .call()
                .chatResponse()
                .getResult()
                .toString();
    }
}