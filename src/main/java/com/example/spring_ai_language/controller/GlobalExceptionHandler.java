package com.example.spring_ai_language.controller;

import com.example.spring_ai_language.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<?> handleMaxSize(MaxUploadSizeExceededException e) {
        long maxBytes = e.getMaxUploadSize();
        log.warn("[文件过大] 限制={}MB", maxBytes / 1024 / 1024);
        return Result.error(400, "文件大小超过限制（最大" + (maxBytes / 1024 / 1024) + "MB）");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        return Result.error(400, e.getMessage());
    }
}
