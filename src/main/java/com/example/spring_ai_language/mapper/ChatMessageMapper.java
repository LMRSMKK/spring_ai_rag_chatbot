package com.example.spring_ai_language.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.spring_ai_language.entity.ChatMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    @Select("SELECT * FROM chat_message" +
            "        WHERE session_id = #{sessionId}" +
            "        ORDER BY create_time ASC" +
            "            LIMIT #{n}")
    List<ChatMessage> selectRecent(@Param("sessionId") String sessionId, @Param("n") int n);

    @Select("SELECT cm.session_id AS sessionId," +
            "       cm.content AS content," +
            "       cm.create_time AS createTime," +
            "       COUNT(*) OVER(PARTITION BY cm.session_id) AS msgCount" +
            "  FROM chat_message cm" +
            " WHERE cm.create_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)" +
            " ORDER BY cm.create_time DESC" +
            " LIMIT #{offset}, #{size}")
    List<ChatMessage> selectSessionList(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(DISTINCT session_id) FROM chat_message " +
            "WHERE create_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)")
    int countSessionList();

    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    @Select(" SELECT id, session_id AS sessionId, user_id AS userId, role, content," +
            "           model, tokens_in AS tokensIn, tokens_out AS tokensOut, cost_ms AS costMs," +
            "           create_time AS createTime" +
            "    FROM chat_message" +
            "    WHERE session_id = #{sessionId}" +
            "    ORDER BY create_time ASC" +
            "    LIMIT #{limit}")
    List<ChatMessage> selectAllBySession(@Param("sessionId") String sessionId,
                                         @Param("limit") int limit);
}
