package com.example.spring_ai_language.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.spring_ai_language.entity.KbChunk;
import com.example.spring_ai_language.entity.KbDocument;
import com.example.spring_ai_language.mapper.KbChunkMapper;
import com.example.spring_ai_language.mapper.KbDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RagIngestionService {
    @Autowired
    private Tika tika;

    @Autowired
    private IntelligentChunkService chunkService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private KbChunkMapper kbChunkMapper;

    @Autowired
    private KbDocumentMapper kbDocumentMapper;

    /**
     * 主入口：接收文件，完成全链路入库
     * @param file             用户上传的文件
     * @param knowledgeBaseId  知识库ID（租户隔离）
     * @param userId           当前用户ID
     */

    @Transactional(rollbackFor = Exception.class)
    public void ingest(MultipartFile file,String knowledgeBaseId,String userId)throws Exception{
        String fileName=file.getOriginalFilename();
        String fileType=extractFileType(fileName);
        long fileSize=file.getSize();

        log.info("[入库开始] 文件={}, type={}, size={}KB, kbId={}, user={}",
                fileName, fileType, fileSize / 1024, knowledgeBaseId, userId);

        String content;
        try (InputStream is=file.getInputStream()){
            // .md/.txt 等纯文本直接用字节读，避免 Tika 默认无 parser 时返回空串
            if ("md".equals(fileType)||"txt".equals(fileType)||"text".equals(fileType)||"pdf".equals(fileType)||"docx".equals(fileType)) {
                content=new String(is.readAllBytes(),"UTF-8").strip();
            }else{
                content=tika.parseToString(is).strip();
            }
        }
        log.info("[解析完成] 总字符数={}", content.length());
        if (content.isBlank()||content.length()<20){
            throw new IllegalArgumentException("文档内容为空或过短，无法入库");
        }
        KbDocument doc = new KbDocument();
        doc.setFileName(fileName);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setKnowledgeBaseId(knowledgeBaseId);
        doc.setUploadedBy(userId);
        doc.setStatus("PROCESSING");
        doc.setUploadTime(LocalDateTime.now());
        kbDocumentMapper.insert(doc);
        log.info("[文档记录] 插入 kb_document id={}", doc.getId());

        //智能分片
        List<TextChunk> chunks=chunkService.chunk(content,fileType,fileName,knowledgeBaseId,userId);
        log.info("[分片完成] 共 {} 个片段，chunkSize={}, overlap={}", chunks.size(), 500, 50);
        int success=0;
        for(TextChunk t:chunks){
            try {
                float[] embedding =embeddingModel.embed(t.content());
                Map<String,Object> metadata=Map.of(
                        "chunk_id",     t.id(),
                        "doc_id",       String.valueOf(doc.getId()),
                        "kb_id",        knowledgeBaseId,
                        "user_id",      userId,
                        "file_name",    t.fileName(),
                        "chunk_index",  String.valueOf(t.index())
                );
                Document document = new Document(t.content(), metadata);
                vectorStore.add(List.of(document));
                KbChunk chunk=new KbChunk();
                chunk.setDocId(doc.getId());
                chunk.setChunkId(t.id());
                chunk.setContent(t.content());
                chunk.setKnowledgeBaseId(knowledgeBaseId);
                chunk.setChunkIndex(t.index());
                chunk.setWordCount(t.content().length());
                kbChunkMapper.insert(chunk);

                success++;
            }catch (Exception e){
                log.error("[分片写入失败] chunkIndex={}, err={}", t.index(), e.getMessage());
            }
        }

        doc.setStatus(success>0 ? "COMPLETED" : "FAILED");
        if(success==0){
            doc.setErrorMsg("所有切块写入失败，请检查embedding模型和向量库连接");
        }
        kbDocumentMapper.updateById(doc);
        log.info("[入库完成] id={}, totalChunks={}, success={}", doc.getId(), chunks.size(), success);
    }
    /** 从文件名提取扩展名（小写） */
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "txt";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /** 按文档ID查询该文档的所有切片（供调试/溯源） */
    public List<KbChunk> getChunksByDocId(Long docId) {
        LambdaQueryWrapper<KbChunk> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(KbChunk::getDocId, docId)
                .orderByAsc(KbChunk::getChunkIndex);
        List<KbChunk> chunks=kbChunkMapper.selectList(wrapper);
        return chunks;
    }

    /**
     * 删除指定知识库下某个文档在 Chroma 中的所有 embedding
     * 思路：用 filterExpression 查回匹配的所有 ID，再调 vectorStore.delete()
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(Long docId, String kbId) {
        try {
            // 双重过滤：kb_id + doc_id，避免误删其他知识库的数据
            String safeKbId = kbId == null ? "" : kbId.replace("'", "''");
            String safeDocId = String.valueOf(docId).replace("'", "''");
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("")
                            .topK(10000)
                            .filterExpression("kb_id = '" + safeKbId + "' AND doc_id = '" + safeDocId + "'")
                            .build()
            );
            if (!docs.isEmpty()) {
                List<String> ids = docs.stream()
                        .map(Document::getId)
                        .collect(Collectors.toList());
                vectorStore.delete(ids);
                log.info("[Chroma删除] docId={} kbId={} deletedIds={}", docId, kbId, ids.size());
            }
        } catch (Exception e) {
            log.warn("[Chroma删除失败] docId={} err={}", docId, e.getMessage());
        }
    }
}
