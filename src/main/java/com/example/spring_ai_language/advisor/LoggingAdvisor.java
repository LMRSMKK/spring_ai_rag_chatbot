package com.example.spring_ai_language.advisor;

import com.example.spring_ai_language.entity.LogOperation;
import com.example.spring_ai_language.mapper.LogOperationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LoggingAdvisor implements BaseAdvisor, Ordered {

    @Autowired
    private LogOperationMapper logOperationMapper;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 从原始请求的 Prompt 中提取最后一条用户消息
        List<Message> messages = request.prompt().getInstructions();
        String userInput = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .collect(Collectors.joining("\n"));
        if (userInput.length() > 200) {
            userInput = userInput.substring(0, 200) + "...";
        }

        Map<String, Object> ctx = new HashMap<>(request.context());
        ctx.put("__logging_start_ms", System.currentTimeMillis());
        ctx.put("__logging_user_input", userInput);
        // 传递 intercepted 标记到 request attribute，供 Controller 读取
        if (Boolean.TRUE.equals(request.context().get("__sensitive_blocked"))) {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                attrs.getRequest().setAttribute("__sensitive_blocked", true);
            }
        }
        return request.mutate().context(ctx).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        long startMs = (long) response.context().getOrDefault(
                "__logging_start_ms", System.currentTimeMillis());
        long costMs = System.currentTimeMillis() - startMs;

        String userId = extractUserId();
        String userInput = (String) response.context().getOrDefault(
                "__logging_user_input", "");

        String answerText = response.chatResponse()
                .getResult().getOutput().getText();
        if (answerText.length() > 500) {
            answerText = answerText.substring(0, 500) + "...";
        }

        try {
            LogOperation logEntry = new LogOperation();
            logEntry.setUserId(userId);
            logEntry.setOperation("CHAT");
            logEntry.setRequest(userInput);
            logEntry.setResponse(answerText);
            logEntry.setCostMs((int) costMs);
            logEntry.setSuccess(true);
            logOperationMapper.insert(logEntry);
        } catch (Exception e) {
            log.warn("[日志写入失败]", e);
        }
        return response;
    }

    private String extractUserId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String uid = attrs.getRequest().getHeader("X-User-Id");
            if (uid != null && !uid.isBlank()) return uid;
        }
        return "anonymous";
    }

    @Override
    public int getOrder() { return 100; }

    @Override
    public String getName() { return "logging-advisor"; }
}
