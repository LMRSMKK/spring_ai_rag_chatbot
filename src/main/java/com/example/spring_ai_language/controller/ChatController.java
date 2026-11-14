package com.example.spring_ai_language.controller;

import com.example.spring_ai_language.Uitl.Result;
import com.example.spring_ai_language.entity.AskResult;
import com.example.spring_ai_language.entity.ChatMessage;
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

import java.util.List;
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
    @Qualifier("ollamaChatClient")
    private org.springframework.ai.chat.client.ChatClient ollamaClient;

    @PostMapping("/ask")
    public Map<String,Object> ask(@RequestBody Map<String,String> body){
        String question = body.get("question");
        String userId = body.get("userId");
        String sessionId = body.get("sessionId");
        String kbId = body.get("knowledgeBaseId");
        String model = body.getOrDefault("model", "deepseek"); // 默认 DeepSeek
        int topK = body.containsKey("topK") ? Integer.parseInt(body.get("topK")) : 5;
        double temperature = body.containsKey("temp") ? Double.parseDouble(body.get("temp")) : 0.7;
        if(question.isBlank()){
            return Map.of("code", 400, "message", "问题不能为空");
        }
        log.info("[请求] userId={} sessionId={} kbId={} model={} question={}", userId, sessionId, kbId, model, question);

        // 根据模型选择对应的 ChatClient
        ChatClient client = "ollama".equalsIgnoreCase(model)
                ? ollamaClient : deepSeekClient;

        QuestionRouter.Route route = router.route(question);
        AskResult askResult;
        if(route == QuestionRouter.Route.SQL){
            String sqlAnswer = textToSqlService.anwserWithSql(question, userId, client);
            askResult = new AskResult();
            askResult.setAnswer(sqlAnswer);
            askResult.setCostMs(0);
            askResult.setRefCount(0);
        } else {
            askResult = ragQueryService.ask(kbId, userId, sessionId, question, client,topK, temperature);
        }
        return Map.of("code", 200, "message", "success",
                "data", Map.of(
                        "answer",   askResult.getAnswer(),
                        "sessionId",sessionId,
                        "mode",     route.name(),
                        "model",    model,
                        "costMs",     askResult.getCostMs(),
                        "refCount",   askResult.getRefCount(),
                        "blocked",    askResult.isBlocked()
                ));
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
}
