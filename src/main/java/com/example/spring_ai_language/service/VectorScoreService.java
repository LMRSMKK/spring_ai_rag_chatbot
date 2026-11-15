package com.example.spring_ai_language.service;

import com.example.spring_ai_language.entity.ScoredDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
@Slf4j
public class VectorScoreService {
    @Value("${spring.ai.vectorstore.chroma.client.base-url:http://localhost:8000}")
    private String chromaBaseUrl;
    @Value("${spring.ai.vectorstore.chroma.collection-name}")
    private String collectionName;

    public List<ScoredDocument> scoredSearch(String query, int topK) {
        try {
            RestClient client = RestClient.create(chromaBaseUrl);
            Map<String, Object> body = new HashMap<>();
            body.put("query_texts", List.of(query));
            body.put("n_results", topK);
            body.put("include", List.of("documents", "metadatas", "distances"));

            Map<?, ?> resp = client.post()
                    .uri("/api/v1/collections/" + collectionName + "/query")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class)
                    .getBody();

            List<List<String>> allDocs = (List<List<String>>) resp.get("documents");
            List<List<Double>> allDists = (List<List<Double>>) resp.get("distances");

            List<String> docs = allDocs.get(0);
            List<Double> dists = allDists.get(0);

            List<ScoredDocument> result = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                double dist = dists.get(i);
                double score = 1.0 / (1.0 + dist);
                List<Map<String, Object>> allMetas = (List<Map<String, Object>>) ((List<?>) resp.get("metadatas")).get(0);
                Document doc = new Document(docs.get(i), allMetas.get(i));
                ScoredDocument sd = new ScoredDocument();
                sd.setDocument(doc);
                sd.setScore(score);
                result.add(sd);
            }
            return result;
        } catch (Exception e) {
            log.warn("[VectorScore] 检索失败: {}", e.getMessage());
            return List.of();
        }
    }
}