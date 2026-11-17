package com.example.spring_ai_language.util;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SessionSummary {
    private String title;
    private String sessionId;
    private LocalDateTime lastMessageTime;
}
