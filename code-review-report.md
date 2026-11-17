# Spring_AI_Language 项目审计报告

> 分析日期：2026-11-16  
> 项目路径：`D:\IDE\Spring_AI_Language`

---

## 一、存在的安全问题（高优先级）

### 1. 敏感信息明文存储在配置文件中
**位置：** `src/main/resources/application.yml:38-44`

```yaml
spring:
  ai:
    deepseek:
      chat:
        api-key: sk-41e9458cbc974b84abc9ef4fce542212   # ⚠️ 明文 API Key
    agnes:
      chat:
        api-key: sk-WMdP4lIuEiANqWu3NXbg4wCMhGeRVBOrzFT4M6fT33pgU7Hq  # ⚠️ 明文 API Key
```
**风险：** API Key 提交到代码仓库，任何人可见。应改为环境变量或加密存储（如 Jasypt）。

### 2. SQL 注入风险（Chroma 过滤表达式拼接）
**位置：** `service/RagIngestionService.java:148-154`

```java
String safeKbId = kbId == null ? "" : kbId.replace("'", "''");
String safeDocId = String.valueOf(docId).replace("'", "''");
.filterExpression("kb_id = '" + safeKbId + "' AND doc_id = '" + safeDocId + "'")
```
虽然做了引号转义，但并非安全的参数化查询方式，建议改用 Chroma API 支持的元数据 filter 对象格式（如 `{"$and": [...]}`）而非字符串拼接。

### 3. getSchema() 中的 SQL 注入
**位置：** `tools/SqlTool.java:82`

```java
"SHOW CREATE TABLE `" + name + "`"
```
表名未经验证直接拼入 SQL，若 LLM 生成的表名含恶意字符则存在注入风险。

### 4. application.yml 中硬编码数据库密码
**位置：** `src/main/resources/application.yml:12`
```yaml
password: Lm312405@   # ⚠️ 明文密码
```

---

## 二、可以优化的地方

### 1. pom.xml 依赖排除过于冗长且重复
**位置：** `pom.xml:76-284`

每个 AI 相关 starter 都写了大量几乎相同的 exclusion 块（排除 spring-boot-starter-restclient / webclient / reactor 等），共计约 **150 行**。建议：
- 将 common exclusions 抽取为 Maven `<dependencyManagement>` 中的属性统一管理；
- 或确认这些排除是否仍然必要（部分可能在 Spring Boot 3.4.x 下已不再产生冲突）。

### 2. ChromaConfig 与 VectorScoreService 重复构造 RestClient
**位置：** `config/ChromaConfig.java:48-93` vs `service/VectorScoreService.java:35-42`

两者都用 `RestClient.create(baseUrl)` 创建实例。应统一从 ChromaApi bean 注入，避免双重初始化及 baseUrl 不一致的风险。

### 3. ChatController 返回类型不统一
**位置：** `controller/ChatController.java:47`

`ask()` 方法返回 `Map<String,Object>`（裸 Map），其他接口均返回 `Result<?>`。建议全部统一为 `Result<?>`，前端解析更一致。

### 4. RagQueryService 每次问答都重新拉全量历史
**位置：** `service/RagQueryService.java:129`

```java
List<ChatMessage> all = chatMessageMapper.selectAllBySession(sessionId, 9999);
```
- 全量加载 + 内存截断，大数据量会话性能差；
- 可加 Redis 缓存，key = `chat:history:{sessionId}`，TTL 1 小时，命中时免去 DB 查询。

### 5. SensitiveWordAdvisor 逐词 contains 匹配效率低
**位置：** `advisor/SensitiveWordAdvisor.java:40-48`

使用 `HashSet.contains()` 做子串匹配需要遍历整个输入文本多次（O(n × m)）。建议改为 Aho-Corasick 自动机，或使用正则一次扫描，敏感词较多时性能差异显著。

### 6. RagQueryService 日志级别不当
**位置：** `service/RagQueryService.java:113`

```java
log.info("[问答] user={} mode={} relevance={}", ...);
```
每次正常问答都打一条 INFO 日志，QPS 高时会产生海量日志噪音。建议改为 `log.debug` 或在应用层增加日志采样率控制。

### 7. SessionSummary.groupLabel 字段未被填充
**位置：** `Uitl/SessionSummary.java:12`

`groupLabel` 字段定义了但没有在 `listSessions()` 中赋值（分组标签放在外层 map 的 key 里了），属于死字段，可直接删除。

### 8. IntelligentChunkService 命名风格不一致
**位置：** `service/IntelligentChunkService.java:30,73`

方法名 `byParagraPhs` 大小写混乱（`Ph`），应为 `byParagraphs`。同一文件还有 `findBreadkPoint`（typo：应为 `findBreakPoint`，第 118 行）。

### 9. package 名称大小写不规范
**位置：** `Uitl/` → 应为 `util/`

Java 包名规范是全小写，`Uitl` 同时是拼写错误（应为 `Util`）。虽不影响运行，但不符合 Java 惯例。

### 10. 缺少全局异常处理器
整个项目没有 `@RestControllerAdvice`，各 Controller 内部 try-catch 自行处理错误，逻辑分散且不统一。建议增加全局异常类，集中处理 `IllegalArgumentException`、服务异常等，并统一响应格式。

### 11. QuestionRouter 未被实际调用
**位置：** `service/QuestionRouter.java`

路由服务存在，但 `ChatController.ask()` 使用的是启发式关键词判断（`isLikelySqlQuestion`），从未调用 `QuestionRouter.route()`。这个 Bean 是一个死代码，或者应该替换掉启发式判断。

### 12. LoggingAdvisor.before() 中有无效的条件分支
**位置：** `advisor/LoggingAdvisor.java:48-54`

```java
if (Boolean.TRUE.equals(request.context().get("__sensitive_blocked"))) {
    ServletRequestAttributes attrs = ...
    attrs.getRequest().setAttribute("__sensitive_blocked", true);
}
```
这段代码把 context 标记同步到 request attribute，但后续 `after()` 方法中没有读取 request attribute，逻辑上形成了孤立的写操作，无实际效果。

---

## 三、未实现或功能残缺

### 1. SqlTool.getTableInfo() / listTables() 有 @Tool 注解但实际未使用
**位置：** `tools/SqlTool.java:50-71`

这两个方法标注了 `@Tool`，但 `ChatClient` 构建时只注入了 `sqlTool`，LLM 实际执行 SQL 走的是 `TextToSqlService.anwserWithSql()` 里的硬编码 
prompt，Function Calling 的工具列表是否真的包含这两个工具取决于 Spring AI 是否能自动发现它们——建议确认并测试。

### 2. AutoFillHandler updateTime 字段未在所有 Entity 中使用
**位置：** `service/AutoFillHandler.java:22`

`updateTime` 只在 `KbDocument` 上定义了 `@TableField(fill = FieldFill.INSERT_UPDATE)`，而 `ChatMessage` 没有 `updateTime` 字段。若将来所有实体都需要更新时间戳，应确保字段定义完整。

### 3. 分享链接 URL 硬编码协议
**位置：** `service/ChatService.java:148`

```java
String url = "https://" + getHost() + "/share?s=" + shareToken;
```
当用户通过 HTTP 访问时也会生成 HTTPS URL。应动态获取协议头（`request.getScheme()`）或允许通过配置指定。

### 4. TextToSqlService 的两阶段 LLM 调用无法获取 token 用量
**位置：** `service/TextToSqlService.java:44-87`

`anwserWithSql()` 直接调 `.call().chatResponse()`，返回的是纯文本，但 `AskResult.tokensIn/tokensOut` 在此处均为 `null`（见 `ChatController.java:77`）。RAG 路径有 token 统计，SQL 路径没有，数据不完整。

### 5. 没有数据库连接池监控 / 健康检查
`application.yml` 配置了 MySQL + Redis + ChromaDB 三个外部依赖，但未配置 Spring Boot Actuator Health Indicator，部署后无法快速诊断哪个下游服务不可用。

### 6. 缺少对大文件的上传限制校验
`max-file-size: 50MB` 已配置，但 `DocumentController.upload()` 没有在前端校验之外再做服务端文件体积判断，超大文件会在 Tika 解析中途才报错，浪费资源。

---

## 四、冗余代码

| 位置 | 说明 |
|------|------|
| `service/QuestionRouter.java` | 整个类未被任何 Controller 调用，是死代码（除非有明确计划用于替代 `isLikelySqlQuestion`） |
| `mapper/ChatMessageMapper.java:14-18` | `selectRecent()` 方法定义但全篇无任何调用方，可删除 |
| `mapper/ChatMessageMapper.java:30-32` | `countSessionList()` 同理，无任何调用 |
| `service/RagIngestionService.java:132-138` | `getChunksByDocId()` 仅用于调试，生产逻辑未见调用，可根据需要移除或保留注释 |
| `entity/ScoredDocument.java` | 若只用 `sd.getDocument()` / `sd.getScore()`，考虑直接返回 `Record<Document, Double>` 或使用现有 `Document` 扩展 metadata，减少中间 DTO |

---

## 五、总结优先级建议

| 优先级 | 类别 | 事项 |
|--------|------|------|
| 🔴 P0 | 安全 | 将 API Key / DB 密码移出配置文件，改为环境变量 |
| 🔴 P0 | 安全 | Chroma filterExpression 改用水文档元数据过滤器（非字符串拼接） |
| 🟡 P1 | 优化 | 统一 Controller 返回值类型为 `Result<?>` |
| 🟡 P1 | 优化 | 添加全局异常处理器 `@RestControllerAdvice` |
| 🟡 P1 | 未实现 | Token 统计覆盖 SQL 路径（`TextToSqlService`） |
| 🟡 P1 | 未实现 | 修复分享链接协议问题（支持 HTTP） |
| 🟢 P2 | 优化 | 清理 `QuestionRouter` / `selectRecent` / `countSessionList` 死代码 |
| 🟢 P2 | 优化 | 修复拼写错误：`byParagraPhs`→`byParagraphs`，`findBreadkPoint`→`findBreakPoint` |
| 🟢 P2 | 优化 | 删除 `SessionSummary.groupLabel` 无用字段 |
| 🟢 P3 | 优化 | 降低 RAG 问答 INFO 日志频率（改 debug 或加采样） |
| 🟢 P3 | 优化 | pom.xml 依赖排除块精简 |
