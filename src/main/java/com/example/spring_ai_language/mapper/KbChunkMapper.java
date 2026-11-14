package com.example.spring_ai_language.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.spring_ai_language.entity.KbChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
    @Delete("DELETE FROM kb_chunk WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);
}
