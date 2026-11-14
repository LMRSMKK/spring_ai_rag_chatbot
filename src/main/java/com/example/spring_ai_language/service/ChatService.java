package com.example.spring_ai_language.service;

import com.example.spring_ai_language.Uitl.Result;
import com.example.spring_ai_language.entity.ChatMessage;
import com.example.spring_ai_language.Uitl.SessionSummary;
import com.example.spring_ai_language.mapper.ChatMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    private static final int SHARE_EXPIRE_DAYS = 7;

    // 会话列表分页加载
    public Object listSessions(int page, int size) {
        int offset = page * size;
        List<ChatMessage> recent = chatMessageMapper.selectSessionList(offset, size);
        if (recent == null || recent.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 sessionId 去重：保留最新一条（ORDER BY create_time DESC 已排好序）
        Map<String, ChatMessage> latestMap = new LinkedHashMap<>();
        for (ChatMessage m : recent) {
            latestMap.putIfAbsent(m.getSessionId(), m);
        }

        // 构建摘要
        List<SessionSummary> summaries = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (ChatMessage m : latestMap.values()) {
            SessionSummary s = new SessionSummary();
            s.setSessionId(m.getSessionId());
            s.setTitle(truncate(m.getContent(), 30));
            s.setLastMessageTime(m.getCreateTime());
            summaries.add(s);
        }

        // 按日期分组：今天 / 昨天 / 其他
        Map<String, List<SessionSummary>> group = new HashMap<>();
        for (SessionSummary s : summaries) {
            String label;
            LocalDate msgDate = s.getLastMessageTime().toLocalDate();
            if (msgDate.isEqual(today)) {
                label = "\u4eca\u5929";   // "今天"
            } else if (msgDate.isEqual(today.minusDays(1))) {
                label = "\u6628\u5929";   // "昨天"
            } else {
                label = "\u6700\u8fd17\u5929"; // "最近7天"
            }
            group.computeIfAbsent(label, k -> new ArrayList<>()).add(s);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : List.of("\u4eca\u5929", "\u6628\u5929", "\u6700\u8fd17\u5929")) {
            List<SessionSummary> items = group.getOrDefault(key, Collections.emptyList());
            if (!items.isEmpty()) {
                Map<String, Object> g = new HashMap<>();
                g.put("label", key);
                g.put("items", items);
                result.add(g);
            }
        }
        return result;
    }

    //删除对话
    public Object deleteSession(String sessionId) {
        int deleted = chatMessageMapper.deleteBySessionId(sessionId);
        return Map.of("deletedRows", deleted);
    }

    //加载历史对话
    public Object getMessage(String sessionId, int limit) {
        List<ChatMessage> messages = chatMessageMapper.selectAllBySession(sessionId, limit);
        List<Map<String, Object>> result = messages.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            map.put("time", m.getCreateTime() != null ? m.getCreateTime().toString() : "");
            map.put("costMs", m.getCostMs());
            return map;
        }).collect(Collectors.toList());
        return result;
    }

    //获取分享链接详情（通过 shareToken 查消息列表）
    public Object getShareSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Result.error(400, "sessionId不能为空");
        }
        String shareKey = "share:" + sessionId;
        Object val = redisTemplate.opsForValue().get(shareKey);
        String storedSessionId;
        try {
            storedSessionId = val != null ? val.toString() : null;
        } catch (Exception e) {
            log.error("[getShareSession Redis error] sessionId={}, err={}", sessionId, e.getMessage());
            return Result.error(500, "服务内部错误");
        }
        if (storedSessionId == null) {
            return Result.error(404, "分享链接已过期或无效");
        }
        List<ChatMessage> messages = chatMessageMapper.selectAllBySession(storedSessionId, 100);
        List<Map<String, Object>> result = messages.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("role", m.getRole());
            map.put("content", m.getContent());
            map.put("time", m.getCreateTime() != null ? m.getCreateTime().toString() : "");
            return map;
        }).collect(Collectors.toList());
        return Result.success(result);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private String getHost() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            return attrs.getRequest().getHeader("Host");
        }
        return "localhost:8080";
    }

    //生成分享链接（存入Redis）
    public Object createShareLink(String sessionId) {
        String shareToken = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String shareKey = "share:" + shareToken;
        redisTemplate.opsForValue().set(shareKey, sessionId, SHARE_EXPIRE_DAYS, TimeUnit.DAYS);
        String url = "https://" + getHost() + "/share?s=" + shareToken;
        return Result.success(Map.of("url", url));
    }

    //清空会话记忆
    public Object clearSession(String sessionId) {
        int deleted = chatMessageMapper.deleteBySessionId(sessionId);
        return Result.success(Map.of("deletedRows", deleted));
    }
}
