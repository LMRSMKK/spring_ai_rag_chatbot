package com.example.spring_ai_language.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("log_operation")
public class LogOperation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String userId;

    /** 操作类型：CHAT / UPLOAD / QUERY */
    private String operation;

    /** 操作目标描述 */
    private String target;

    /** 请求摘要 */
    private String request;

    /** 响应摘要 */
    private String response;

    /** 耗时 ms */
    private Integer costMs;

    /** 是否成功 */
    private Boolean success;

    /** 错误信息 */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}