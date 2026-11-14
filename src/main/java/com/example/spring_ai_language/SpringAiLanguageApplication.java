package com.example.spring_ai_language;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.spring_ai_language.mapper")
public class SpringAiLanguageApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiLanguageApplication.class, args);
    }

}
