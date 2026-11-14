package com.example.spring_ai_language.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文本切块表：每条记录对应向量库中的一条向量
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的文档ID */
    private Long docId;

    /** 在向量库中的唯一标识（与 Document.id 对应） */
    private String chunkId;

    /** 切块原文 */
    private String content;

    /** 所属知识库ID（冗余，便于查询过滤） */
    private String knowledgeBaseId;

    /** 在源文档中的顺序号 */
    private Integer chunkIndex;

    /** 字符数 */
    private Integer wordCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
