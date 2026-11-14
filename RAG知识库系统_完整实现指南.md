# 企业级 RAG 知识库系统 — 完整实现指南（含全部源码）

> 来源：B站【2026版】Spring AI 教程 P49~P62（第49集起为项目实战部分）  
> 技术栈：Spring Boot 3.4.x + Spring AI 2.1.0-M1 + DeepSeek + Chroma + MySQL 8 + Redis  
> 数据库端口：MySQL 3307（本机3306被占用）

---

## 目录

1. [环境准备](#一环境准备)
2. [Maven 依赖（pom.xml）](#二maven-依赖pomxml)
3. [配置文件（application.yml）](#三配置文件applicationyml)
4. [实体类（Entity）](#四实体类entity)
5. [数据访问层（Mapper）](#五数据访问层mapper)
6. [Service 层](#六service-层)
7. [Controller 层](#七controller-层)
8. [Advisor 拦截器](#八advisor-拦截器)
9. [Tool 工具类](#九tool-工具类)
10. [配置类](#十配置类)
11. [启动 & 测试](#十一启动--测试)
12. [前端对接说明](#十二前端对接说明)

---

## 一、环境准备

### 1.1 启动基础设施（Docker Compose）

```cmd
cd D:\IDE\Spring_AI_Language
docker compose up -d
```

三个容器必须全部 Running：
- `rag-chroma`：Chroma 向量库（localhost:8000）
- `rag-mysql`：MySQL 8.0（localhost:3307，密码 root123，库名 rag_kb）
- `rag-redis`：Redis（localhost:6379）

验证 SQL 表已自动建好：
```cmd
docker exec rag-mysql mysql -uroot -proot123 rag_kb -e "SHOW TABLES;"
```
应看到：chat_message / kb_chunk / kb_document / log_operation / sensitive_word

### 1.2 申请 DeepSeek API Key

去 https://platform.deepseek.com/api_keys 注册并获取 key，填入下方 application.yml 中的 `api-key`。

---

## 二、Maven 依赖（pom.xml）

用 IDE 打开 `D:\IDE\Spring_AI_Language` 作为 Maven 项目，替换 pom.xml：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.1</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>rag-knowledge-base</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>rag-knowledge-base</name>

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>2.1.0-M1</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Spring AI BOM：统一管理所有 Spring AI 组件版本 -->
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring AI OpenAI 兼容客户端（接 DeepSeek） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
        </dependency>

        <!-- Spring AI Chroma 向量存储 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-chroma-store-spring-boot-starter</artifactId>
        </dependency>

        <!-- Spring AI Chat Memory（JDBC持久化） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-autoconfigure-model-chat-memory</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-autoconfigure-model-chat-memory-repository-jdbc</artifactId>
        </dependency>

        <!-- JDBC 驱动（对话历史存入MySQL） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- MyBatis Plus（更强大的ORM） -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>3.5.7</version>
        </dependency>

        <!-- MySQL 驱动 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Redis（对话缓存加速） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Apache Tika（文档解析：PDF/Word/TXT） -->
        <dependency>
            <groupId>org.apache.tika</groupId>
            <artifactId>tika-core</artifactId>
            <version>3.0.0</version>
        </dependency>

        <!-- Lombok（简化代码） -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- 单元测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <!-- 阿里云镜像加速（国内必配，否则下载很慢） -->
    <repositories>
        <repository>
            <id>aliyunmaven</id>
            <url>https://maven.aliyun.com/repository/public</url>
        </repository>
    </repositories>
    <pluginRepositories>
        <pluginRepository>
            <id>aliyunmaven</id>
            <url>https://maven.aliyun.com/repository/public</url>
        </pluginRepository>
    </pluginRepositories>

</project>
```

---

## 三、配置文件（application.yml）

路径：`src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: rag-knowledge-base

  # ─── MySQL（对接 Docker 里的 rag-mysql，端口3307）───
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3307/rag_kb?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: root123

  # ─── Redis ───
  data:
    redis:
      host: localhost
      port: 6379
      password:

  # ─── 文件上传限制（默认 1MB，改大支持 PDF/Word）───
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 100MB

  # ─── Spring AI 通用配置 ───
  ai:
    # 深度思考模式开关（DeepSeek R1 等推理模型开启后思考质量更好但更慢）
    deepseek:
      enable-thinking: false

    # ─── OpenAI 兼容接口（DeepSeek 支持此协议）───
    openai:
      api-key: sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxx   # ⚠️ 换成你自己的 DeepSeek API Key
      base-url: https://api.deepseek.com/v1       # DeepSeek OpenAI 兼容地址
      chat:
        options:
          model: deepseek-chat                     # 普通对话用这个
          temperature: 0.7
          max-tokens: 2048
      embedding:
        options:
          model: text-embedding-v3                 # 文本向量化用这个

    # ─── Chroma 向量库 ───
    chroma:
      uri: http://localhost:8000
      collection-name: rag_kb_collection           # 集合名称
      replicate-distance: false                    # 关闭距离复制（性能优化）

    # ─── Chat Memory（对话记忆 JDBC 持久化）───
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always              # 首次启动自动建表

  # ─── MyBatis Plus 配置 ───
mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.example.spring_ai_language.entity
  configuration:
    map-underscore-to-camel-case: true           # 下划线自动转驼峰
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # 开发期打印SQL

# ─── 业务自定义配置 ───
app:
  rag:
    chunk-size: 500         # 每块字符数（中文建议 300~500）
    chunk-overlap: 50       # 块间重叠字符数（防止截断语义）
    top-k: 5                # RAG 检索返回片段数
    rerank-enabled: true    # 是否启用 Cross-Encoder 重排序
    rerank-top-k: 10        # 重排序候选数
  sensitive-words:
    enable: true
    words: "政治敏感词,违法词汇,欺诈信息"  # 生产环境应从 DB 加载，此处仅示例

logging:
  level:
    com.example.spring_ai_language: DEBUG
    org.springframework.ai: DEBUG
    org.springframework.ai.chat.client.advisor: TRACE
```

---

## 四、实体类（Entity）

### 4.1 KbDocument.java — 文档元信息

```java
package com.example.spring_ai_language.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库文档表：记录每次上传的文件信息
 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型：pdf/docx/txt/md */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 所属知识库ID（用于隔离不同部门/租户） */
    private String knowledgeBaseId;

    /** 上传者用户ID */
    private String uploadedBy;

    /** 处理状态：PROCESSING / COMPLETED / FAILED */
    private String status;

    /** 失败原因 */
    private String errorMsg;

    /** 上传时间 */
    private LocalDateTime uploadTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

### 4.2 KbChunk.java — 文本切块

```java
package com.example.spring_ai_language.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文本切块表：每条记录对应向量库中的一条向量
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的文档ID */
    private Long docId;

    /** 在向量库中的唯一标识（与 Document.id 对应） */
    private String chunkId;

    /** 切块原文 */
    private String content;

    /** 所属知识库ID（冗余，便于查询过滤） */
    private String knowledgeBaseId;

    /** 上传者（冗余，用于权限过滤） */
    private String userId;

    /** 在源文档中的顺序号 */
    private Integer chunkIndex;

    /** 字符数 */
    private Integer wordCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
```

### 4.3 ChatMessage.java — 对话消息

```java
package com.example.spring_ai_language.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 多轮对话消息表
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话 ID（一次完整问答为一个 session） */
    private String sessionId;

    /** 用户 ID */
    private String userId;

    /** 角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 使用的模型名 */
    private String model;

    /** 输入 token 数 */
    private Integer tokensIn;

    /** 输出 token 数 */
    private Integer tokensOut;

    /** 耗时毫秒 */
    private Integer costMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
```

### 4.4 LogOperation.java — 操作日志

```java
package com.example.spring_ai_language.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 操作日志表：记录每次 API 调用
 */
@Data
@TableName("log_operation")
public class LogOperation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String userId;

    /** 操作类型：CHAT / UPLOAD / QUERY */
    private String operation;

    /** 操作目标描述 */
    private String target;

    /** 请求摘要 */
    private String request;

    /** 响应摘要 */
    private String response;

    /** 耗时 ms */
    private Integer costMs;

    /** 是否成功 */
    private Boolean success;

    /** 错误信息 */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
```

---

## 五、数据访问层（Mapper）

### 5.1 KbDocumentMapper.java

```java
package com.example.spring_ai_language.mapper;

import com.example.spring_ai_language.entity.KbDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
```

### 5.2 KbChunkMapper.java

```java
package com.example.spring_ai_language.mapper;

import com.example.spring_ai_language.entity.KbChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
}
```

### 5.3 ChatMessageMapper.java

```java
package com.example.spring_ai_language.mapper;

import com.example.spring_ai_language.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 按会话ID倒序取最近 N 条 */
    List<ChatMessage> selectRecent(@Param("sessionId") String sessionId, @Param("n") int n);
}
```

对应的 XML：`src/main/resources/mapper/ChatMessageMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.spring_ai_language.mapper.ChatMessageMapper">

    <select id="selectRecent" resultType="com.example.spring_ai_language.entity.ChatMessage">
        SELECT * FROM chat_message
        WHERE session_id = #{sessionId}
        ORDER BY create_time DESC
        LIMIT #{n}
    </select>

</mapper>
```

### 5.4 LogOperationMapper.java

```java
package com.example.spring_ai_language.mapper;

import com.example.spring_ai_language.entity.LogOperation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LogOperationMapper extends BaseMapper<LogOperation> {
}
```

---

## 六、Service 层

### 6.1 AutoFillHandler.java — MyBatis Plus 自动填充

```java
package com.example.spring_ai_language.service;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 自动填充 createTime / updateTime，避免每次手动 set
 */
@Component
public class AutoFillHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime",  LocalDateTime::now, LocalDateTime.class);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime::now, LocalDateTime.class);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime::now, LocalDateTime.class);
    }
}
```

### 6.2 TextChunk.java — 内部 DTO（不要放 entity 包）

```java
package com.example.spring_ai_language.service;

import java.util.UUID;

/**
 * 文本切块数据载体
 */
public record TextChunk(String id, String content,
                         String fileName, String knowledgeBaseId,
                         String userId, int index) {
    public TextChunk {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
    }
}
```

### 6.3 IntelligentChunkService.java — 智能分片

```java
package com.example.spring_ai_language.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
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
    public List<TextChunk> chunk(String content, String fileType,
                                  String fileName, String kbId, String userId) {
        if (content.isBlank()) return List.of();

        return switch (fileType.toLowerCase()) {
            case "markdown", "md" -> byHeadings(content, kbId, userId, fileName);
            default               -> byParagraphs(content, kbId, userId, fileName);
        };
    }

    // ─────────────────────────────────────────────
    // 策略1：Markdown 按标题切分
    // ─────────────────────────────────────────────
    private List<TextChunk> byHeadings(String content, String kbId,
                                        String userId, String fileName) {
        Pattern hPattern = Pattern.compile("^#{1,3}\\s+.+$", Pattern.MULTILINE);
        Matcher matcher = hPattern.matcher(content);

        List<TextChunk> chunks = new ArrayList<>();
        int lastPos = 0;
        int idx = 0;

        while (matcher.find()) {
            String seg = content.substring(lastPos, matcher.start()).stripTrailing();
            if (!seg.isBlank() && seg.length() > 20) {
                chunks.add(new TextChunk(null, seg, fileName, kbId, userId, idx++));
            }
            lastPos = matcher.start();
        }
        // 最后一段
        String tail = content.substring(lastPos).stripTrailing();
        if (!tail.isBlank() && tail.length() > 20) {
            chunks.add(new TextChunk(null, tail, fileName, kbId, userId, idx++));
        }
        // 如果整段太短没触发标题匹配，退回备用方案
        if (chunks.isEmpty() && content.length() > 50) {
            chunks.addAll(fixedSplit(content, kbId, userId, fileName));
        }
        return chunks;
    }

    // ─────────────────────────────────────────────
    // 策略2：按段落切分，超长则二次按字符数切
    // ─────────────────────────────────────────────
    private List<TextChunk> byParagraphs(String content, String kbId,
                                          String userId, String fileName) {
        String[] paragraphs = content.split("\\n\\s*\\n");
        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int idx = 0;

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isBlank()) continue;

            if (sb.length() + trimmed.length() + 2 > chunkSize && sb.length() > 0) {
                chunks.add(new TextChunk(null, sb.toString().strip(), fileName, kbId, userId, idx++));
                sb = new StringBuilder(trimmed + "\n\n");
            } else {
                sb.append(trimmed).append("\n\n");
            }
        }
        if (sb.length() > 50) {
            chunks.add(new TextChunk(null, sb.toString().strip(), fileName, kbId, userId, idx++));
        }
        if (chunks.isEmpty() && content.length() > 50) {
            chunks.addAll(fixedSplit(content, kbId, userId, fileName));
        }
        return chunks;
    }

    // ─────────────────────────────────────────────
    // 兜底：固定长度 + 重叠切片
    // ─────────────────────────────────────────────
    private List<TextChunk> fixedSplit(String content, String kbId,
                                        String userId, String fileName) {
        List<TextChunk> chunks = new ArrayList<>();
        int idx = 0;
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            // 尽量在句号/换行处断开，保持语义完整
            int breakAt = findBreakPoint(content, start, end);
            if (breakAt > start) end = breakAt;
            String segment = content.substring(start, end).strip();
            if (!segment.isBlank()) {
                chunks.add(new TextChunk(null, segment, fileName, kbId, userId, idx++));
            }
            start = end - overlap;  // 往前回退 overlap 字，保证上下文连续
        }
        return chunks;
    }

    /** 在 [start, end] 范围内找最佳断点（优先换行或标点） */
    private int findBreakPoint(String s, int start, int end) {
        String window = s.substring(start, end);
        // 优先在换行处断开
        int nl = window.lastIndexOf('\n');
        if (nl > end - start - chunkSize / 2) return start + nl;
        // 其次在中文字句号处
        for (int i = window.length() - 1; i >= Math.max(0, window.length() - chunkSize / 2); i--) {
            char c = window.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') return start + i + 1;
        }
        return end;
    }
}
```

### 6.4 RagIngestionService.java — 文档入库流水线（核心）

```java
package com.example.spring_ai_language.service;

import com.example.spring_ai_language.entity.KbChunk;
import com.example.spring_ai_language.entity.KbDocument;
import com.example.spring_ai_language.mapper.KbChunkMapper;
import com.example.spring_ai_language.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 入库流水线：
 *   上传文件 → Tika 解析 → 智能分片 → Embedding → 写入向量库 + 关系库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestionService {

    private final Tika tika = new Tika();
    private final IntelligentChunkService chunkService;
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;

    /**
     * 主入口：接收文件，完成全链路入库
     *
     * @param file             用户上传的文件
     * @param knowledgeBaseId  知识库ID（租户隔离）
     * @param userId           当前用户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void ingest(MultipartFile file, String knowledgeBaseId, String userId) throws Exception {
        String fileName = file.getOriginalFilename();
        String fileType = extractFileType(fileName);
        long fileSize = file.getSize();

        log.info("[入库开始] 文件={}, type={}, size={}KB, kbId={}, user={}",
                fileName, fileType, fileSize / 1024, knowledgeBaseId, userId);

        // ① 解析文档，提取纯文本
        String content;
        try (InputStream is = file.getInputStream()) {
            content = tika.parseToString(is).strip();
        }
        log.info("[解析完成] 总字符数={}", content.length());

        if (content.isBlank() || content.length() < 20) {
            throw new IllegalArgumentException("文档内容为空或过短，无法入库");
        }

        // ② 插入文档元信息到 MySQL（初始状态 PROCESSING）
        KbDocument doc = new KbDocument();
        doc.setFileName(fileName);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setKnowledgeBaseId(knowledgeBaseId);
        doc.setUploadedBy(userId);
        doc.setStatus("PROCESSING");
        doc.setUploadTime(LocalDateTime.now());
        docMapper.insert(doc);
        log.info("[文档记录] 插入 kb_document id={}", doc.getId());

        // ③ 智能分片
        List<TextChunk> chunks = chunkService.chunk(content, fileType, fileName, knowledgeBaseId, userId);
        log.info("[分片完成] 共 {} 个片段，chunkSize={}, overlap={}", chunks.size(), 500, 50);

        // ④ 批量向量化 + 写入向量库
        int success = 0;
        for (TextChunk chunk : chunks) {
            try {
                // 调用 Embedding 模型生成向量
                float[] embedding = embeddingModel.embed(chunk.content());

                // 构造 Document，metadata 带 kbId/userId 实现隔离
                Map<String, Object> metadata = Map.of(
                        "chunk_id",     chunk.id(),
                        "doc_id",       String.valueOf(doc.getId()),
                        "kb_id",        knowledgeBaseId,
                        "user_id",      userId,
                        "file_name",    chunk.fileName(),
                        "chunk_index",  String.valueOf(chunk.index())
                );
                Document document = new Document(chunk.content(), metadata);
                vectorStore.add(List.of(document));

                // 记录切块结果到 MySQL
                KbChunk chunkRecord = new KbChunk();
                chunkRecord.setDocId(doc.getId());
                chunkRecord.setChunkId(chunk.id());
                chunkRecord.setContent(chunk.content());
                chunkRecord.setKnowledgeBaseId(knowledgeBaseId);
                chunkRecord.setUserId(userId);
                chunkRecord.setChunkIndex(chunk.index());
                chunkRecord.setWordCount(chunk.content().length());
                chunkMapper.insert(chunkRecord);

                success++;
            } catch (Exception e) {
                log.error("[分片写入失败] chunkIndex={}, err={}", chunk.index(), e.getMessage());
            }
        }

        // ⑤ 更新文档状态
        doc.setStatus(success > 0 ? "COMPLETED" : "FAILED");
        if (success == 0) {
            doc.setErrorMsg("所有切块写入失败，请检查 Embedding 模型和向量库连接");
        }
        docMapper.updateById(doc);
        log.info("[入库完成] id={}, totalChunks={}, success={}", doc.getId(), chunks.size(), success);
    }

    /** 从文件名提取扩展名（小写） */
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "txt";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /** 按文档ID查询该文档的所有切片（供调试/溯源） */
    public List<KbChunk> getChunksByDocId(Long docId) {
        return chunkMapper.selectList(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.lambdaBuild(
                        com.example.spring_ai_language.entity.KbChunk.class,
                        w -> w.eq(KbChunk::getDocId, docId))
        );
    }
}
```

> **注意**：上面使用了 LambdaQueryWrapper 的错误写法，修正如下——先创建 mapper query helper：

把 `RagIngestionService.java` 中末尾的 `getChunksByDocId` 方法改为：

```java
    public List<KbChunk> getChunksByDocId(Long docId) {
        return chunkMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getDocId, docId)
                        .orderByAsc(KbChunk::getChunkIndex)
        );
    }
```

### 6.5 RagQueryService.java — RAG 检索回答（核心）

```java
package com.example.spring_ai_language.service;

import com.example.spring_ai_language.entity.ChatMessage;
import com.example.spring_ai_language.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 问答核心服务
 *
 * 流程：
 *   用户问题 → 向量化 → 向量库检索 → 拼接 Prompt → 调用 LLM → 保存对话 → 返回
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMessageMapper messageMapper;

    /**
     * 执行一轮 RAG 问答
     *
     * @param knowledgeBaseId  指定知识库（控制范围）
     * @param userId           当前用户
     * @param sessionId        会话ID（UUID）
     * @param question         用户问题
     * @return LLM 回答文本
     */
    public String ask(String knowledgeBaseId, String userId,
                      String sessionId, String question) {

        long startMs = System.currentTimeMillis();

        // ── 第一步：从 MySQL 加载最近 20 条历史消息（多轮对话记忆）──
        List<ChatMessage> history = messageMapper.selectRecent(sessionId, 20);
        List<Message> messages = buildMessages(history, question, knowledgeBaseId);

        // ── 第二步：调用 LLM（流式或非流式均可）──
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .messages(messages)
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            log.error("[LLM调用失败] userId={}, err={}", userId, e.getMessage());
            saveAndReturn(sessionId, userId, question, "服务暂时不可用，请稍后再试。", startMs);
            return "服务暂时不可用，请稍后再试。";
        }

        String answer = response.getResult().getOutput().getText();
        long costMs = System.currentTimeMillis() - startMs;

        // ── 第三步：保存本次对话 ──
        saveAndReturn(sessionId, userId, question, answer, costMs);

        log.info("[RAG问答] user={} kb={} cost={}ms tokens_in={}",
                userId, knowledgeBaseId, costMs, response.getMetadata().getUsage().getTotalTokens());
        return answer;
    }

    // ─────────────────────────────────────────────
    // 私有辅助方法
    // ─────────────────────────────────────────────

    /**
     * 构建发送给 LLM 的消息列表
     * 结构：[System提示词 + Context] + [历史对话] + [当前问题]
     */
    private List<Message> buildMessages(List<ChatMessage> history, String question, String kbId) {
        List<Message> messages = new ArrayList<>();

        // ① System 提示词 + RAG 检索上下文
        String context = retrieveContext(kbId, question);
        messages.add(buildSystemMessage(context));

        // ② 历史对话（按时间正序）
        for (ChatMessage m : history) {
            if ("user".equals(m.getRole())) {
                messages.add(new UserMessage(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                messages.add(new AssistantMessage(m.getContent()));
            }
        }

        // ③ 当前问题
        messages.add(new UserMessage(question));
        return messages;
    }

    /**
     * 向量检索相关文档片段，拼成上下文字符串
     * metadata 中的 kb_id 实现知识库隔离
     */
    private String retrieveContext(String kbId, String question) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(5)
                            .filterExpression("kb_id = '" + kbId + "'")
                            .build()
            );
            return docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.warn("[RAG检索失败] kbId={}, err={}", kbId, e.getMessage());
            return "";
        }
    }

    private Message buildSystemMessage(String context) {
        String base = """
                你是一个专业的企业知识助手。你的回答必须严格基于提供的参考资料，\
                不得编造资料中没有的信息。如果参考资料中找不到答案，请直接告知"\
                抱歉，相关资料中未找到该问题的答案，建议联系相关部门咨询。"\
                回答请使用简洁、条理清晰的语言。""";
        if (context != null && !context.isBlank()) {
            return new org.springframework.ai.chat.messages.SystemMessage(
                    base + "\n\n=== 参考资料 ===\n" + context);
        }
        return new org.springframework.ai.chat.messages.SystemMessage(base);
    }

    private void saveAndReturn(String sessionId, String userId,
                                String question, String answer, long costMs) {
        ChatMessage qMsg = new ChatMessage();
        qMsg.setSessionId(sessionId);
        qMsg.setUserId(userId);
        qMsg.setRole("user");
        qMsg.setContent(question);
        qMsg.setCostMs((int) costMs);
        messageMapper.insert(qMsg);

        ChatMessage aMsg = new ChatMessage();
        aMsg.setSessionId(sessionId);
        aMsg.setUserId(userId);
        aMsg.setRole("assistant");
        aMsg.setContent(answer);
        aMsg.setCostMs((int) costMs);
        messageMapper.insert(aMsg);
    }
}
```

### 6.6 QuestionRouter.java — 路由决策（RAG vs Text-to-SQL）

```java
package com.example.spring_ai_language.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 路由服务：判断用户问题应该走 RAG（查文档）还是 Text-to-SQL（查数据库）
 *
 * RAG：制度问答、流程说明、产品文档等"知识型"问题
 * SQL：数据统计、订单查询、人员信息等"数据型"问题
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionRouter {

    private final ChatClient chatClient;

    public Route route(String question) {
        String prompt = """
                请判断以下用户问题属于哪一类，只回复 RAG 或 SQL，不要其他内容：
                - RAG：询问公司制度、操作流程、产品说明、帮助文档等知识性问题
                - SQL：需要查询具体数值、统计数据、历史记录等业务数据问题

                用户问题：%s
                """.formatted(question);

        String result = chatClient.prompt()
                .user(prompt)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText()
                .strip();

        Route r = result.toUpperCase().contains("SQL") ? Route.SQL : Route.RAG;
        log.debug("[路由决策] question={} → {}", question, r);
        return r;
    }

    public enum Route { RAG, SQL }
}
```

### 6.7 TextToSqlService.java — Text-to-SQL 编排

```java
package com.example.spring_ai_language.service;

import com.example.spring_ai_language.tools.SqlTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Text-to-SQL 服务：将自然语言转为 SQL 并执行，再将结果整理成自然语言回答
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextToSqlService {

    private final ChatClient chatClient;
    private final SqlTool sqlTool;

    /**
     * 执行 Text-to-SQL 问答
     *
     * @param question  用户自然语言问题
     * @param userId    用户ID（用于权限审计）
     * @return 最终自然语言回答
     */
    public String answerWithSql(String question, String userId) {
        // ① 获取数据库表结构（作为 Schema 上下文发给 LLM）
        String schema = sqlTool.getSchema();
        log.debug("[TextToSql] 获取到表结构，长度={}", schema.length());

        // ② 让 LLM 生成 SQL
        String generatePrompt = """
                你是一位精通 SQL 的数据分析师。根据以下数据库表结构，将用户的自然语言问题转为可执行的 SELECT 语句。\
                要求：\
                1. 只输出 SQL，不要任何解释或其他文字\
                2. 确保 SQL 语法正确，字段名与表结构完全一致\
                3. 不要输出分号\
                4. 日期字段使用 YYYY-MM-DD 格式

                【数据库表结构】
                %s

                【用户问题】
                %s
                """.formatted(schema, question);

        String sql;
        try {
            sql = chatClient.prompt()
                    .user(generatePrompt)
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText()
                    .strip();
            log.debug("[TextToSql] 生成的 SQL: {}", sql);
        } catch (Exception e) {
            log.error("[TextToSql] SQL生成失败: {}", e.getMessage());
            return "抱歉，SQL 生成过程中出现异常，请联系管理员。";
        }

        // ③ 执行 SQL（通过 Tool，Tool 内部做了安全校验只允许 SELECT）
        String queryResult;
        try {
            queryResult = sqlTool.executeQuery(sql);
        } catch (SecurityException se) {
            log.warn("[TextToSql] SQL安全检查拦截: {}", se.getMessage());
            return "您无权执行该查询，请联系管理员。";
        } catch (Exception e) {
            log.error("[TextToSql] SQL执行失败: {}", e.getMessage());
            return "查询执行失败：" + e.getMessage();
        }

        // ④ 将查询结果交给 LLM 整理成自然语言
        String formatPrompt = """
                根据以下查询结果，用简洁友好的方式回答用户的问题。如果结果为空，请如实告知。\

                用户问题：%s
                查询结果：%s
                """.formatted(question, queryResult);

        return chatClient.prompt()
                .user(formatPrompt)
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText();
    }
}
```

---

## 七、Controller 层

### 7.1 ChatController.java — 问答接口

```java
package com.example.spring_ai_language.controller;

import com.example.spring_ai_language.service.QuestionRouter;
import com.example.spring_ai_language.service.RagQueryService;
import com.example.spring_ai_language.service.TextToSqlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 智能问答接口
 * POST /api/chat/ask
 * Body: { "question": "...", "userId": "u001", "sessionId": "xxx", "knowledgeBaseId": "kb001" }
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")   // 允许前端跨域访问
public class ChatController {

    private final QuestionRouter router;
    private final RagQueryService ragQueryService;
    private final TextToSqlService textToSqlService;

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "").strip();
        String userId   = body.getOrDefault("userId",   "anonymous");
        String sessionId= body.getOrDefault("sessionId", java.util.UUID.randomUUID().toString());
        String kbId     = body.getOrDefault("knowledgeBaseId", "default");

        if (question.isBlank()) {
            return Map.of("code", 400, "message", "问题不能为空");
        }

        log.info("[请求] userId={} sessionId={} kbId={} question={}", userId, sessionId, kbId, question);

        // 路由：决定走 RAG 还是 Text-to-SQL
        QuestionRouter.Route route = router.route(question);
        String answer;

        if (route == QuestionRouter.Route.SQL) {
            answer = textToSqlService.answerWithSql(question, userId);
        } else {
            answer = ragQueryService.ask(kbId, userId, sessionId, question);
        }

        return Map.of(
                "code",    200,
                "message", "success",
                "data",    Map.of(
                        "answer",      answer,
                        "sessionId",   sessionId,
                        "mode",        route.name()   // 前端展示用：RAG 或 SQL
                )
        );
    }
}
```

### 7.2 DocumentController.java — 文档上传接口

```java
package com.example.spring_ai_language.controller;

import com.example.spring_ai_language.service.RagIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文档上传接口
 * POST /api/document/upload
 * Form: file + knowledgeBaseId + userId
 */
@Slf4j
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DocumentController {

    private final RagIngestionService ingestionService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("knowledgeBaseId") String knowledgeBaseId,
            @RequestParam("userId") String userId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "文件不能为空"));
        }

        try {
            ingestionService.ingest(file, knowledgeBaseId, userId);
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
}
```

---

## 八、Advisor 拦截器

Advisor 是 Spring AI 提供的拦截链机制，可以在每次 LLM 调用前后执行自定义逻辑。

### 8.1 LoggingAdvisor.java — 对话日志

```java
package com.example.spring_ai_language.advisor;

import com.example.spring_ai_language.entity.LogOperation;
import com.example.spring_ai_language.mapper.LogOperationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Advisor：每次对话结束后自动记录操作日志到 MySQL
 *
 * 注意：Spring AI 2.1.0-M1 的 ChatClientRequestSpec 没有 .context() 方法，
 * 无法在服务层注入 userId。本例从 HTTP 请求头 X-User-Id 读取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingAdvisor implements BaseAdvisor, Ordered {

    private final LogOperationMapper logMapper;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 从原始请求的 Prompt 中提取用户消息，存入 context 供 after() 使用
        List<Message> messages = request.prompt().getInstructions();
        String userInput = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .collect(Collectors.joining("\n"));
        if (userInput.length() > 200) {
            userInput = userInput.substring(0, 200) + "...";
        }
        Map<String, Object> ctx = new HashMap<>(request.context());
        ctx.put("__logging_start_ms", System.currentTimeMillis());
        ctx.put("__logging_user_input", userInput);
        return request.mutate().context(ctx).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        long startMs = (long) response.context().getOrDefault(
                "__logging_start_ms", System.currentTimeMillis());
        long costMs = System.currentTimeMillis() - startMs;

        // userId 从 HTTP 请求头 X-User-Id 读取
        String userId = extractUserId();

        // userInput 从 before() 存入的 context 中读取
        String userInput = (String) response.context().getOrDefault(
                "__logging_user_input", "");

        // 截取响应文本前 500 字符
        String answerText = response.chatResponse()
                .getResult().getOutput().getText();
        if (answerText.length() > 500) {
            answerText = answerText.substring(0, 500) + "...";
        }

        try {
            LogOperation logEntry = new LogOperation();
            logEntry.setUserId(userId);
            logEntry.setOperation("CHAT");
            logEntry.setRequest(userInput);
            logEntry.setResponse(answerText);
            logEntry.setCostMs((int) costMs);
            logEntry.setSuccess(true);
            logMapper.insert(logEntry);
        } catch (Exception e) {
            log.warn("[日志写入失败]", e);
        }
        return response;
    }

    /** 从当前 HTTP 请求中提取 userId，未登录时返回 anonymous */
    private String extractUserId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String uid = attrs.getRequest().getHeader("X-User-Id");
            if (uid != null && !uid.isBlank()) return uid;
        }
        return "anonymous";
    }

    @Override
    public int getOrder() { return 100; }

    @Override
    public String getName() { return "logging-advisor"; }
}
```

**关键 API 变更说明：**

| 旧版（已废弃） | Spring AI 2.1.0-M1 正确写法 |
|---|---|
| `implements Advisor` | `implements BaseAdvisor` |
| `CallTemplate.CallWrapper wrap(CallWrapper next)` | `before()` / `after()` 两个钩子方法 |
| `CallAroundAdvisor` | **不存在此接口，为笔误** |
| `prompt.getContext().getOrElse(...)` | 不存在；userId 通过 `RequestContextHolder` 读取 |
| `response.prompt().getInstructions()` | **不存在**；应从 `request.prompt()` 在 `before()` 中取值 |
| `response.getResult().getOutput().getText()` | `response.chatResponse().getResult().getOutput().getText()` |
| `.call().response()` | `.call().chatResponse()` |

> **关于 userId**：Spring AI 2.1.0-M1 的 `ChatClientRequestSpec` 接口**不提供 `.context(Map)` 方法**，无法在 Service 层注入。推荐做法是在 Controller 层将 userId 放入请求头（如 `X-User-Id`），Advisor 通过 `RequestContextHolder` 读取。

### 8.2 SensitiveWordAdvisor.java — 敏感词过滤

```java
package com.example.spring_ai_language.advisor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Advisor：对用户输入和模型输出做敏感词过滤
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordAdvisor implements BaseAdvisor, Ordered {

    private final Set<String> sensitiveWords;

    public SensitiveWordAdvisor(
            @Value("${app.sensitive-words.words:敏感词示例1,敏感词示例2}") String words) {
        this.sensitiveWords = new HashSet<>(List.of(words.split(",")));
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String input = extractInput(request);
        for (String word : sensitiveWords) {
            if (input.contains(word.strip())) {
                log.warn("[敏感词拦截] 用户输入包含敏感词: {}", word);
                Map<String, Object> ctx = new HashMap<>(request.context());
                ctx.put("__sensitive_blocked", true);
                return request.mutate().context(ctx).build();
            }
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (Boolean.TRUE.equals(response.context().get("__sensitive_blocked"))) {
            return response.mutate().chatResponse(safeBlockedResponse()).build();
        }
        String output = response.chatResponse()
                .getResult().getOutput().getText();
        for (String word : sensitiveWords) {
            if (output.contains(word.strip())) {
                log.warn("[敏感词拦截] 模型输出包含敏感词: {}", word);
                return response.mutate().chatResponse(safeBlockedResponse()).build();
            }
        }
        return response;
    }

    /** 构造一个"内容被屏蔽"的安全 ChatResponse */
    private ChatResponse safeBlockedResponse() {
        AssistantMessage blockedMsg = new AssistantMessage("您的消息包含不适当内容，已屏蔽。");
        return new ChatResponse(List.of(new Generation(blockedMsg)));
    }

    private String extractInput(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .collect(Collectors.joining("\n"));
    }

    @Override
    public int getOrder() { return 200; }

    @Override
    public String getName() { return "sensitive-word-advisor"; }
}
```

---

## 九、Tool 工具类

### 9.1 SqlTool.java — Text-to-SQL 执行工具

```java
package com.example.spring_ai_language.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Text-to-SQL Tool：LLM 通过 Function Calling 调用此类执行 SQL 查询
 * 只允许 SELECT 语句，防止恶意操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlTool {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 执行安全校验后的 SELECT 查询
     *
     * @param sql 自然语言转换来的 SQL（由 LLM 生成）
     * @return JSON 格式的查询结果
     */
    @Tool(description = "执行 SQL 查询并返回结果，仅允许 SELECT 语句")
    public String executeQuery(@ToolParam(description = "要执行的 SQL 语句，必须以 SELECT 开头") String sql) {
        String trimmed = sql.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT")) {
            log.warn("[SQL安全拦截] 非 SELECT 语句被拒绝: {}", sql);
            throw new SecurityException("只允许执行 SELECT 查询，拒绝其他操作");
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            return rows.isEmpty() ? "[]" : rows.toString();
        } catch (Exception e) {
            log.error("[SQL执行失败] sql={} err={}", sql, e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取关键业务表的字段结构（提供给 LLM 生成正确 SQL 使用）
     */
    @Tool(description = "获取数据库中指定表的字段结构信息（列名、类型、注释）")
    public String getTableInfo(@ToolParam(description = "表名，如：sales_order, employee 等") String tableName) {
        try {
            List<Map<String, Object>> cols = jdbcTemplate.queryForList(
                    "SHOW FULL COLUMNS FROM " + tableName);
            return cols.toString();
        } catch (Exception e) {
            return "{\"error\":\"获取表结构失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 列出所有业务表（给 LLM 了解有哪些表可用）
     */
    @Tool(description = "列出数据库中所有用户业务表（不含系统表）")
    public String listTables() {
        try {
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME, TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'");
            return tables.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
```

---

## 十、配置类

### 10.1 AiConfig.java — ChatClient 组装

```java
package com.example.spring_ai_language.config;

import com.example.spring_ai_language.advisor.LoggingAdvisor;
import com.example.spring_ai_language.advisor.SensitiveWordAdvisor;
import com.example.spring_ai_language.tools.SqlTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel,
                                  LoggingAdvisor loggingAdvisor,
                                  SensitiveWordAdvisor sensitiveWordAdvisor,
                                  SqlTool sqlTool) {
        return ChatClient.builder(chatModel)
                // 注册 Advisor（责任链，按 getOrder 升序执行）
                .defaultAdvisors(loggingAdvisor, sensitiveWordAdvisor)
                // 注册 Tool（LLM 可调用）
                .defaultTools(sqlTool)
                .build();
    }
}
```

### 10.2 MybatisPlusConfig.java — MyBatis Plus 分页插件

```java
package com.example.spring_ai_language.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor()); // 分页插件
        return interceptor;
    }
}
```

---

## 十一、主启动类

### 11.1 RAGApplication.java

```java
package com.example.spring_ai_language;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RAGApplication {

    public static void main(String[] args) {
        SpringApplication.run(RAGApplication.class, args);
    }
}
```

---

## 十二、启动 & 测试流程

### 步骤 1：替换 API Key

打开 `src/main/resources/application.yml`，把：
```yaml
api-key: sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```
改成你真实的 DeepSeek API Key。

### 步骤 2：启动应用

IDEA 中直接运行 `RAGApplication.main()`，或在命令行：

```cmd
cd D:\IDE\Spring_AI_Language
mvn spring-boot:run
```

看到 `Started RAGApplication in X seconds` 即表示成功。

### 步骤 3：curl 测试文档上传

```cmd
curl -X POST http://localhost:8080/api/document/upload ^
  -F "file=@D:\某份测试文档.pdf" ^
  -F "knowledgeBaseId=dept_a" ^
  -F "userId=user001"
```

### 步骤 4：curl 测试对话

```cmd
curl -X POST http://localhost:8080/api/chat/ask ^
  -H "Content-Type: application/json" ^
  -d "{\"question\":\"飞书请假怎么申请？\",\"userId\":\"user001\",\"knowledgeBaseId\":\"dept_a\"}"
```

### 步骤 5：打开前端页面

双击 `D:\IDE\Spring_AI_Language\src\main\resources\static\index.html` 在浏览器中打开。

前端目前请求的是 mock 数据（无后端），接入真实后端时把前端 `simulateResponse()` 函数里的 `fetch('/api/chat/ask', ...)` 取消注释即可。

---

## 十三、关键技术点说明

### 为什么 BOM 有时不生效？

Spring AI 的 BOM 通过 `<scope>import</scope>` 导入，但如果 Spring Boot 父 POM 版本过高（如 4.x），某些 starter 可能在 Maven 本地仓库未缓存导致拉不到。此时直接给每个依赖显式声明 `<version>${spring-ai.version}</version>` 更稳妥。

### 向量检索时如何实现知识库隔离？

在存入向量库时，每条 Document 的 metadata 中都带上 `kb_id`。检索时用 `SearchRequest.filterExpression("kb_id = 'xxx'")` 加过滤条件，这样不同部门之间数据完全隔离，互不可见。

### Advisor 的执行顺序

Advisor 按 `getOrder()` 返回值从小到大依次执行，相当于过滤器链：
1. `LoggingAdvisor`（order=100）→ 记录日志
2. `SensitiveWordAdvisor`（order=200）→ 过滤敏感词
3. 真正的 LLM 调用

### Text-to-SQL 的安全保障

`SqlTool.executeQuery()` 第一行就检查 SQL 是否以 `SELECT` 开头，不是直接抛 `SecurityException`，LLM 调用时会收到错误信息而非真正执行危险操作。

---

## 文件清单

```
src/main/java/com/example/spring_ai_language/
├── RAGApplication.java                          # 启动类
├── config/
│   ├── AiConfig.java                            # ChatClient + Advisor + Tool 装配
│   └── MybatisPlusConfig.java                   # 分页插件
├── controller/
│   ├── ChatController.java                      # 问答接口
│   └── DocumentController.java                  # 文档上传接口
├── entity/
│   ├── KbDocument.java                          # 文档元信息
│   ├── KbChunk.java                             # 文本切块
│   ├── ChatMessage.java                         # 对话消息
│   └── LogOperation.java                        # 操作日志
├── mapper/
│   ├── KbDocumentMapper.java
│   ├── KbChunkMapper.java
│   ├── ChatMessageMapper.java
│   ├── LogOperationMapper.java
│   └── resources/mapper/ChatMessageMapper.xml   # 自定义 SQL
├── service/
│   ├── AutoFillHandler.java                     # 自动填充时间戳
│   ├── TextChunk.java                           # 切块 DTO（record）
│   ├── IntelligentChunkService.java             # 智能分片
│   ├── RagIngestionService.java                 # 入库流水线
│   ├── RagQueryService.java                     # RAG 检索回答
│   ├── QuestionRouter.java                      # RAG vs SQL 路由
│   └── TextToSqlService.java                    # Text-to-SQL 编排
├── advisor/
│   ├── LoggingAdvisor.java                      # 对话日志记录
│   └── SensitiveWordAdvisor.java               # 敏感词过滤
└── tools/
    └── SqlTool.java                             # Text-to-SQL 执行工具

src/main/resources/
├── application.yml                              # 全局配置
├── mapper/ChatMessageMapper.xml                 # 自定义 SQL
└── static/index.html                            # 前端页面

sql/
└── init.sql                                     # 建表脚本（已由 Docker 自动执行）
```
