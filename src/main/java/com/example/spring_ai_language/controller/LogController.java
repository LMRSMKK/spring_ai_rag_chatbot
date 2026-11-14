package com.example.spring_ai_language.controller;

import com.example.spring_ai_language.Uitl.Result;
import com.example.spring_ai_language.mapper.LogOperationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@CrossOrigin(origins = "*")
public class LogController {

    @Autowired
    private LogOperationMapper logOperationMapper;

    @GetMapping
    public Result<?> listLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int offset = page * size;
        List<Map<String, Object>> rows = logOperationMapper.selectPage(offset, size);
        int total = logOperationMapper.countAll();
        Map<String, Object> data = new HashMap<>();
        data.put("list", rows);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        data.put("pages", (total + size - 1) / size);
        return Result.success(data);
    }
}
