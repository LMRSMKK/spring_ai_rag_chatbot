package com.example.spring_ai_language.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.spring_ai_language.entity.ChatMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    // ──────────────────────────────────────────────
    // 会话列表（带 pending 数量统计）
    // ──────────────────────────────────────────────

    @Select("SELECT cm.session_id AS sessionId," +
            "       cm.content AS content," +
            "       cm.create_time AS createTime," +
            "       COUNT(*) OVER(PARTITION BY cm.session_id) AS msgCount" +
            "  FROM chat_message cm" +
            " WHERE cm.create_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)" +
            " ORDER BY cm.create_time DESC" +
            " LIMIT #{offset}, #{size}")
    List<ChatMessage> selectSessionList(@Param("offset") int offset, @Param("size") int size);

    /** 按 sessionId 获取所有消息（含 pending），按时间正序 */
    @Select("SELECT id, session_id AS sessionId, user_id AS userId, role, content," +
            "           model, tokens_in AS tokensIn, tokens_out AS tokensOut, cost_ms AS costMs," +
            "           ask_id AS askId, status, create_time AS createTime" +
            "    FROM chat_message" +
            "    WHERE session_id = #{sessionId}" +
            "    ORDER BY create_time ASC" +
            "    LIMIT #{limit}")
    List<ChatMessage> selectAllBySession(@Param("sessionId") String sessionId,
                                         @Param("limit") int limit);

    /** 删除指定会话的所有消息 */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    // ──────────────────────────────────────────────
    // 按 askId 查询
    // ──────────────────────────────────────────────

    @Select("SELECT id, session_id AS sessionId, user_id AS userId, role, content," +
            "           model, tokens_in AS tokensIn, tokens_out AS tokensOut, cost_ms AS costMs," +
            "           ask_id AS askId, status, create_time AS createTime" +
            "    FROM chat_message" +
            "    WHERE ask_id = #{askId}" +
            "    ORDER BY create_time ASC")
    List<ChatMessage> selectByAskId(@Param("askId") String askId);

    /** 更新单条消息的文本内容（用于填充 AI 回答到 user 行的 content 列 - 暂不使用，保留接口） */
    @Update("UPDATE chat_message SET content = #{content}, status = #{status}" +
            " WHERE id = #{id}")
    int updateContentAndStatus(@Param("id") Long id,
                               @Param("content") String content,
                               @Param("status") String status);

    /** 更新 user 行的元数据（tokens、costMs、model）并将状态改为 answered */
    @Update("UPDATE chat_message SET model = #{model}, tokens_in = #{tokensIn}," +
            "         tokens_out = #{tokensOut}, cost_ms = #{costMs}," +
            "         status = 'answered'" +
            " WHERE id = #{id}")
    int updateUserMeta(@Param("id") Long id,
                       @Param("model") String model,
                       @Param("tokensIn") Integer tokensIn,
                       @Param("tokensOut") Integer tokensOut,
                       @Param("costMs") Integer costMs);

    // ──────────────────────────────────────────────
    // 状态更新
    // ──────────────────────────────────────────────

    /** 将 pending 消息标记为 cancelled */
    @Update("UPDATE chat_message SET status = 'cancelled' WHERE id = #{id}")
    int markCancelled(@Param("id") Long id);

    /** 批量取消某 askId 下的所有 pending 记录 */
    @Update("UPDATE chat_message SET status = 'cancelled' WHERE ask_id = #{askId} AND status = 'pending'")
    int cancelByAskId(@Param("askId") String askId);

    // ──────────────────────────────────────────────
    // Pending 会话统计
    // ──────────────────────────────────────────────

    /** 查询有 pending 消息的 sessionId 列表（每个 sessionId 只返回一行） */
    @Select("SELECT DISTINCT session_id FROM chat_message WHERE status = 'pending'")
    List<String> selectPendingSessionIds();

    /** 统计某个 sessionId 下 pending 的消息数量 */
    @Select("SELECT COUNT(*) FROM chat_message WHERE session_id = #{sessionId} AND status = 'pending'")
    int countPendingBySession(@Param("sessionId") String sessionId);

    /** 取消指定 askId 下的所有 pending 记录（用于用户主动取消时清理 DB） */
    @Update("UPDATE chat_message SET status = 'cancelled' WHERE ask_id = #{askId} AND status = 'pending'")
    int markCancelledByAskId(@Param("askId") String askId);
}
