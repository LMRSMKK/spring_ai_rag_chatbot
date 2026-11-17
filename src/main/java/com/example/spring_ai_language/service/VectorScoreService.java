package com.example.spring_ai_language.service;

import com.example.spring_ai_language.entity.ScoredDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaApi.QueryRequest;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class VectorScoreService {

    @Value("${spring.ai.vectorstore.chroma.client.base-url:http://localhost:8000}")
    private String chromaBaseUrl;

    @Value("${spring.ai.vectorstore.chroma.collection-name:rag_kb_collection}")
    private String collectionName;
    @Autowired
    private ChromaApi chromaApi;
    @Autowired
    private EmbeddingModel embeddingModel;
    private static final String TENANT = "SpringAiTenant";
    private static final String DATABASE = "SpringAiDatabase";

    private ChromaApi getChromaApi() {
        if (chromaApi == null) {
            String baseUrl = chromaBaseUrl != null && chromaBaseUrl.endsWith("/")
                    ? chromaBaseUrl.substring(0, chromaBaseUrl.length() - 1) : chromaBaseUrl;
            this.chromaApi = ChromaApi.builder().baseUrl(baseUrl).build();
        }
        return chromaApi;
    }

    public List<ScoredDocument> scoredSearch(String query, int topK) {
        try {
            float[] embedding = embeddingModel.embed(query);
            String colId = getChromaApi().getCollection(TENANT, DATABASE, collectionName).id();

            QueryRequest req = new QueryRequest(
                    List.of(embedding),
                    topK,
                    null,
                    List.of(QueryRequest.Include.DOCUMENTS,
                            QueryRequest.Include.METADATAS,
                            QueryRequest.Include.DISTANCES)
            );

            var resp = getChromaApi().queryCollection(TENANT, DATABASE, colId, req);

            List<String> docs      = resp.documents().get(0);
            List<Double> dists     = resp.distances().get(0);
            List<java.util.Map<String, Object>> metas = resp.metadata().get(0);

            List<ScoredDocument> result = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                double dist  = dists.get(i);
                double score = 1.0 / (1.0 + dist);
                Document doc = new Document(docs.get(i), metas.get(i));
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
