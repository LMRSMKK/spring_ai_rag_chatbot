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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RagQueryService {

    // ────────────────────────────────────────────
    // 依赖注入
    // ────────────────────────────────────────────
    @Autowired private ChatClient client;
    @Autowired private ChatMessageMapper chatMessageMapper;
    @Autowired private VectorScoreService vectorScoreService;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private ObjectMapper objectMapper;

    // ────────────────────────────────────────────
    // 配置参数
    // ────────────────────────────────────────────
    @Value("${app.rag.high-confidence-threshold:0.7}")
    private double HIGH_THRESHOLD;

    @Value("${app.rag.med-confidence-threshold:0.4}")
    private double MED_THRESHOLD;

    /** 超过此轮数（每轮2条消息）时触发摘要压缩 */
    private static final int SUMMARIZE_THRESHOLD = 10;
    /** 保留给LLM的最近原始消息条数 = 阈值 × 2 */
    private static final int KEEP_RAW_COUNT = SUMMARIZE_THRESHOLD * 2;

    // ════════════════════════════════════════════
    // 公开入口
    // ════════════════════════════════════════════

    /**
     * RAG问答主入口：检索 → 评分 → 按置信度选策略 → 调用LLM → 落库
     *
     * @param requestCancelService 用于检查用户是否主动取消了本次请求
     * @param messageId            本次请求的唯一标识，用于取消追踪
     */
    public AskResult ask(String userId, String sessionId,
                         String question, int topK, double temperature,
                         RequestCancelService requestCancelService, String messageId) {

        // 【检查点 1】方法入口
        if (requestCancelService.shouldCancel(messageId)) {
            log.info("[RAG-取消] messageId={} 用户主动中断（入口）", messageId);
            return buildCancelledResult();
        }

        long startMs = System.currentTimeMillis();

        // ① 向量检索 + 相关性评分
        List<ScoredDocument> scoredDocs = vectorScoreService.scoredSearch(question, topK);

        // 【检查点 2】向量检索完成后
        if (requestCancelService.shouldCancel(messageId)) {
            log.info("[RAG-取消] messageId={} 用户主动中断（向量检索后）", messageId);
            return buildCancelledResult();
        }

        double maxRelevance = scoredDocs.isEmpty() ? 0.0
                : scoredDocs.stream().mapToDouble(ScoredDocument::getScore).max().orElse(0.0);
        List<String> sources = extractSources(scoredDocs);

        AskResult result = AskResult.builder()
                .relevance(maxRelevance)
                .sources(sources)
                .build();

        // ② 按相关性选择回答策略，调用LLM
        String answer;
        LlmResult llmResult;
        if (maxRelevance >= HIGH_THRESHOLD) {
            List<Document> docs = scoredDocs.stream()
                    .filter(sd -> sd.getScore() >= HIGH_THRESHOLD)
                    .map(ScoredDocument::getDocument)
                    .collect(Collectors.toList());
            llmResult = askWithLlm(history(sessionId), question,
                    buildStrictPrompt(buildContext(docs)), temperature,
                    requestCancelService, messageId);
            answer = llmResult.text;
            result.setMode("rag");
            result.setRefCount(docs.size());
        } else if (maxRelevance >= MED_THRESHOLD) {
            List<Document> docs = scoredDocs.stream()
                    .filter(sd -> sd.getScore() >= MED_THRESHOLD)
                    .map(ScoredDocument::getDocument)
                    .collect(Collectors.toList());
            llmResult = askWithLlm(history(sessionId), question,
                    buildMixedPrompt(buildContext(docs)), temperature,
                    requestCancelService, messageId);
            answer = llmResult.text;
            result.setMode("rag_with_knowledge");
            result.setRefCount(docs.size());
        } else {
            llmResult = askOpenMode(history(sessionId), question, temperature,
                    requestCancelService, messageId);
            answer = llmResult.text;
            result.setMode("knowledge");
            result.setRefCount(0);
        }

        // 【检查点 3】LLM 调用完成后、落库前
        if (requestCancelService.shouldCancel(messageId)) {
            log.info("[RAG-取消] messageId={} 用户主动中断（LLM返回后）", messageId);
            return buildCancelledResult();
        }

        // ③ 写入DB并返回
        result.setAnswer(answer);
        result.setCostMs(System.currentTimeMillis() - startMs);
        result.setTokensIn(llmResult.tokensIn);
        result.setTokensOut(llmResult.tokensOut);
        saveAndReturn(sessionId, userId, question, answer, result.getCostMs(),
                llmResult.tokensIn, llmResult.tokensOut);
        log.debug("[问答] user={} mode={} relevance={} refs={}",
                userId, result.getMode(), String.format("%.2f", maxRelevance), result.getRefCount());
        return result;
    }

    /** 构造被取消时的 AskResult */
    private AskResult buildCancelledResult() {
        return AskResult.builder()
                .cancelled(true)
                .answer("")
                .mode("cancelled")
                .costMs(0)
                .build();
    }

    // ════════════════════════════════════════════
    // 历史记忆管理（含按需摘要压缩）
    // ════════════════════════════════════════════

    private List<ChatMessage> history(String sessionId) {
        try {
            String cacheKey = "session:history:" + sessionId;
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                return objectMapper.readValue(json, new TypeReference<List<ChatMessage>>() {});
            }
            List<ChatMessage> all = chatMessageMapper.selectAllBySession(sessionId, 9999);
            if (all == null || all.isEmpty()) {
                return List.of();
            }
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(all), 30, TimeUnit.MINUTES);
            if (all.size() >= SUMMARIZE_THRESHOLD * 2) {
                compressOldMessages(sessionId, all);
            }
            int fromIndex = Math.max(0, all.size() - KEEP_RAW_COUNT);
            return new ArrayList<>(all.subList(fromIndex, all.size()));
        } catch (Exception e) {
            log.warn("[历史加载失败] sessionId={}, err={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private void compressOldMessages(String sessionId, List<ChatMessage> all) {
        int keepFromIndex = Math.max(0, all.size() - KEEP_RAW_COUNT);
        List<ChatMessage> oldMessages = all.subList(0, keepFromIndex);
        if (oldMessages.isEmpty()) return;
        String chatContent = formatForSummary(oldMessages);
        if (chatContent.isBlank()) return;
        String summary = generateSummary(chatContent);
        if (summary == null) {
            log.warn("[摘要生成失败] sessionId={}, 跳过压缩", sessionId);
            return;
        }
        insertSummaryRecord(sessionId, summary);
        log.info("[记忆压缩] sessionId={} 旧消息{}条→已归档为摘要", sessionId, oldMessages.size());
    }

    private String formatForSummary(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if ("user".equals(m.getRole()))       sb.append("用户：").append(m.getContent()).append("\n");
            else if ("assistant".equals(m.getRole())) sb.append("助手：").append(m.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private String generateSummary(String chatContent) {
        try {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("请将以下对话历史总结为一句话概述，不超过150字。只需输出总结内容，不需要其他解释。"));
            msgs.add(new UserMessage("以下是需要总结的对话历史：\n" + chatContent));
            ChatClientResponse resp = client.prompt()
                    .messages(msgs)
                    .options(ChatOptions.builder().temperature(0.3).build())
                    .call().chatClientResponse();
            return resp.chatResponse().getResult().getOutput().getText().trim();
        } catch (Exception e) {
            log.error("[摘要LLM调用失败]", e);
            return null;
        }
    }

    private void insertSummaryRecord(String sessionId, String summary) {
        try {
            ChatMessage summaryMsg = new ChatMessage();
            summaryMsg.setSessionId(sessionId);
            summaryMsg.setUserId("system");
            summaryMsg.setRole("summary");
            summaryMsg.setContent("[对话摘要]\n" + summary);
            summaryMsg.setCostMs(0);
            chatMessageMapper.insert(summaryMsg);
        } catch (Exception e) {
            log.error("[写入摘要失败] sessionId={}", sessionId, e);
        }
    }

    // ════════════════════════════════════════════
    // LLM调用封装（含取消检查）
    // ════════════════════════════════════════════

    /** 带系统提示的通用LLM调用，返回文本+token用量 */
    private LlmResult askWithLlm(List<ChatMessage> history, String question,
                                   Message systemMsg, double temp,
                                   RequestCancelService rcs, String messageId) {
        if (rcs.shouldCancel(messageId)) return new LlmResult("", null, null);
        List<Message> messages = buildMessages(history, question, systemMsg);
        return callLlm(client, messages, temp, rcs, messageId);
    }

    /** 开放模式调用：无参考资料约束，仅保留历史记录+当前问题 */
    private LlmResult askOpenMode(List<ChatMessage> history, String question, double temp,
                                   RequestCancelService rcs, String messageId) {
        if (rcs.shouldCancel(messageId)) return new LlmResult("", null, null);
        List<Message> messages = new ArrayList<>();
        messages.add(buildOpenPrompt());
        history.forEach(m -> addRoleMessage(messages, m));
        messages.add(new UserMessage(question));
        return callLlm(client, messages, temp, rcs, messageId);
    }

    /** 统一LLM调用入口，同时提取prompt/completion token数量。 */
    private LlmResult callLlm(ChatClient cli, List<Message> messages, double temp,
                               RequestCancelService rcs, String messageId) {
        try {
            ChatClientResponse resp = cli.prompt()
                    .messages(messages)
                    .options(ChatOptions.builder().temperature(temp).build())
                    .call().chatClientResponse();
            var chatResp = resp.chatResponse();
            var usage = chatResp.getMetadata().getUsage();

            Integer promptTokens  = null;
            Integer completionTok = null;
            if (usage != null && usage.getPromptTokens() != null) {
                promptTokens  = usage.getPromptTokens();
                completionTok = usage.getCompletionTokens();
            } else {
                String inputText = messages.stream()
                        .filter(m -> m instanceof UserMessage || m instanceof SystemMessage)
                        .map(m -> m instanceof UserMessage ? ((UserMessage) m).getText()
                                 : ((SystemMessage) m).getText())
                        .collect(Collectors.joining("\n"));
                String outputText = chatResp.getResult().getOutput().getText();
                promptTokens  = estimateTokens(inputText);
                completionTok = estimateTokens(outputText);
                log.debug("[Token估算] prompt={} completion={} (fallback)", promptTokens, completionTok);
            }
            return new LlmResult(chatResp.getResult().getOutput().getText(), promptTokens, completionTok);
        } catch (Exception e) {
            log.error("[LLM调用失败]: {}", e.getMessage());
            return new LlmResult("服务暂时不可用，请稍后再试。", null, null);
        }
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        long cn = 0, en = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') cn++;
            else if (Character.isLetter(c)) en++;
            else other++;
        }
        return (int) Math.ceil(cn + (double) en / 4 + other);
    }

    // ════════════════════════════════════════════
    // 消息构建辅助
    // ════════════════════════════════════════════

    private List<Message> buildMessages(List<ChatMessage> history, String question, Message systemMsg) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(systemMsg);
        history.forEach(m -> addRoleMessage(msgs, m));
        msgs.add(new UserMessage(question));
        return msgs;
    }

    private void addRoleMessage(List<Message> messages, ChatMessage m) {
        if ("summary".equals(m.getRole())) {
            messages.add(new SystemMessage(m.getContent()));
        } else if ("user".equals(m.getRole())) {
            messages.add(new UserMessage(m.getContent()));
        } else if ("assistant".equals(m.getRole())) {
            messages.add(new AssistantMessage(m.getContent()));
        }
    }

    // ════════════════════════════════════════════
    // Prompt模板
    // ════════════════════════════════════════════

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

    private Message buildOpenPrompt() {
        return new SystemMessage(
                "你是一个有用的 AI 助手。请用简洁清晰的方式回答用户的问题。\n" +
                "无需引用任何特定资料，自由发挥即可。");
    }

    // ════════════════════════════════════════════
    // 数据入库 & 工具方法
    // ════════════════════════════════════════════

    private void saveAndReturn(String sessionId, String userId, String question, String answer,
                                long costMs, Integer tokensIn, Integer tokensOut) {
        insertMsg(sessionId, userId, "user", question, costMs, tokensIn, null);
        insertMsg(sessionId, userId, "assistant", answer, costMs, null, tokensOut);
        try {
            redisTemplate.delete("session:history:" + sessionId);
        } catch (Exception e) {
            log.warn("[缓存失效失败] sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    private void insertMsg(String sessionId, String userId, String role, String content,
                           long costMs, Integer tokensIn, Integer tokensOut) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setUserId(userId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCostMs((int) costMs);
        msg.setTokensIn(tokensIn);
        msg.setTokensOut(tokensOut);
        chatMessageMapper.insert(msg);
    }

    private List<String> extractSources(List<ScoredDocument> scoredDocs) {
        return scoredDocs.stream()
                .map(sd -> (String) sd.getDocument().getMetadata().getOrDefault("file_name", "未知文件"))
                .distinct()
                .collect(Collectors.toList());
    }

    private String buildContext(List<Document> docs) {
        return docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
    }

    private static class LlmResult {
        final String text;
        final Integer tokensIn;
        final Integer tokensOut;

        LlmResult(String text, Integer tokensIn, Integer tokensOut) {
            this.text = text;
            this.tokensIn = tokensIn;
            this.tokensOut = tokensOut;
        }
    }
}
