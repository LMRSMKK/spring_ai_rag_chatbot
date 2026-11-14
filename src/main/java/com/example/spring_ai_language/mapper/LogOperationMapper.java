package com.example.spring_ai_language.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.spring_ai_language.entity.LogOperation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface LogOperationMapper extends BaseMapper<LogOperation> {

    /**
     * 分页查询操作日志（按时间倒序）
     */
    @Select("SELECT id, user_id AS userId, operation, request, response, " +
            "       cost_ms AS costMs, success, error_msg AS errorMsg, create_time AS createTime" +
            "  FROM log_operation" +
            " ORDER BY create_time DESC" +
            " LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectPage(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM log_operation")
    int countAll();
}
