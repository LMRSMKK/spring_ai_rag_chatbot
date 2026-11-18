package com.example.spring_ai_language.controller;

import com.example.spring_ai_language.entity.AskResult;
import com.example.spring_ai_language.service.*;
import com.example.spring_ai_language.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {
    @Autowired private QuestionRouter router;
    @Autowired private RagQueryService ragQueryService;
    @Autowired private TextToSqlService textToSqlService;
    @Autowired private ChatService chatService;
    @Autowired private RequestCancelService requestCancelService;
    @Autowired @Qualifier("deepSeekChatClient")
    private ChatClient deepSeekClient;
    @Autowired @Qualifier("agnesChatClient")
    private ChatClient agnesClient;
    @Autowired @Qualifier("ollamaChatClient")
    private ChatClient ollamaClient;

    // ─────────────────────────────────────────────
    // 公共接口
    // ─────────────────────────────────────────────

    /** 发送问答请求（含取消支持） */
    @PostMapping("/ask")
    public Result<?> ask(@RequestBody Map<String, String> body) {
        String question   = body.get("question");
        String userId     = body.get("userId");
        String sessionId  = body.get("sessionId");
        String kbId       = body.get("knowledgeBaseId");
        String model      = body.getOrDefault("model", "deepseek");
        int    topK       = body.containsKey("topK")     ? Integer.parseInt(body.get("topK"))     : 5;
        double temperature= body.containsKey("temp")     ? Double.parseDouble(body.get("temp"))     : 0.7;

        if (question.isBlank()) return Result.error(400, "问题不能为空");

        log.info("[请求] userId={} sessionId={} kbId={} model={} question={}", userId, sessionId, kbId, model, question);

        // ① 先生成 messageId，再注册取消服务，再执行处理 → finally 统一清理
        String messageId = UUID.randomUUID().toString();
        AskResult askResult;
        try {
            requestCancelService.register(messageId);

            // 根据模型选择对应的 ChatClient
            ChatClient client = resolveClient(model);

            // 路由：启发式快筛命中 → 直接 SQL；否则调 LLM 二次判断，兜底走 RAG
            if (isLikelySqlQuestion(question)) {
                askResult = doSqlAnswer(question, userId, sessionId, client, model, messageId);
            } else {
                try {
                    QuestionRouter.Route route = router.route(question, requestCancelService, messageId);
                    if (route == QuestionRouter.Route.SQL) {
                        askResult = doSqlAnswer(question, userId, sessionId, client, model, messageId);
                    } else {
                        askResult = ragQueryService.ask(userId, sessionId, question, topK, temperature, requestCancelService, messageId, model);
                    }
                } catch (Exception e) {
                    log.warn("[路由失败] question={} err={}，降级走 RAG", question, e.getMessage());
                    askResult = ragQueryService.ask(userId, sessionId, question, topK, temperature, requestCancelService, messageId, model);
                }
            }

            // ② 组装响应（始终包含 messageId 和 cancelled 标志）
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("answer",       askResult.getAnswer()  != null ? askResult.getAnswer()  : "");
            data.put("sessionId",    sessionId);
            data.put("mode",         askResult.getMode()    != null ? askResult.getMode()    : "");
            data.put("model",        model);
            data.put("costMs",       askResult.getCostMs());
            data.put("refCount",     askResult.getRefCount());
            data.put("relevance",    askResult.getRelevance());
            data.put("blocked",      askResult.isBlocked());
            data.put("cancelled",    askResult.isCancelled());
            data.put("messageId",    messageId);
            if (askResult.getTokensIn()  != null) data.put("tokensIn",  askResult.getTokensIn());
            if (askResult.getTokensOut() != null) data.put("tokensOut", askResult.getTokensOut());
            return Result.success(data);

        } catch (Exception e) {
            log.error("[问答异常] messageId={} err={}", messageId, e.getMessage(), e);
            return Result.error(500, "服务异常：" + e.getMessage());
        } finally {
            // 无论成功/异常/取消，均在此处清理 cancelFlag，防止内存泄漏
            requestCancelService.remove(messageId);
        }
    }

    /** 用户主动取消正在进行的问答 */
    @DeleteMapping("/cancel/{messageId}")
    public Result<?> cancelChat(@PathVariable String messageId) {
        boolean ok = requestCancelService.cancel(messageId);
        log.info("[取消请求] messageId={} cancelled={}", messageId, ok);
        return Result.success(Map.of("cancelled", ok));
    }

    // ─────────────────────────────────────────────
    // 内部辅助方法
    // ─────────────────────────────────────────────

    private ChatClient resolveClient(String model) {
        if ("ollama".equalsIgnoreCase(model))      return ollamaClient;
        if ("agnes".equalsIgnoreCase(model))        return agnesClient;
        return deepSeekClient;
    }

    /** 统一执行 Text-to-SQL，提取为私有方法供快筛和 LLM 路由共用 */
    private AskResult doSqlAnswer(String question, String userId, String sessionId,
                                    ChatClient client, String model, String messageId) {
        long startMs = System.currentTimeMillis();
        // anwserWithSql 内部负责：插入 pending → 检查取消 → SQL生成/执行 → 写入结果
        String answer = textToSqlService.anwserWithSql(question, userId, sessionId,
                client, requestCancelService, messageId, model);
        boolean cancelled = (answer == null || answer.isBlank());
        AskResult r = AskResult.builder()
                .answer(answer != null ? answer : "")
                .mode(cancelled ? "cancelled" : "sql")
                .costMs(System.currentTimeMillis() - startMs)
                .refCount(0)
                .cancelled(cancelled)
                .build();
        log.debug("[路由] question={} → SQL（启发式命中）", question);
        return r;
    }

    /** 启发式快筛：问题包含明确的数据查询关键词时，直接走 SQL 路径 */
    private boolean isLikelySqlQuestion(String q) {
        if (q == null || q.isBlank()) return false;
        boolean hasQueryIntent = q.matches(".*[查查找找问问看看].*")
                || q.contains("多少") || q.contains("统计") || q.contains("总数")
                || q.contains("哪些") || q.contains("排名") || q.contains("趋势")
                || q.contains("最高") || q.contains("最低") || q.contains("平均")
                || q.contains("占比") || q.contains("汇总") || q.contains("明细");
        boolean hasBizKeyword = q.contains("航班") || q.contains("机票")
                || q.contains("员工") || q.contains("部门")
                || q.contains("订单") || q.contains("销售额") || q.contains("销售")
                || q.contains("库存") || q.contains("工资") || q.contains("报销")
                || q.contains("请假") || q.contains("考勤") || q.contains("业绩")
                || q.contains("客户") || q.contains("合同") || q.contains("项目")
                || q.contains("记录") || q.contains("数据")
                || q.contains("人数") || q.contains("金额")
                || q.contains("分数") || q.contains("成绩") || q.contains("课程");
        return hasQueryIntent && hasBizKeyword;
    }

    // ─────────────────────────────────────────────
    // 会话管理接口
    // ─────────────────────────────────────────────

    @GetMapping("/sessions")
    public Result<?> getsessions(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return Result.success(chatService.listSessions(page, size));
    }

    @PostMapping("/new")
    public Result<?> newSession() {
        String sessionId = UUID.randomUUID().toString();
        return Result.success(Map.of("sessionId", sessionId));
    }

    @DeleteMapping("/delete/{sessionId}")
    public Result<?> deleteSession(@PathVariable String sessionId) {
        if (sessionId == null) return Result.error("没有该会话");
        return Result.success(chatService.deleteSession(sessionId));
    }

    @GetMapping("/messages/{sessionId}")
    public Result<?> getMessage(@PathVariable String sessionId,
                                @RequestParam(defaultValue = "50") int limit) {
        return Result.success(chatService.getMessage(sessionId, limit));
    }

    @GetMapping("/share/{sessionId}")
    public Result<?> getShareSession(@PathVariable String sessionId) {
        return Result.success(chatService.getShareSession(sessionId));
    }

    @PostMapping("/share")
    public Result<?> createShareLink(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null) return Result.error(400, "sessionId 不能为空");
        return Result.success(chatService.createShareLink(sessionId));
    }

    @PostMapping("/clear")
    public Result<?> clearSession(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null) return Result.error(400, "sessionId 不能为空");
        return Result.success(chatService.clearSession(sessionId));
    }
}
