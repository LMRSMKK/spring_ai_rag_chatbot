package com.example.spring_ai_language.config;

import com.example.spring_ai_language.advisor.LoggingAdvisor;
import com.example.spring_ai_language.advisor.SensitiveWordAdvisor;
import com.example.spring_ai_language.tools.SqlTool;
import org.apache.tika.Tika;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class AiConfig {

    @Bean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }

    @Bean
    @Primary
    public ChatClient deepSeekChatClient(
            @Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
            LoggingAdvisor loggingAdvisor,
            SensitiveWordAdvisor sensitiveWordAdvisor,
            SqlTool sqlTool) {
        return ChatClient.builder(deepSeekChatModel)
                .defaultAdvisors(loggingAdvisor, sensitiveWordAdvisor)
                .defaultTools(sqlTool)
                .build();
    }

    @Bean
    public ChatClient ollamaChatClient(
            @Qualifier("ollamaChatModel") ChatModel ollamaChatModel,
            LoggingAdvisor loggingAdvisor,
            SensitiveWordAdvisor sensitiveWordAdvisor,
            SqlTool sqlTool) {
        return ChatClient.builder(ollamaChatModel)
                .defaultAdvisors(loggingAdvisor, sensitiveWordAdvisor)
                .defaultTools(sqlTool)
                .build();
    }

    @Bean
    @Qualifier("agnesChatModel")
    public ChatModel agnesChatModel(
            @Value("${spring.ai.agnes.chat.base-url}") String baseUrl,
            @Value("${spring.ai.agnes.chat.api-key}") String apiKey,
            @Value("${spring.ai.agnes.chat.model}") String modelName,
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ObservationRegistry observationRegistry) {
        return new OpenAiChatModel(
                OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .build(),
                OpenAiChatOptions.builder()
                        .model(modelName)
                        .build(),
                toolCallingManager,
                retryTemplate,
                observationRegistry
        );
    }

    @Bean
    public ChatClient agnesChatClient(
            @Qualifier("agnesChatModel") ChatModel agnesChatModel,
            LoggingAdvisor loggingAdvisor,
            SensitiveWordAdvisor sensitiveWordAdvisor,
            SqlTool sqlTool) {
        return ChatClient.builder(agnesChatModel)
                .defaultAdvisors(loggingAdvisor, sensitiveWordAdvisor)
                .defaultTools(sqlTool)
                .build();
    }

    @Bean
    public Tika tika() {
        return new Tika();
    }
}
