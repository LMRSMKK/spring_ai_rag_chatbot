# Spring AI 企业知识库问答系统

基于 **Spring AI** 的企业级 RAG（检索增强生成）知识库对话系统，支持多模型切换、文档智能解析、向量语义检索、Text-to-SQL 业务数据查询，以及完整的会话管理和敏感词过滤。

---

## 目录

- [技术栈](#技术栈)
- [核心功能](#核心功能)
- [架构流程](#架构流程)
- [快速启动](#快速启动)
  - [前置条件](#前置条件)
  - [本地开发（推荐）](#本地开发推荐)
  - [Docker 部署](#docker-部署)
- [环境配置](#环境配置)
- [数据库初始化](#数据库初始化)
- [API 接口文档](#api-接口文档)
- [前端使用指南](#前端使用指南)
- [项目结构说明](#项目结构说明)
- [常见问题排查](#常见问题排查)

---

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.4.1 | 应用框架 |
| Spring AI | 1.0.3 | AI 能力集成 |
| MySQL | 8.0+ | 关系型数据存储 |
| Redis | 7.x | 会话缓存 & 历史消息缓存 |
| ChromaDB | latest | 向量数据库（语义检索） |
| MyBatis-Plus | 3.5.6 | ORM 持久层 |
| Hutool | - | 工具类库 |
| Lombok | - | 代码简化 |

---

## 核心功能

### 1. 多模型无缝切换
- **DeepSeek V3**：通过远程 API 调用（需配置 `DEEPSEEK_API_KEY`）
- **Agnes 2.5 Flash**：通过远程 API 调用（需配置 `AGNES_API_KEY`）
- **Ollama 本地模型**：`qwen2.5:3b-instruct`（聊天）+ `all-minilm:22m`（Embedding）
- 顶部工具栏可实时切换模型，当前会话保持连续

### 2. 知识库 RAG 问答
- 上传 PDF / DOCX / TXT / MD 等格式文档
- 自动切块 → Embedding 向量化 → 存入 ChromaDB
- 用户提问时进行语义相似度检索，返回 top-K 参考资料
- 按置信度分级回答策略：
  - **高置信度（≥0.75）**：严格引用资料作答，标注来源
  - **中置信度（0.4~0.75）**：混合模式，资料优先 + 通用知识补充
  - **低置信度（<0.4）**：仅提供通用知识，明确告知"未找到相关资料"

### 3. Text-to-SQL 业务数据查询
- 问题路由判断：启发式快筛关键词 → LLM 二次确认意图
- 将自然语言问题转为 SQL，在 MySQL 业务数据库中执行
- 结果以表格或文本形式呈现，附带查询耗时与 token 用量统计

### 4. 多轮对话与会话管理
- 新建 / 删除 / 清空记忆 / 分享会话
- 历史记录持久化到 MySQL，按时间分组展示
- 超长对话自动摘要压缩（超过 10 轮触发），保留近期原始上下文

### 5. 取消机制
- 发送后可点击「取消」按钮中止正在进行的请求
- 后端使用 `ConcurrentHashMap<String, Boolean>` 原子标记取消状态
- 取消后 DB 中标记为 `cancelled`，刷新页面不会恢复旧答案

### 6. 敏感词过滤
- 内置敏感词拦截（BLOCK / REPLACE 两种模式）
- 可在 `application.yml` 中自定义词列表

### 7. 操作日志
- 所有对话、上传、查询操作均有日志记录
- 支持分页查询与导出

---

## 架构流程

```
用户输入问题
     │
     ▼
┌─────────────────┐
│  启发式快筛      │── 命中关键词 ──→ 直接走 Text-to-SQL ──→ MySQL 直查
│  （正则/关键字）  │
└────────┬────────┘
         │ 未命中
         ▼
┌─────────────────┐
│  LLM 路由判断    │── SQL 意图 ──→ Text-to-SQL 路径
│  （QuestionRouter）│
└────────┬────────┘
         │ 知识问答
         ▼
┌─────────────────┐
│  ChromaDB 向量检索 │── 召回 top-K 相似片段
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  RAG 上下文拼接   │── 按置信度选择回答策略
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  LLM 生成最终回答  │
└────────┬────────┘
         │
         ▼
    用户界面展示
```

---

## 快速启动

### 前置条件

| 软件 | 最低版本 | 用途 |
|------|---------|------|
| JDK | 17 | 编译运行 |
| Maven | 3.8+ | 依赖管理 |
| MySQL | 8.0 | 业务数据存储 |
| Redis | 7.x | 缓存加速 |
| ChromaDB | latest | 向量存储 |
| Ollama | latest | 本地 Embedding（可选，用 DeepSeek/Agnes 则无需） |
| Docker + docker-compose | — | 一键部署方案 |

### 本地开发（推荐）

#### 第一步：启动基础服务

使用 Docker 启动 MySQL、Redis、ChromaDB（任选其一）：

```powershell
# 方式 A：手动启动各服务
docker run -d --name mysql_rag -e MYSQL_ROOT_PASSWORD=<your-mysql-password> -p 3306:3306 -v mysql_data:/var/lib/mysql mysql:8.0
docker run -d --name redis_rag -p 6379:6379 redis:7-alpine
docker run -d --name chroma_rag -p 8000:8000 -v chroma_data:/chroma/chroma chromadb/chroma
```

#### 第二步：初始化数据库

连接 MySQL，执行建表脚本：

```bash
mysql -u root -p'<your-mysql-password>' lmretext < sql/sql/init.sql
```

> 若已存在旧表，仅执行 ALTER 部分即可：
> ```sql
> ALTER TABLE chat_message 
>   ADD COLUMN ask_id VARCHAR(64) NULL AFTER cost_ms,
>   ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'pending' AFTER ask_id,
>   ADD INDEX idx_ask_id (ask_id);
> ```

#### 第三步：配置环境变量

在项目根目录创建 `.env` 文件（或直接修改 `application.yml`）：

```env
DB_PASSWORD=<your-mysql-password>
DEEPSEEK_API_KEY=sk-your-deepseek-key-here
AGNES_API_KEY=sk-your-agnes-key-here
```

或在 IDEA 的 Run Configuration 中添加 JVM 参数：
```
-DDB_PASSWORD=<your-mysql-password>
-DDEEPSEEK_API_KEY=sk-xxx
-DAGNES_API_KEY=sk-xxx
```

#### 第四步：启动 Ollama（如使用本地模型）

```powershell
# Windows
ollama pull qwen2.5:3b-instruct
ollama pull all-minilm:22m
ollama serve
```

#### 第五步：启动应用

```powershell
cd D:\IDE\Spring_AI_Language
mvn spring-boot:run
# 或使用 IntelliJ IDEA 直接运行 SpringAiLanguageApplication.java
```

访问 http://localhost:8080 开始使用。

### Docker 部署

项目提供完整的 Dockerfile 和 docker-compose.yml（已推送到仓库，但未同步到远程分支）。如需使用：

```powershell
# 构建并启动所有服务
cd D:\IDE\Spring_AI_Language
docker-compose up -d --build

# 查看日志
docker-compose logs -f app

# 关闭并清理
docker-compose down -v    # 删除数据卷（慎用）
docker-compose down       # 保留数据
```

默认端口映射：

| 服务 | 容器内端口 | 宿主机端口 |
|------|-----------|-----------|
| 应用 | 8080 | 8080 |
| MySQL | 3306 | 3306 |
| Redis | 6379 | 6379 |
| ChromaDB | 8000 | 8000 |

---

## 环境配置

### application.yml 关键配置

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/lmretext?...
    username: root
    password: ${DB_PASSWORD:}        # 从 .env 读取
  data:
    redis:
      host: localhost
      port: 6379

  ai:
    deepseek:
      chat:
        api-key: ${DEEPSEEK_API_KEY:}
        model: deepseek-chat
    agnes:
      chat:
        api-key: ${AGNES_API_KEY:}
        base-url: https://apihub.agnes-ai.com
        model: agnes-2.5-flash
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen2.5:3b-instruct
      embedding:
        model: all-minilm:22m

app:
  rag:
    high-confidence-threshold: 0.75   # ≥此值严格 RAG
    med-confidence-threshold: 0.4     # ≥此值混合回答
    top-k: 5                          # 检索返回条数
    chunk-size: 500                   # 文档切块大小
    chunk-overlap: 50                 # 切块重叠字符数
```

---

## 数据库初始化

首次部署执行 `sql/sql/init.sql`，会自动创建以下表：

| 表名 | 说明 |
|------|------|
| `kb_document` | 知识库文档元信息 |
| `kb_chunk` | 文档切块内容（用于重建原始文档） |
| `chat_message` | 对话消息记录（含 ask_id 关联、status 状态） |
| `log_operation` | 操作日志 |
| `sensitive_word` | 敏感词库 |

---

## API 接口文档

### 聊天问答 `/api/chat`

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| POST | `/api/chat/ask` | 发送问题获取答案 | `{ question, userId?, sessionId?, knowledgeBaseId?, model?, topK?, temp? }` |
| GET | `/api/chat/sessions` | 分页加载会话列表 | `?page=0&size=200` |
| POST | `/api/chat/new` | 创建新会话 | — |
| DELETE | `/api/chat/delete/{sessionId}` | 删除整个会话及所有消息 | — |
| GET | `/api/chat/messages/{sessionId}` | 加载会话历史（最近 N 条） | `?limit=50` |
| GET | `/api/chat/share/{sessionId}` | 获取分享视图内容 | — |
| POST | `/api/chat/cancel/{messageId}` | 取消当前正在处理的请求 | — |
| POST | `/api/chat/clear` | 清空当前会话记忆 | `{ sessionId }` |

### 文档管理 `/api/document`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/document/upload` | 上传文档（multipart/form-data，最大 50MB） |
| GET | `/api/document/knowledge-bases` | 列出所有知识库及统计 |
| POST | `/api/document/knowledge-base` | 创建新知识库 | `{ name, description? }` |
| DELETE | `/api/document/doc/{docId}` | 删除单个文档 |
| DELETE | `/api/document/doc-by-name` | 按文件名删除 |

### 日志查询 `/api/logs`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/logs` | 分页查询操作日志 | `?page=0&size=50&userId=&operation=` |

### Ask 请求示例

```json
{
  "question": "根据员工手册，差旅住宿标准是什么？",
  "userId": "u001",
  "sessionId": "abc-123-uuid",
  "knowledgeBaseId": "default_kb",
  "model": "deepseek",
  "topK": 5,
  "temp": 0.7
}
```

响应字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| answer | String | AI 生成的回答文本 |
| mode | String | `rag` / `sql` / `knowledge` / `rag_with_knowledge` / `cancelled` |
| relevance | Double | 检索相关度分数 |
| refCount | Int | 引用资料数量 |
| sources | List\<String\> | 参考来源列表 |
| costMs | Int | 总耗时（毫秒） |
| tokensIn / tokensOut | Int | 输入/输出 token 数 |
| messageId | String | 本轮唯一 ID（用于取消操作） |
| cancelled | Boolean | 是否被取消 |

---

## 前端使用指南

前端位于 `src/main/resources/static/index.html`，是一个单页应用，无需额外构建。

### 主要功能操作

1. **发送消息**：输入框输入后按 Enter 或点击发送
2. **取消回答**：AI 正在回答时出现「取消」按钮，点击即可终止
3. **切换模型**：右上角下拉选择 DeepSeek / Agnes / Ollama
4. **新建对话**：点击侧边栏「新建对话」按钮
5. **删除对话**：悬停会话条目，点击 × 图标
6. **清空记忆**：点击底部「清空记忆」按钮（保留会话但清除上下文）
7. **上传文档**：左侧「文档管理」面板支持拖拽上传

### 页面截图说明

- 左侧栏：会话列表 + 知识库管理
- 主区域：对话气泡流
- 右上角：模型选择器 + 上传按钮
- 消息下方：显示耗时、token 用量、引用资料数

---

## 项目结构说明

```
Spring_AI_Language/
├── src/main/java/com/example/spring_ai_language/
│   ├── SpringAiLanguageApplication.java   # 启动类
│   ├── advisor/                           # AI Advisor 链
│   │   ├── LoggingAdvisor.java            # 请求日志记录
│   │   └── SensitiveWordAdvisor.java      # 敏感词过滤
│   ├── config/                            # 配置类
│   │   ├── AiConfig.java                  # 多模型 ChatClient Bean
│   │   ├── ChromaConfig.java              # ChromaDB 配置
│   │   ├── MybatisPlusConfig.java         # MyBatis-Plus 配置
│   │   ├── RedisConfig.java               # Redis 配置
│   │   └── WebMvcConfig.java              # MVC 拦截器
│   ├── controller/                        # REST 控制器
│   │   ├── ChatController.java            # 对话核心接口
│   │   ├── DocumentController.java        # 文档管理接口
│   │   ├── LogController.java             # 日志查询接口
│   │   └── GlobalExceptionHandler.java    # 全局异常处理
│   ├── entity/                            # 实体类
│   │   ├── ChatMessage.java               # 对话消息
│   │   ├── KbChunk.java / KbDocument.java # 知识库文档/切片
│   │   ├── AskResult.java                 # 问答结果封装
│   │   └── LogOperation.java              # 操作日志
│   ├── mapper/                            # MyBatis Mapper
│   │   ├── ChatMessageMapper.java         # 含批量状态更新方法
│   │   ├── KbChunkMapper.java
│   │   ├── KbDocumentMapper.java
│   │   └── LogOperationMapper.java
│   ├── service/                           # 业务逻辑层
│   │   ├── RagQueryService.java           # RAG 问答核心（含缓存、摘要）
│   │   ├── TextToSqlService.java          # Text-to-SQL 查询
│   │   ├── QuestionRouter.java            # 意图路由（SQL vs RAG）
│   │   ├── RequestCancelService.java      # 取消状态管理
│   │   ├── ChatService.java               # 会话管理、历史加载
│   │   ├── RagIngestionService.java       # 文档解析与入库
│   │   ├── VectorScoreService.java        # 向量评分服务
│   │   └── IntelligentChunkService.java   # 智能切块
│   ├── tools/                             # 工具类
│   │   └── SqlTool.java                   # SQL 安全校验
│   └── util/                              # 工具包
│       ├── Result.java                    # 统一响应封装
│       └── SessionSummary.java            # 对话摘要
├── src/main/resources/
│   ├── static/index.html                  # 前端页面（单文件 SPA）
│   ├── application.yml                    # 主配置文件
│   └── mapper/*.xml                       # MyBatis XML（如有）
├── sql/sql/init.sql                       # 数据库初始化脚本
└── pom.xml                                # Maven 依赖声明
```

---

## 常见问题排查

### 1. 启动时报 `Unknown column 'ask_id' in field list`

**原因**：MySQL 表的 ALTER 语句未执行，缺少新列。  
**解决**：登录 MySQL，手动执行：

```sql
ALTER TABLE chat_message 
  ADD COLUMN ask_id VARCHAR(64) NULL AFTER cost_ms,
  ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'pending' AFTER ask_id,
  ADD INDEX idx_ask_id (ask_id);
```

### 2. 启动时报 `Access denied for user 'root'@'localhost'`

**原因**：密码错误或数据库未启动。  
**解决**：检查 `.env` 中的 `DB_PASSWORD` 与实际 MySQL 密码一致；确认 MySQL 容器正在运行。

### 3. 上传文档失败（进度卡在 PROCESSING）

**原因**：ChromaDB 或 Embedding 服务不可达。  
**解决**：
- 确认 `http://localhost:8000` 可访问（浏览器打开应返回 JSON）
- 确认 Ollama Embedding 模型已下载：`ollama pull all-minilm:22m`
- 查看应用日志中 `RagIngestionService` 相关错误

### 4. 问答响应很慢

**原因**：默认 DeepSeek/Agnes API 延迟较高（首 token 可能需要 5-15 秒）。  
**优化建议**：
- 调大 `max-file-size`（application.yml 中默认 50MB）
- 减少 `top-k`（降低向量检索开销）
- 切换至本地 Ollama 模型（免去网络传输）

### 5. 历史消息不显示 / 显示错乱

**原因**：数据库中大量记录的 `status` 仍为 `'pending'`（ALTER 时的默认值污染）。  
**解决**：

```sql
UPDATE chat_message SET status = 'answered' WHERE status = 'pending';
```

### 6. ChromaDB 集合不存在

**解决**：重启 ChromaDB 容器，或进入容器执行 `rm -rf /chroma/chroma` 后重新拉起，首次写入会自动创建集合。

---

## 许可证

本项目仅供学习与企业内部使用，请勿用于商业目的。
