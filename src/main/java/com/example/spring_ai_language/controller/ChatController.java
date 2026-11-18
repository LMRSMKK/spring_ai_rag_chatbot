package com.example.spring_ai_language.controller;

import com.example.spring_ai_language.util.Result;
import com.example.spring_ai_language.entity.AskResult;
import com.example.spring_ai_language.service.ChatService;
import com.example.spring_ai_language.service.QuestionRouter;
import com.example.spring_ai_language.service.RagQueryService;
import com.example.spring_ai_language.service.TextToSqlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {
    @Autowired
    private QuestionRouter router;
    @Autowired
    private RagQueryService ragQueryService;
    @Autowired
    private TextToSqlService textToSqlService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    @Qualifier("deepSeekChatClient")
    private org.springframework.ai.chat.client.ChatClient deepSeekClient;
    @Autowired
    @Qualifier("agnesChatClient")
    private ChatClient agnesClient;
    @Autowired
    @Qualifier("ollamaChatClient")
    private org.springframework.ai.chat.client.ChatClient ollamaClient;

    @PostMapping("/ask")
    public Result<?> ask(@RequestBody Map<String,String> body){
        String question = body.get("question");
        String userId = body.get("userId");
        String sessionId = body.get("sessionId");
        String kbId = body.get("knowledgeBaseId");
        String model = body.getOrDefault("model", "deepseek"); // 默认 DeepSeek
        int topK = body.containsKey("topK") ? Integer.parseInt(body.get("topK")) : 5;
        double temperature = body.containsKey("temp") ? Double.parseDouble(body.get("temp")) : 0.7;
        if(question.isBlank()){
            return Result.error(400,"问题不能为空");
        }
        log.info("[请求] userId={} sessionId={} kbId={} model={} question={}", userId, sessionId, kbId, model, question);

        // 根据模型选择对应的 ChatClient
        ChatClient client;
        if ("ollama".equalsIgnoreCase(model)) {
            client = ollamaClient;
        } else if ("agnes".equalsIgnoreCase(model)) {
            client = agnesClient;
        } else {
            client = deepSeekClient;
        }

        // 路由：启发式快筛命中 → 直接 SQL；否则调 LLM 二次判断，兜底走 RAG
        AskResult askResult;
        if (isLikelySqlQuestion(question)) {
            askResult = doSqlAnswer(question, userId, sessionId, client);
        } else {
            try {
                QuestionRouter.Route route = router.route(question);
                if (route == QuestionRouter.Route.SQL) {
                    askResult = doSqlAnswer(question, userId, sessionId, client);
                } else {
                    askResult = ragQueryService.ask(userId, sessionId, question, topK, temperature);
                }
            } catch (Exception e) {
                log.warn("[路由失败] question={} err={}，降级走 RAG", question, e.getMessage());
                askResult = ragQueryService.ask(userId, sessionId, question, topK, temperature);
            }
        }

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("answer",   askResult.getAnswer());
        data.put("sessionId", sessionId);
        data.put("mode",     askResult.getMode() != null ? askResult.getMode() : "");
        data.put("model",    model);
        data.put("costMs",   askResult.getCostMs());
        data.put("refCount", askResult.getRefCount());
        data.put("relevance", askResult.getRelevance());
        data.put("blocked",  askResult.isBlocked());
        if (askResult.getTokensIn()  != null) data.put("tokensIn",  askResult.getTokensIn());
        if (askResult.getTokensOut() != null) data.put("tokensOut", askResult.getTokensOut());
        return Result.success(data);
    }

    //加载对话
    @GetMapping("/sessions")
    public Result<?> getsessions(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size){
        return Result.success(chatService.listSessions(page,size));
    }
    //打开新对话
    @PostMapping("/new")
    public Result<?> newSession(){
        String sessionId= UUID.randomUUID().toString();
        return Result.success(Map.of("sessionId",sessionId));
    }
    //删除对话
    @DeleteMapping("/delete/{sessionId}")
    public Result<?> deleteSession(@PathVariable String sessionId){
        if(sessionId==null)return Result.error("没有该会话");
        return Result.success(chatService.deleteSession(sessionId));
    }
    //加载会话历史
    @GetMapping("/messages/{sessionId}")
    public Result<?> getMessage(@PathVariable String sessionId,
                                @RequestParam(defaultValue = "50") int limit){
        return Result.success(chatService.getMessage(sessionId,limit));
    }
    //分享会话
    @GetMapping("/share/{sessionId}")
    public Result<?> getShareSession(@PathVariable String sessionId){
        return Result.success(chatService.getShareSession(sessionId));
    }
    //生成分享链接
    @PostMapping("/share")
    public Result<?> createShareLink(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null) return Result.error(400, "sessionId 不能为空");
        return Result.success(chatService.createShareLink(sessionId));
    }
    //清空记忆
    @PostMapping("/clear")
    public Result<?> clearSession(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null) return Result.error(400, "sessionId 不能为空");
        return Result.success(chatService.clearSession(sessionId));
    }

    /**
     * 启发式快筛：问题包含明确的数据查询关键词时，直接走 SQL 路径
     * 避免对这类问题先做无意义的向量检索
     */
    private boolean isLikelySqlQuestion(String q) {
        if (q == null || q.isBlank()) return false;

        // 条件A：有查询/统计意图
        boolean hasQueryIntent = q.matches(".*[查查找找问问看看].*")
                || q.contains("多少") || q.contains("统计") || q.contains("总数")
                || q.contains("哪些") || q.contains("排名") || q.contains("趋势")
                || q.contains("最高") || q.contains("最低") || q.contains("平均")
                || q.contains("占比") || q.contains("汇总") || q.contains("明细");

        // 条件B：常见业务名词（根据实际数据库表字段补充）
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

    /** 统一执行 Text-to-SQL，提取为私有方法供快筛和 LLM 路由共用 */
    private AskResult doSqlAnswer(String question, String userId, String sessionId, ChatClient client) {
        long startMs = System.currentTimeMillis();
        String answer = textToSqlService.anwserWithSql(question, userId, sessionId, client);
        AskResult r = new AskResult();
        r.setAnswer(answer);
        r.setMode("sql");
        r.setCostMs(System.currentTimeMillis() - startMs);
        r.setRefCount(0);
        log.debug("[路由] question={} → SQL（启发式命中）", question);
        return r;
    }
}
