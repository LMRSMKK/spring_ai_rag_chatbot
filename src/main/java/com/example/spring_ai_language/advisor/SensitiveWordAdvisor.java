package com.example.spring_ai_language.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Advisor：对用户输入和模型输出做敏感词过滤
 */
@Slf4j
@Component
public class SensitiveWordAdvisor implements BaseAdvisor, Ordered {

    private final Set<String> sensitiveWords;

    public SensitiveWordAdvisor(
            @Value("${app.sensitive-words.words:敏感词示例1,敏感词示例2}") String words) {
        this.sensitiveWords = new HashSet<>(Arrays.asList(words.split(",")));
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String input = extractInput(request);
        for (String word : sensitiveWords) {
            if (input.contains(word.strip())) {
                log.warn("[敏感词拦截] 用户输入包含敏感词: {}", word);
                return request.mutate()
                        .context("__sensitive_blocked", true)
                        .build();
            }
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (Boolean.TRUE.equals(response.context().get("__sensitive_blocked"))) {
            return response.mutate()
                    .context("__intercepted", true)
                    .chatResponse(safeBlockedResponse())
                    .build();
        }
        String output = response.chatResponse()
                .getResult().getOutput().getText();
        for (String word : sensitiveWords) {
            if (output.contains(word.strip())) {
                log.warn("[敏感词拦截] 模型输出包含敏感词: {}", word);
                return response.mutate()
                        .context("__intercepted", true)
                        .chatResponse(safeBlockedResponse())
                        .build();
            }
        }
        return response;
    }

    /** 构造一个"内容被屏蔽"的安全 ChatResponse */
    private ChatResponse safeBlockedResponse() {
        AssistantMessage blockedMsg = new AssistantMessage("您的消息包含不适当内容，已屏蔽。");
        return new ChatResponse(List.of(new Generation(blockedMsg)));
    }

    private String extractInput(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        return instructions.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .collect(Collectors.joining("\n"));
    }

    @Override
    public int getOrder() { return 200; }

    @Override
    public String getName() { return "sensitive-word-advisor"; }
}
