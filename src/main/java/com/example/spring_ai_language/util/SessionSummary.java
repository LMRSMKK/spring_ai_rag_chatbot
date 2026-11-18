package com.example.spring_ai_language.util;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SessionSummary {
    private String sessionId;
    private String title;
    private LocalDateTime lastMessageTime;
    /** 该会话中尚待回答的消息数量（status='pending'） */
    private Integer pendingCount = 0;
}
