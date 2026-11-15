package com.example.spring_ai_language.entity;

import lombok.Data;
import org.springframework.ai.document.Document;

@Data
public class ScoredDocument {
    private Document document;
    private double score;   // 0~1，越高越相关
}
