package com.example.spring_ai_language.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库文档表：记录每次上传的文件信息
 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型：pdf/docx/txt/md */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 所属知识库ID（用于隔离不同部门/租户） */
    private String knowledgeBaseId;

    /** 上传者用户ID */
    private String uploadedBy;

    /** 处理状态：PROCESSING / COMPLETED / FAILED */
    private String status;

    /** 失败原因 */
    private String errorMsg;

    /** 上传时间 */
    private LocalDateTime uploadTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
