package com.example.spring_ai_language.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.RouteMatcher;

@Service
@Slf4j
public class QuestionRouter {
    @Autowired
    private ChatClient client;

    public Route route(String question){
        String prompt = """
                 请判断以下用户问题属于哪一类，只回复RAG或SQL，不要其他内容：
                 - RAG：询问软件使用方法、命令说明、操作指南、概念解释、知识问答（如Git命令、Linux命令、请假流程等）
                 - SQL：需要查数据库里具体的数值记录、统计数字、订单明细、交易记录，航班信息等（如查销售额、查某天的数据,哪个城市的航班详细信息）

                 用户问题：%s
                 """.formatted(question);
        String result=client.prompt()
                .user(prompt)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText()
                .strip();

        Route r=result.toUpperCase().contains("SQL")? Route.SQL : Route.RAG;
        log.debug("[路由决策] question={} → {}", question, r);
        return  r;
    }
    public enum Route { RAG, SQL }
}