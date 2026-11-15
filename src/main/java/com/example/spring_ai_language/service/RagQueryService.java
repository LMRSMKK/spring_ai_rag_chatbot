package com.example.spring_ai_language.service;

import com.example.spring_ai_language.entity.AskResult;
import com.example.spring_ai_language.entity.ChatMessage;
import com.example.spring_ai_language.entity.ScoredDocument;
import com.example.spring_ai_language.mapper.ChatMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RagQueryService {
    @Autowired
    private ChatClient client;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private VectorScoreService vectorScoreService;

    @Value("${app.rag.high-confidence-threshold:0.7}")
    private double HIGH_THRESHOLD;

    @Value("${app.rag.med-confidence-threshold:0.4}")
    private double MED_THRESHOLD;

    public AskResult ask(String knowledgeBaseId, String userId, String sessionId,
                         String question, ChatClient client, int topK, double temperature) {
        long startMs = System.currentTimeMillis();

        //检索 + 评分
        List<ScoredDocument> scoredDocs = vectorScoreService.scoredSearch(question, topK);
        double maxRelevance = scoredDocs.isEmpty() ? 0.0
                : scoredDocs.stream().mapToDouble(ScoredDocument::getScore).max().orElse(0.0);

        List<String> sources = scoredDocs.stream()
                .map(sd -> {
                    Map<String, Object> meta = sd.getDocument().getMetadata();
                    return (String) meta.getOrDefault("file_name", "未知文件");
                })
                .distinct().collect(Collectors.toList());

        AskResult result = new AskResult();
        result.setRelevance(maxRelevance);
        result.setSources(sources);

        //按相关性选策略
        String answer;
        if (maxRelevance >= HIGH_THRESHOLD) {
            // 高置信度 → 严格 RAG
            List<Document> docs = scoredDocs.stream()
                    .filter(sd -> sd.getScore() >= HIGH_THRESHOLD)
                    .map(ScoredDocument::getDocument).collect(Collectors.toList());
            String context = buildContext(docs);
            List<Message> messages = buildMessages(history(sessionId), question, buildStrictPrompt(context));
            answer = callLlm(client, messages, temperature);
            result.setMode("rag");
            result.setRefCount(docs.size());
        } else if (maxRelevance >= MED_THRESHOLD) {
            // 中置信度 → 混合模式
            List<Document> docs = scoredDocs.stream()
                    .filter(sd -> sd.getScore() >= MED_THRESHOLD)
                    .map(ScoredDocument::getDocument).collect(Collectors.toList());
            String context = buildContext(docs);
            List<Message> messages = buildMessages(history(sessionId), question, buildMixedPrompt(context));
            answer = callLlm(client, messages, temperature);
            result.setMode("rag_with_knowledge");
            result.setRefCount(docs.size());
        } else {
            // 低/无相关 → 模型自由回答
            List<Message> messages = new ArrayList<>();
            messages.add(buildOpenPrompt());
            for (ChatMessage m : history(sessionId)) {
                addRoleMessage(messages, m);
            }
            messages.add(new UserMessage(question));
            answer = callLlm(client, messages, temperature);
            result.setMode("knowledge");
            result.setRefCount(0);
        }
        result.setAnswer(answer);
        result.setCostMs(System.currentTimeMillis() - startMs);
        saveAndReturn(sessionId, userId, question, answer, result.getCostMs());
        log.info("[问答] user={} mode={} relevance={} refs={}",
                userId, result.getMode(), String.format("%.2f", maxRelevance), result.getRefCount());
        return result;
    }

    // ────────────────────────────────────────────────────────
    // 私有辅助方法
    // ────────────────────────────────────────────────────────
    private List<ChatMessage> history(String sessionId) {
        try { return chatMessageMapper.selectRecent(sessionId, 20); }
        catch (Exception e) { log.warn("[历史加载失败]: {}", e.getMessage()); return List.of(); }
    }

    private String buildContext(List<Document> docs) {
        return docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
    }

    private void addRoleMessage(List<Message> messages, ChatMessage m) {
        if ("user".equals(m.getRole()))      messages.add(new UserMessage(m.getContent()));
        else if ("assistant".equals(m.getRole())) messages.add(new AssistantMessage(m.getContent()));
    }

    private List<Message> buildMessages(List<ChatMessage> history, String question, Message systemMsg) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(systemMsg);
        history.forEach(m -> addRoleMessage(msgs, m));
        msgs.add(new UserMessage(question));
        return msgs;
    }

    private String callLlm(ChatClient client, List<Message> messages, double temp) {
        try {
            ChatClientResponse resp = client.prompt()
                    .messages(messages)
                    .options(ChatOptions.builder().temperature(temp).build())
                    .call().chatClientResponse();
            return resp.chatResponse().getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("[LLM调用失败]: {}", e.getMessage());
            return "服务暂时不可用，请稍后再试。";
        }
    }

    /** 严格模式：只准用资料答 */
    private Message buildStrictPrompt(String context) {
        String base = """
                你是企业知识库问答助手，必须严格遵守以下规则：
                1. 回答必须完全基于下方【参考资料】，不得编造资料中没有的信息。
                2. 若资料中找不到相关内容，回复："抱歉，资料中没有找到与该问题相关的信息。"
                3. 不要透露自身知识或模型能力信息。
                4. 回答简洁有条理，直接给结论。
                5. 末尾注明资料来源文件名（最多三个）。
                """;
        return context != null && !context.isBlank()
                ? new SystemMessage(base + "\n\n【参考资料】\n" + context)
                : new SystemMessage(base + "\n\n【参考资料】\n（暂无相关资料）");
    }

    /** 混合模式：资料为主，可结合通用知识补充 */
    private Message buildMixedPrompt(String context) {
        String base = """
                你是企业知识库问答助手。以下【参考资料】可作为参考，也可以结合自己的知识回答。
                1. 资料中有相关信息时优先使用并标注来源。
                2. 资料不全时可补充通用知识，但需区分"来自资料"和"来自通用知识"。
                3. 回答简洁有条理。
                """;
        return context != null && !context.isBlank()
                ? new SystemMessage(base + "\n\n【参考资料】\n" + context)
                : new SystemMessage(base);
    }

    /** 开放模式：无强制约束，自由回答 */
    private Message buildOpenPrompt() {
        return new SystemMessage(
                "你是一个有用的 AI 助手。请用简洁清晰的方式回答用户的问题。\n" +
                        "无需引用任何特定资料，自由发挥即可。");
    }

    private void saveAndReturn(String sessionId, String userId, String question, String answer, long costMs) {
        ChatMessage qMsg = new ChatMessage();
        qMsg.setSessionId(sessionId); qMsg.setUserId(userId); qMsg.setRole("user");
        qMsg.setContent(question); qMsg.setCostMs((int) costMs);
        chatMessageMapper.insert(qMsg);

        ChatMessage aMsg = new ChatMessage();
        aMsg.setSessionId(sessionId); aMsg.setUserId(userId); aMsg.setRole("assistant");
        aMsg.setContent(answer); aMsg.setCostMs((int) costMs);
        chatMessageMapper.insert(aMsg);
    }
}