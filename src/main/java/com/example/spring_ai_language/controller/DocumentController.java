package com.example.spring_ai_language.controller;

import com.example.spring_ai_language.util.Result;
import com.example.spring_ai_language.entity.KbDocument;
import com.example.spring_ai_language.mapper.KbChunkMapper;
import com.example.spring_ai_language.mapper.KbDocumentMapper;
import com.example.spring_ai_language.service.RagIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档上传接口
 */
@Slf4j
@RestController
@RequestMapping("/api/document")
@CrossOrigin(origins = "*")
public class DocumentController {
    @Autowired
    private RagIngestionService ingestionService;

    @Autowired
    private KbDocumentMapper kbDocumentMapper;

    @Autowired
    private KbChunkMapper kbChunkMapper;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam("userId") String userId) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code",400,"message", "文件不能为空"));
        }
        if(file.getSize()>15*1024*1024){
            return ResponseEntity.badRequest().body(Map.of("code",400,"message","文件不能超过15MB"));
        }
        try {
            ingestionService.ingest(file,knowledgeBaseId,userId);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "文档上传成功，正在处理",
                    "filename", file.getOriginalFilename(),
                    "size", file.getSize()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("[文档上传失败]", e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "处理失败：" + e.getMessage()));
        }
    }

    @GetMapping("/knowledge-bases")
    public Result<?> listKnowledgeBases() {
        List<Map<String, Object>> stats = kbDocumentMapper.countByKnowledgeBase();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : stats) {
            String kbId = row.get("kbId").toString();
            List<Map<String, Object>> files = kbDocumentMapper.listFilesByKb(kbId);
            log.debug("[listFiles] kbId={} fileCount={} firstRowKeys={}", kbId, files.size(),
                    files.isEmpty() ? "[]" : java.util.Arrays.toString(files.get(0).keySet().toArray()));
            Map<String, Object> kb = new HashMap<>();
            kb.put("kbId", kbId);
            kb.put("docCount", row.get("docCount"));
            kb.put("chunkCount", row.get("chunkCount"));
            List<Map<String, Object>> fileList = files.stream().map(f -> {
                // 直接用原始下划线 key（由 MyBatis map-underscore-to-camel-case 转换）
                String docId = f.get("docId") != null ? f.get("docId").toString() : f.get("doc_id") != null ? f.get("doc_id").toString() : "";
                String fileName = f.get("fileName") != null ? f.get("fileName").toString() : f.get("file_name") != null ? f.get("file_name").toString() : "";
                log.debug("[listFiles] docId={} fileName={} status={}", docId, fileName, f.get("status"));
                Map<String, Object> item = new HashMap<>();
                item.put("docId", docId);
                item.put("fileName", fileName);
                item.put("status", f.get("status"));
                return item;
            }).collect(Collectors.toList());
            kb.put("files", fileList);
            result.add(kb);
        }
        return Result.success(result);
    }

    /**
     * 按文件名删除指定知识库下的某个文档（Chroma + MySQL）
     */
    @DeleteMapping("/doc-by-name")
    public ResponseEntity<Map<String, Object>> deleteDocByName(
            @RequestParam String knowledgeBaseId,
            @RequestParam String fileName) {
        try {
            List<KbDocument> docs = kbDocumentMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbDocument>()
                            .eq(KbDocument::getKnowledgeBaseId, knowledgeBaseId)
                            .eq(KbDocument::getFileName, fileName));
            if (docs.isEmpty()) {
                return ResponseEntity.ok(Map.of("code", 200, "message", "未找到该文档"));
            }
            // 只删除最新的一条记录
            KbDocument latest = docs.stream()
                    .max(Comparator.comparing(KbDocument::getUploadTime))
                    .orElse(null);
            if (latest == null) return ResponseEntity.ok(Map.of("code", 200, "message", "未找到该文档"));
            ingestionService.deleteByDocId(latest.getId(), knowledgeBaseId);
            kbChunkMapper.deleteByDocId(latest.getId());
            kbDocumentMapper.deleteById(latest.getId());
            log.info("[删除文档] kbId={} fileName={} docId={}", knowledgeBaseId, fileName, latest.getId());
            return ResponseEntity.ok(Map.of("code", 200, "message", "删除成功"));
        } catch (Exception e) {
            log.error("[文档删除失败] fileName={}, err={}", fileName, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "删除失败：" + e.getMessage()));
        }
    }

    @PostMapping("/knowledge-base")
    public Result<?> createKnowledgeBase(@RequestBody Map<String,String > body){
        String name = body.getOrDefault("name","").toString().strip();
        String description = body.getOrDefault("description","").toString().strip();
        if (name.isEmpty()){
            return Result.error(400,"知识库名称不能为空");
        }
        // 使用用户输入的名称作为知识库标识符，而非自动生成的数字ID
        String kbId = name.length() <= 50 ? name : name.substring(0, 50);
        KbDocument doc = new KbDocument();
        doc.setFileName(name);
        doc.setFileType("kb");
        doc.setFileSize(0L);
        doc.setKnowledgeBaseId(kbId);
        doc.setStatus("ACTIVE");
        kbDocumentMapper.insert(doc);
        return Result.success(Map.of("kbId",kbId,"name",name,"description",description));
    }

    @DeleteMapping("/doc/{docId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable Long docId) {
        try {
            KbDocument doc = kbDocumentMapper.selectById(docId);
            if (doc == null) {
                return ResponseEntity.notFound().build();
            }
            ingestionService.deleteByDocId(docId, doc.getKnowledgeBaseId());
            kbChunkMapper.deleteByDocId(docId);
            kbDocumentMapper.deleteById(docId);
            return ResponseEntity.ok(Map.of("code", 200, "message", "删除成功", "docId", docId));
        } catch (Exception e) {
            log.error("[文档删除失败] docId={}", docId, e);
            return ResponseEntity.internalServerError().body(Map.of("code", 500, "message", "删除失败：" + e.getMessage()));
        }
    }
}
