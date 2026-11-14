package com.example.spring_ai_language.config;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class ChromaConfig {

    @Value("${spring.ai.vectorstore.chroma.collection-name}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.chroma.client.base-url:http://localhost:8000}")
    private String baseUrl;

    // Chroma v2 默认使用的租户和数据库名（与 ChromaDb Rust 实现保持一致）
    private static final String DEFAULT_TENANT = "SpringAiTenant";
    private static final String DEFAULT_DB = "SpringAiDatabase";

    /**
     * 自定义 ChromaVectorStore bean：确保 tenant / database / collection 三级结构完整后再初始化。
     * 如果 ChromaDB 未运行则抛出异常，避免应用启动后 RAG 静默失败。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnProperty(name = "spring.ai.vectorstore.chroma.collection-name")
    public ChromaVectorStore chromaVectorStore(EmbeddingModel embeddingModel) {
        ensureTenantExists();
        ensureDatabaseExists();
        ensureCollectionExists();

        String cleanBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        ChromaApi api = ChromaApi.builder().baseUrl(cleanBaseUrl).build();

        return ChromaVectorStore.builder(api, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(true)
                .build();
    }

    private void ensureTenantExists() {
        try {
            RestClient.create(baseUrl).post()
                    .uri("/api/v2/tenants")
                    .header("Content-Type", "application/json")
                    .body("{\"name\":\"" + DEFAULT_TENANT + "\"}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) return; // 已存在，忽略
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("[ChromaDB] 连接失败，请确认服务已在 http://" + baseUrl + " 运行: " + e.getMessage(), e);
        }
    }

    private void ensureDatabaseExists() {
        try {
            RestClient.create(baseUrl).post()
                    .uri("/api/v2/tenants/" + DEFAULT_TENANT + "/databases")
                    .header("Content-Type", "application/json")
                    .body("{\"name\":\"" + DEFAULT_DB + "\"}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) return; // 已存在，忽略
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("[ChromaDB] 连接失败: " + e.getMessage(), e);
        }
    }

    private void ensureCollectionExists() {
        try {
            RestClient.create(baseUrl).post()
                    .uri("/api/v2/tenants/" + DEFAULT_TENANT + "/databases/" + DEFAULT_DB + "/collections")
                    .header("Content-Type", "application/json")
                    .body("{\"name\":\"" + collectionName + "\",\"metadata\":{\"hnsw:space\":\"cosine\"}}")
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 409) return; // 已存在，忽略
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("[ChromaDB] 连接失败: " + e.getMessage(), e);
        }
    }
}
