package com.example.spring_ai_language.config;

import com.example.spring_ai_language.advisor.LoggingAdvisor;
import com.example.spring_ai_language.advisor.SensitiveWordAdvisor;
import com.example.spring_ai_language.tools.SqlTool;
import org.apache.tika.Tika;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

    /**
     * DeepSeek 主聊天客户端（@Primary 确保其他 Bean 注入时有默认值）
     */
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

    /**
     * Ollama 备用聊天客户端
     */
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
    public Tika tika() {
        return new Tika();
    }
}
