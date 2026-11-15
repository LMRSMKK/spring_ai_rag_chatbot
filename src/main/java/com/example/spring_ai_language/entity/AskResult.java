package com.example.spring_ai_language.entity;

import lombok.Data;

import java.util.List;

@Data
public class AskResult {
    private String answer;
    private long costMs;
    private int refCount;   // RAG模式下检索到的片段数；SQL模式下为0
    private boolean blocked;
    private String mode;  // "rag" / "sql" / "knowledge"
    private double relevance;  // 最高相关片段的相似度分数，0~1
    private List<String> sources; // 涉及的文档文件名列表
}
