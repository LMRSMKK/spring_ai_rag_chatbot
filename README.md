# Spring AI RAG 知识库问答系统

基于 Spring AI 的企业级 RAG（检索增强生成）知识库对话系统，集成大语言模型、向量数据库和业务数据智能查询。

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.4.1 |
| Spring AI | 1.0.3 |
| MySQL | 8.0 |
| Redis | 7 Alpine |
| ChromaDB | latest |
| DeepSeek | API 接入 |
| Ollama | 本地 Embedding |

## 功能特性

- **多模型支持**：DeepSeek / Agnes / Ollama 自由切换
- **RAG 知识问答**：文档上传 → 向量化 → 语义检索 → LLM 回答
- **Text-to-SQL 智能路由**：数据查询类问题自动走 SQL 路径
- **启发式快筛**：命中关键词直接走 SQL，避免无效向量检索
- **对话管理**：新建/删除/分享会话，历史记录持久化
- **敏感词过滤**：内置敏感内容拦截机制
- **操作日志**：完整记录用户交互行为

## 快速启动

### 前置条件

- Docker Desktop
- JDK 17+（本地开发需要）
- Maven 3.8+

### Docker 一键部署（推荐）

```powershell
cd D:\IDE\Spring_AI_Language
docker-compose up -d --build
```

服务全部启动后访问：`http://localhost:8080`

### 关闭服务

```powershell
docker-compose down          # 保留数据
docker-compose down -v       # 连数据卷一起删除
```

## .env 配置说明

项目根目录创建 `.env` 文件，填写以下内容：

```env
DB_PASSWORD=你的MySQL密码
REDIS_PASSWORD=你的Redis密码
DEEPSEEK_API_KEY=sk-你的DeepSeek密钥
AGNES_API_KEY=sk-你的Agnes密钥
```

## API 接口列表

### 聊天问答 `/api/chat`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/ask` | 发送问题并获取答案 |
| GET  | `/api/chat/sessions` | 分页加载会话列表 |
| POST | `/api/chat/new` | 创建新会话 |
| DELETE | `/api/chat/delete/{sessionId}` | 删除会话 |
| GET  | `/api/chat/messages/{sessionId}` | 加载会话历史消息 |
| GET  | `/api/chat/share/{sessionId}` | 获取分享会话内容 |
| POST | `/api/chat/share` | 生成分享链接 |
| POST | `/api/chat/clear` | 清空会话记忆 |

### 文档管理 `/api/document`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/document/upload` | 上传文档到知识库（最大15MB） |
| GET  | `/api/document/knowledge-bases` | 列出所有知识库及文档统计 |
| POST | `/api/document/knowledge-base` | 创建新知识库 |
| DELETE | `/api/document/doc/{docId}` | 按ID删除文档 |
| DELETE | `/api/document/doc-by-name` | 按文件名删除文档 |

### 日志查询 `/api/logs`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/logs` | 分页查询操作日志 |

### Ask 请求示例

```json
{
  "question": "查询销售额最高的前10名员工",
  "userId": "user001",
  "sessionId": "session-uuid",
  "knowledgeBaseId": "hr",
  "model": "deepseek",
  "topK": 5,
  "temp": 0.7
}
```

可用 model：`deepseek` / `agnes` / `ollama`

## 架构流程

```
用户提问
    │
    ▼
启发式快筛（关键词匹配）───── 命中 ──→ Text-to-SQL ──→ MySQL 直查
    │                                  │
    未命中                              │
    ▼                                  │
LLM 路由判断                          │
    │                                │
    ├─ SQL意图 ──→ Text-to-SQL ──────┘
    │
    └─ 知识问答 ──→ Chroma 向量检索 ──→ RAG 上下文拼接 ──→ LLM 生成回答
```

## 注意事项

- `.env` 和 `application.yml` 已加入 `.gitignore` 和 `.dockerignore`，不会提交到仓库
- 默认端口：应用 8080 / MySQL 3306 / Redis 6379 / Chroma 8000
- 如需修改端口，编辑 `docker-compose.yml` 中的映射规则即可
