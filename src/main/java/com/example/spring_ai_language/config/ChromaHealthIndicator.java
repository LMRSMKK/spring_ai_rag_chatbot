package com.example.spring_ai_language.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ChromaHealthIndicator implements HealthIndicator {
    @Value("${spring.ai.vectorstore.chroma.client.base-url:http://localhost:8000}")
    private String baseUrl;


    @Override
    public Health getHealth(boolean includeDetails) {
        return HealthIndicator.super.getHealth(includeDetails);
    }

    @Override
    public Health health() {
        try {
        RestClient.create(baseUrl)
                .get().uri("/api/v2/info")
                .retrieve().toBodilessEntity();
        return Health.up().build();
        }catch (Exception e){
            return Health.down()
                    .withDetail("endpoint", "/api/v2/info")
                    .withDetail("error", e.getMessage())
                    .build();

        }
    }
}
