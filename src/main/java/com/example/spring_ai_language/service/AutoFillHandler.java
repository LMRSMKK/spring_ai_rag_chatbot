package com.example.spring_ai_language.service;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 自动填充 createTime / updateTime，避免每次手动 set
 */
@Service
public class AutoFillHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject,"createTime", LocalDateTime::now,LocalDateTime.class);
        this.strictInsertFill(metaObject,"updateTime", LocalDateTime::now,LocalDateTime.class);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
       this.strictInsertFill(metaObject,"updateTime",LocalDateTime::now,LocalDateTime.class);
    }

}
