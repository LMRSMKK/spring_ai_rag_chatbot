package com.example.spring_ai_language;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
@MapperScan("com.example.spring_ai_language.mapper")
@EnableCaching
public class SpringAiLanguageApplication {
    public static void main(String[] args) {
        Dotenv.configure().load();
        SpringApplication.run(SpringAiLanguageApplication.class, args);
    }
}
