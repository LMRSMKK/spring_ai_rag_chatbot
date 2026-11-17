package com.example.spring_ai_language.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能文本分片服务
 *
 * 策略选择：
 *   - Markdown：按标题层级分段，保证每个 chunk 是一个完整的章节
 *   - 普通文本：按段落（双换行）分割，再按字符数二次切分
 *   - 未知类型：使用固定长度+重叠切片
 */
@Service
public class IntelligentChunkService {
    @Value("${app.rag.chunk-size:500}")
    private int chunkSize;
    @Value("${app.rag.chunk-overlap:50}")
    private int overlap;

    /**
     * 根据文件类型选择最佳分片策略
     */
    public List<TextChunk> chunk(String content,String fileType,String fileName,String kbId,String userId){
        if(content.isEmpty())return List.of();
        return switch (fileType.toLowerCase()){
            case "markdown","md"-> byHeadings(content,kbId,userId,fileName);
            default -> byParagraphs(content,kbId,userId,fileName);
        };
    }



    // ─────────────────────────────────────────────
    // 策略1：Markdown 按标题切分
    // ─────────────────────────────────────────────
    private List<TextChunk> byHeadings(String content, String kbId, String userId, String fileName) {
        Pattern hpattern = Pattern.compile("^#{1,3}\\s+.+$", Pattern.MULTILINE);
        Matcher matcher = hpattern.matcher(content);

        List<TextChunk> chunks = new ArrayList<>();
        int lastPos = 0;
        int idx = 0;
        while (matcher.find()) {
            String seg = content.substring(lastPos, matcher.start()).stripTrailing();
            if (!seg.isEmpty() && seg.length() > 20) {
                chunks.add(new TextChunk(null, seg, fileName, kbId, userId, idx++));
            }
            lastPos = matcher.start();
        }
        //最后一段
        String tail = content.substring(lastPos).stripTrailing();
        if (!tail.isEmpty() && tail.length() > 20) {
            chunks.add(new TextChunk(null, tail, fileName, kbId, userId, idx++));
        }
        //太短没触发标题匹配，退回备用方案
        if (chunks.isEmpty() && content.length() > 50) {
            chunks.addAll(fixedSplit(content, kbId, userId, fileName));
        }
        return chunks;
    }


    // ─────────────────────────────────────────────
    // 策略2：按段落切分，超长则二次按字符数切
    // ─────────────────────────────────────────────
    private List<TextChunk> byParagraphs(String content, String kbId, String userId, String fileName) {
        String[] paragraphs=content.split("\\n\\s*\\n");
        List<TextChunk> chunks=new ArrayList<>();
        StringBuilder sb=new StringBuilder();
        int idex=0;

        for(String para:paragraphs){
            String trimmed=para.strip();
            if(trimmed.isBlank())continue;
            if(sb.length()+trimmed.length()+2>chunkSize&&sb.length()>0){
                chunks.add(new TextChunk(null,sb.toString().strip(),fileName,kbId,userId,idex++));
                sb=new StringBuilder(trimmed+"\n\n");
            }else {
                sb.append(trimmed).append("\n\n");
            }
        }
        if(sb.length()>50){
            chunks.add(new TextChunk(null, sb.toString().strip(),fileName,kbId,userId,idex));
        }
        if (chunks.isEmpty() && content.length() > 50) {
            chunks.addAll(fixedSplit(content,kbId,userId,fileName));
        }
        return chunks;
    }

    // ─────────────────────────────────────────────
    // 兜底：固定长度 + 重叠切片
    // ─────────────────────────────────────────────
    private Collection<TextChunk> fixedSplit(String content, String kbId, String userId, String fileName) {
        List<TextChunk> chunks=new ArrayList<>();
        int idx=0;
        int start=0;
        while(start<content.length()){
            int end=Math.min(start+chunkSize,content.length());
            int breakAt = findBreakPoint(content,start,end);
            String segment=content.substring(start,end).strip();
            if(!segment.isBlank()){
                chunks.add(new TextChunk(null,segment,fileName,kbId,userId,idx++));
            }
            start=end-overlap;
        }
        return chunks;
    }

    /** 在 [start, end] 范围内找最佳断点（优先换行或标点） */
    private int findBreakPoint(String content, int start, int end) {
        String window=content.substring(start,end);
        //优先在换行处断开
        int nl=window.lastIndexOf('\n');
        if(nl>end-start-chunkSize/2)return start+nl;
        for(int i=window.length()-1;i>=Math.max(0,window.length()-chunkSize/2);i++) {
            char c = window.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') return start + i + 1;
        }
            return end;
    }
}
