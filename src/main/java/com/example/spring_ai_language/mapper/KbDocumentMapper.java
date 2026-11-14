package com.example.spring_ai_language.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.spring_ai_language.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
    /**
     * 统计每个知识库的文档数和切片数
     */
    @Select("SELECT d.knowledge_base_id AS kbId," +
            "       COUNT(DISTINCT d.id) AS docCount," +
            "       COUNT(c.id) AS chunkCount" +
            "  FROM kb_document d" +
            "  LEFT JOIN kb_chunk c ON c.doc_id = d.id" +
            " GROUP BY d.knowledge_base_id")
    List<Map<String, Object>> countByKnowledgeBase();

    /**
     * 获取某知识库下的文件名列表（去重，保留每个文件名的最新一条记录）
     * 只返回已完成或正在处理的文档，隐藏失败的记录
     */
    @Select("SELECT d1.id AS doc_id, d1.file_name AS file_name, d1.status, d1.upload_time AS upload_time" +
            "  FROM kb_document d1" +
            "  INNER JOIN (" +
            "    SELECT file_name, MAX(upload_time) AS max_upload_time" +
            "    FROM kb_document" +
            "    WHERE knowledge_base_id = #{kbId}" +
            "      AND status IN ('COMPLETED', 'PROCESSING')" +
            "    GROUP BY file_name" +
            "  ) d2 ON d1.file_name = d2.file_name AND d1.upload_time = d2.max_upload_time" +
            " ORDER BY d1.upload_time DESC")
    List<Map<String, Object>> listFilesByKb(@Param("kbId") String kbId);
}
