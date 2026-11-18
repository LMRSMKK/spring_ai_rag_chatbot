package com.example.spring_ai_language.service;

import com.example.spring_ai_language.entity.ChatMessage;
import com.example.spring_ai_language.mapper.ChatMessageMapper;
import com.example.spring_ai_language.tools.SqlTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class TextToSqlService {
    @Autowired private ChatClient client;
    @Autowired private SqlTool sqlTool;
    @Autowired private ChatMessageMapper chatMessageMapper;

    /**
     * 执行 Text-to-SQL 问答
     *
     * @param question             用户自然语言问题
     * @param userId               用户ID
     * @param sessionId            会话ID
     * @param client               当前选用的 LLM ChatClient
     * @param requestCancelService 用于检查是否被用户取消
     * @param messageId            本次请求的唯一标识（同时作为 askId 关联 DB 记录）
     * @param model                使用的模型名称，用于落库
     * @return 最终自然语言回答；若被取消则返回空字符串
     */
    public String anwserWithSql(String question, String userId, String sessionId,
                                 ChatClient client, RequestCancelService requestCancelService,
                                 String messageId, String model) {

        // 【检查点 1】方法入口：先标记取消，再插入 pending 用户消息
        if (requestCancelService.shouldCancel(messageId)) {
            log.info("[TextToSql-取消] messageId={} 用户主动中断（入口）", messageId);
            return "";
        }

        // 预先插入 user 消息为 pending
        insertPendingUser(sessionId, userId, question, messageId);

        // ①获取数据库表结构（作为Schema上下文发给LLM）
        String schema = sqlTool.getSchema();
        log.debug("[TextToSql] 获取到表结构，长度={}", schema.length());

        // 构建会话上下文（让LLM知道如何查询历史）
        String sessionContext = (sessionId != null && !sessionId.isBlank())
                ? "当前会话ID: " + sessionId + "\n如需查询历史消息，请使用 session_id = '" + sessionId + "' 进行过滤\n"
                : "";

        // ②让LLM生成SQL
        String generatePrompt = """
                你是一位精通SQL的数据分析师。根据以下数据库表结构，将用户的自然语言问题转为可执行的 SELECT 语句。\
                【强制要求】\
                1. 输出内容必须以 SELECT 开头，只能是 SQL 语句本身\
                2. 绝对不允许有任何解释、说明、markdown格式或换行\
                3. 确保 SQL 语法正确，字段名与表结构完全一致\
                4. 不要输出分号\
                5. 日期字段使用 YYYY-MM-DD 格式\
                6. 如果问题与表数据无关，请输出一条返回空结果的 SQL 如：SELECT '' AS result WHERE 0=1\
                \
                【数据库表结构】\
                %s\
                \
                【会话上下文】\
                %s\
                \
                【用户问题】\
                %s\
                【你的输出（只有SQL，不要任何其他内容）】\
                """.formatted(schema, sessionContext, question);

        String sql;
        try {
            // 【检查点 2】LLM调用前
            if (requestCancelService.shouldCancel(messageId)) {
                log.info("[TextToSql-取消] messageId={} 用户主动中断（SQL生成前）", messageId);
                markCancelled(messageId);
                return "";
            }
            String raw = client.prompt()
                    .user(generatePrompt)
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            // 清理 Ollama/部分 LLM 自动添加的 markdown 代码块包裹：```sql\n...\n```
            sql = raw.strip().replaceAll("(?s)^```\\w*\\s*|\\s*```$", "").strip();
            log.debug("[TextToSql] 生成的SQL: {}", sql);
            // 校验 LLM 是否真正输出了 SQL，而非自然语言
            String firstWord = sql.split("\\s+")[0].toUpperCase();
            if (!firstWord.equals("SELECT")) {
                log.warn("[TextToSql] LLM未输出有效SQL，实际返回: {}", sql.length() > 80 ? sql.substring(0, 80) + "..." : sql);
                return "无法生成查询语句，请换个问法（如'查看航班列表'、'查一下订单'）。";
            }
        } catch (Exception e) {
            log.error("[TextToSql] SQL生成失败: {}", e.getMessage());
            return "抱歉，SQL 生成过程中出现异常，请联系管理员。";
        }

        // ③执行SQL（通过Tool，Tool内部做了安全校验只允许SELECT）
        String queryResult;
        try {
            // 【检查点 3】SQL执行前
            if (requestCancelService.shouldCancel(messageId)) {
                log.info("[TextToSql-取消] messageId={} 用户主动中断（SQL执行前）", messageId);
                markCancelled(messageId);
                return "";
            }
            queryResult = sqlTool.executeQuery(sql);
        } catch (SecurityException se) {
            log.warn("[TextToSql] SQL安全检查拦截，LLM可能未生成有效SQL: {}", sql.length() > 50 ? sql.substring(0, 50) + "..." : sql);
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

        try {
            // 【检查点 4】结果整理前
            if (requestCancelService.shouldCancel(messageId)) {
                log.info("[TextToSql-取消] messageId={} 用户主动中断（结果整理前）", messageId);
                markCancelled(messageId);
                return "";
            }
            String answer = client.prompt()
                    .user(formatPrompt)
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            // 成功：更新 user 行为 answered，插入 assistant 行
            saveAnswered(sessionId, userId, messageId, answer, model);
            return answer;
        } catch (Exception e) {
            log.error("[TextToSql] 结果整理失败: {}", e.getMessage());
            return "查询完成但结果整理时出错，请稍后重试。";
        }
    }

    /** 在请求开始时插入一条 pending 状态的 user 消息 */
    private void insertPendingUser(String sessionId, String userId, String question, String messageId) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setUserId(userId);
            msg.setRole("user");
            msg.setContent(question);
            msg.setAskId(messageId);
            msg.setStatus("pending");
            msg.setCostMs(0);
            chatMessageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("[插入pending用户消息失败] sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    /** 将 pending 记录标记为 cancelled */
    private void markCancelled(String messageId) {
        try {
            chatMessageMapper.markCancelledByAskId(messageId);
            log.info("[TextToSql-DB清理] messageId={} 已标记为 cancelled", messageId);
        } catch (Exception e) {
            log.warn("[TextToSql-DB清理失败] messageId={} err={}", messageId, e.getMessage());
        }
    }

    /** 用户消息完成时：更新 user 行状态，插入 assistant 行 */
    private void saveAnswered(String sessionId, String userId, String messageId, String answer, String modelName) {
        try {
            List<ChatMessage> records = chatMessageMapper.selectByAskId(messageId);
            Long userMsgId = null;
            for (ChatMessage m : records) {
                if ("user".equals(m.getRole()) && "pending".equals(m.getStatus())) {
                    userMsgId = m.getId();
                    break;
                }
            }
            if (userMsgId != null) {
                chatMessageMapper.updateContentAndStatus(userMsgId, answer, "answered");
            }
            // 插入 assistant 回答
            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setSessionId(sessionId);
            assistantMsg.setUserId(userId);
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(answer);
            assistantMsg.setModel(modelName);
            assistantMsg.setAskId(messageId);
            assistantMsg.setStatus("answered");
            chatMessageMapper.insert(assistantMsg);
        } catch (Exception e) {
            log.error("[TextToSql-写入结果失败] messageId={}", messageId, e);
        }
    }
}
