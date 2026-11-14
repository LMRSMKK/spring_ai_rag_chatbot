package com.example.spring_ai_language.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 多轮对话消息表
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话 ID（一次完整问答为一个 session） */
    private String sessionId;

    /** 用户 ID */
    private String userId;

    /** 角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 使用的模型名 */
    private String model;

    /** 输入 token 数 */
    private Integer tokensIn;

    /** 输出 token 数 */
    private Integer tokensOut;

    /** 耗时毫秒 */
    private Integer costMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
