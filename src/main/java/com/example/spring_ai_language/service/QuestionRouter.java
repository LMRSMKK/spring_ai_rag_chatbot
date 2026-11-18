package com.example.spring_ai_language.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 问题路由：判断用户问题是 RAG 还是 SQL 类型。
 * 支持取消检查，在 LLM 调用完成后立即判断是否需要中断。
 */
@Service
@Slf4j
public class QuestionRouter {
    @Autowired private ChatClient client;

    /**
     * 路由决策。
     *
     * @param question           用户问题
     * @param requestCancelService 取消服务（可为 null，兼容旧调用方）
     * @param messageId          本次请求 ID（可为 null，兼容旧调用方）
     * @return RAG 或 SQL
     */
    public Route route(String question, RequestCancelService requestCancelService, String messageId) {
        // 【检查点】路由前
        if (requestCancelService != null && messageId != null
                && requestCancelService.shouldCancel(messageId)) {
            log.info("[路由-取消] messageId={} 用户主动中断", messageId);
            return Route.RAG; // 取消时默认走 RAG，避免卡死
        }

        String prompt = """
                 请判断以下用户问题属于哪一类，只回复RAG或SQL，不要其他内容：
                 - RAG：询问软件使用方法、命令说明、操作指南、概念解释、知识问答（如Git命令、Linux命令、请假流程等）
                 - SQL：需要查数据库里具体的数值记录、统计数字、订单明细、交易记录，航班信息等（如查销售额、查某天的数据,哪个城市的航班详细信息）

                 用户问题：%s
                 """.formatted(question);

        String result = client.prompt()
                .user(prompt)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText()
                .strip();

        // 【检查点】路由响应回来后
        if (requestCancelService != null && messageId != null
                && requestCancelService.shouldCancel(messageId)) {
            log.info("[路由-取消] messageId={} 用户主动中断（路由完成后）", messageId);
            return Route.RAG;
        }

        Route r = result.toUpperCase().contains("SQL") ? Route.SQL : Route.RAG;
        log.debug("[路由决策] question={} → {}", question, r);
        return r;
    }

    public enum Route { RAG, SQL }
}
