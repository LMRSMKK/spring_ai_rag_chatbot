package com.example.spring_ai_language.service;


import java.util.UUID;

public record TextChunk(String id,
                        String content,
                        String fileName,
                        String knowledgeBaseId,
                        String userId,
                        int index) {
    public  TextChunk{
        if(id==null||id.isEmpty()){
            id= UUID.randomUUID().toString();
        }
    }
}
