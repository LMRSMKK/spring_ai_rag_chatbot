package com.example.spring_ai_language.service;

import com.example.spring_ai_language.entity.AskResult;
import com.example.spring_ai_language.entity.ChatMessage;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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

    public AskResult ask(String knowledgeBaseId, String userId, String sessionId, String question,
                         ChatClient client, int topK, double temperature) {
        long startMs = System.currentTimeMillis();
        List<ChatMessage> history = chatMessageMapper.selectRecent(sessionId, 20);
        List<Message> messages = buildMessages(history, question, knowledgeBaseId, topK);
        AskResult result = new AskResult();
        ChatClientResponse clientResponse;
        try {
            clientResponse = client.prompt()
                    .messages(messages)
                    .options(ChatOptions.builder()
                            .temperature(temperature)
                            .build())
                    .call()
                    .chatClientResponse();
        } catch (Exception e) {
            log.error("[LLM调用失败] userId={}, err={}", userId, e.getMessage());
            result.setAnswer("服务暂时不可用，请稍后再试。");
            result.setCostMs(System.currentTimeMillis() - startMs);
            result.setRefCount(0);
            saveAndReturn(sessionId, userId, question, "服务暂时不可用，请稍后再试。", startMs);
            return result;
        }
        String answer = clientResponse.chatResponse().getResult().getOutput().getText();
        long costMs = System.currentTimeMillis() - startMs;
        result.setAnswer(answer);
        result.setCostMs(costMs);
        result.setRefCount(retrieveDocCount(knowledgeBaseId, question, topK));
        result.setBlocked(Boolean.TRUE.equals(clientResponse.context().get("__intercepted")));
        saveAndReturn(sessionId, userId, question, answer, costMs);
        log.info("[RAG问答] user={} kb={} cost={}ms", userId, knowledgeBaseId, costMs);
        return result;
    }

    private List<Message> buildMessages(List<ChatMessage> history, String question, String kbId, int topK) {
        List<Message> messages = new ArrayList<>();
        String context = retrieveContext(kbId, question, topK);
        messages.add(buildSystemMessages(context));
        for (ChatMessage m : history) {
            if ("user".equals(m.getRole())) {
                messages.add(new UserMessage(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                messages.add(new AssistantMessage(m.getContent()));
            }
        }
        messages.add(new UserMessage(question));
        return messages;
    }

    private String retrieveContext(String kbId, String question, int topK) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(topK)
                            .build());
            return docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.warn("[RAG检索失败] kbId={}, err={}", kbId, e.getMessage());
            return "";
        }
    }

    private Message buildSystemMessages(String context) {
        // 严格约束模型只能使用参考资料回答，禁止使用自身训练知识
        String strictPrompt = """
            你是企业知识库问答助手，以下是从知识库中检索到的相关资料片段。
            你必须严格遵守以下规则：
            1. 你的回答必须完全基于下方提供的【参考资料】，不得引用或编造资料中没有的信息。
            2. 如果参考资料中没有与用户问题相关的内容，直接回复："抱歉，资料中没有找到与该问题相关的信息。"
            3. 不要透露任何关于你自身知识或模型能力的信息。
            4. 回答简洁、有条理，直接给出结论。
            5. 在回答末尾注明资料来源文件名（如有多个，列出前三个即可）。
            """;
        if (context != null && !context.isBlank()) {
            return new SystemMessage(strictPrompt + "\n\n【参考资料】\n" + context);
        }
        return new SystemMessage(strictPrompt + "\n\n【参考资料】\n（暂无相关资料）");
    }

    private void saveAndReturn(String sessionId, String userId, String question, String answer, long costMs) {
        ChatMessage qMsg = new ChatMessage();
        qMsg.setSessionId(sessionId);
        qMsg.setUserId(userId);
        qMsg.setRole("user");
        qMsg.setContent(question);
        qMsg.setCostMs((int) costMs);
        chatMessageMapper.insert(qMsg);

        ChatMessage aMsg = new ChatMessage();
        aMsg.setSessionId(sessionId);
        aMsg.setUserId(userId);
        aMsg.setRole("assistant");
        aMsg.setContent(answer);
        aMsg.setCostMs((int) costMs);
        chatMessageMapper.insert(aMsg);
    }

    private int retrieveDocCount(String kbId, String question, int topK) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(topK)
                            .build());
            return docs.size();
        } catch (Exception e) {
            return 0;
        }
    }
}