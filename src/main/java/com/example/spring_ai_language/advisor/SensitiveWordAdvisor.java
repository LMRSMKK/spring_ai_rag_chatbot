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

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Advisor：对用户输入和模型输出做敏感词过滤（Aho-Corasick 前缀树实现）
 */
@Slf4j
@Component
public class SensitiveWordAdvisor implements BaseAdvisor, Ordered {

    private final TreeNode root = new TreeNode();

    public SensitiveWordAdvisor(
            @Value("${app.sensitive-words.words:敏感词示例1,敏感词示例2}") String words) {
        for (String w : words.split(",")) {
            insert(w.strip());
        }
    }

    /** 逐词插入前缀树 */
    private void insert(String word) {
        if (word.isEmpty()) return;
        TreeNode node = root;
        for (char c : word.toCharArray()) {
            node.children.computeIfAbsent(c, k -> new TreeNode());
            node = node.children.get(c);
        }
        node.isEnd = true;
    }

    /** 单次扫描判断文本是否包含任意敏感词，O(n) */
    private boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            TreeNode node = root;
            for (int j = i; j < text.length(); j++) {
                char c = text.charAt(j);
                node = node.children.get(c);
                if (node == null) break;
                if (node.isEnd) return true;
            }
        }
        return false;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String input = extractInput(request);
        if (containsSensitive(input)) {
            log.warn("[敏感词拦截] 用户输入命中敏感词");
            return request.mutate()
                    .context("__sensitive_blocked", true)
                    .build();
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
        if (containsSensitive(output)) {
            log.warn("[敏感词拦截] 模型输出命中敏感词");
            return response.mutate()
                    .context("__intercepted", true)
                    .chatResponse(safeBlockedResponse())
                    .build();
        }
        return response;
    }

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

    /** 前缀树节点 */
    static class TreeNode {
        Map<Character, TreeNode> children = new HashMap<>();
        boolean isEnd;
    }
}
