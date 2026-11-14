package com.example.spring_ai_language.entity;

import lombok.Data;

@Data
public class AskResult {
    private String answer;
    private long costMs;
    private int refCount;   // RAG模式下检索到的片段数；SQL模式下为0
    private boolean blocked;
}
