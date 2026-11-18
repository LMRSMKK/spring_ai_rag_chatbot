package com.example.spring_ai_language.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求取消服务：管理正在进行的问答任务取消状态。
 *
 * 设计约束：
 * - cancel() 只做原子替换，不执行 remove（避免与 shouldCancel() 产生竞态）
 * - remove() 由调用方在任务结束时统一调用（try-finally 中）
 */
@Service
public class RequestCancelService {

    private final ConcurrentHashMap<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    /** 注册新任务，初始状态为未取消 */
    public void register(String messageId) {
        cancelFlags.put(messageId, false);
    }

    /**
     * 原子性地将指定任务的取消标志置为 true。
     * 返回 true 表示成功取消（原状态为 false），返回 false 表示任务已结束或不存在。
     * ⚠️ 注意：此处不做 remove，由调用方在 finally 中统一清理。
     */
    public boolean cancel(String messageId) {
        return cancelFlags.replace(messageId, false, true);
    }

    /**
     * 检查指定任务是否已被取消。
     * 必须在 register 之后、remove 之前调用；若记录已不存在则返回 false。
     */
    public boolean shouldCancel(String messageId) {
        Boolean flag = cancelFlags.get(messageId);
        return flag != null && flag;
    }

    /**
     * 任务结束（无论成功/异常/取消）后调用，释放占用的内存。
     */
    public void remove(String messageId) {
        cancelFlags.remove(messageId);
    }
}
