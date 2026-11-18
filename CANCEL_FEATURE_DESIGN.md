# 取消问答功能设计方案（修订版）

## 一、需求描述

在 AI 正在生成回答时，用户可以主动打断并取消当前请求。取消后：
- 前端停止加载状态，不显示回答内容
- 后端不再继续处理已取消的请求（如可能）
- 用户可随时发起新的对话

---

## 二、核心思路

### 2.1 前端侧：请求取消机制

利用浏览器原生的 **AbortController** API，为每个发送的请求绑定一个控制器。当用户点击"取消"按钮时，调用 `controller.abort()`，该请求会立即中断，`fetch` 抛出 AbortError，前端捕获后清理 loading 状态即可。

**注意：** `AbortController` 只能取消"进行中"的 HTTP 请求（即网络层），无法取消后端已经接手的业务逻辑。如果希望彻底释放后端资源，需要配合后端支持"任务取消"信号。

### 2.2 后端侧：任务取消接口

在后端为每次问答请求分配一个唯一 ID（例如 UUID），并存入一个内存级 `ConcurrentHashMap<String, Boolean>`（键=messageId，值=isCancelled）。

- AI 处理线程在关键步骤（每条消息完成后、每次迭代前）检查 `isCancelled`，若为真则提前退出，返回取消标志。
- 提供一个 `/api/chat/cancel/{messageId}` 接口，前端取消时调用，后端将其标记为已取消。
- 任务结束后（无论成功还是被取消）由调用方负责从 Map 中移除记录，避免内存泄漏。

---

## 三、具体修改方案

### 3.1 新增数据模型

#### 3.1.1 新增 DTO：CancelRequest（可选）

新建 `src/main/java/com/example/spring_ai_language/dto/CancelRequest.java`：

```java
@Data
public class CancelRequest {
    private String messageId;  // 需要取消的任务ID
}
```

#### 3.1.2 修改 AskResult

在现有 `AskResult` 类中增加字段（需同时添加 `@Builder` 注解才能使用 builder 语法）：

```java
import lombok.Data;
import lombok.Builder;

@Data
@Builder   // ← 必须添加此注解
public class AskResult {
    private String answer;
    private long costMs;
    private int refCount;
    private boolean blocked;
    private String mode;
    private double relevance;
    private List<String> sources;
    private Integer tokensIn;
    private Integer tokensOut;
    private boolean cancelled;   // true 表示被用户主动取消
}
```

---

### 3.2 新增后端服务：RequestCancelService

新建 `src/main/java/com/example/spring_ai_language/service/RequestCancelService.java`：

**职责：** 维护"待取消任务"的状态表，提供三个方法：

| 方法 | 作用 |
|------|------|
| `register(String messageId)` | 请求开始时注册，向 Map 写入 false |
| `cancel(String messageId)` | 收到取消请求时原子性地置为 true；**不做 remove** |
| `shouldCancel(String messageId)` | 在任何关键节点前调用，若为 true 则退出 |
| `remove(String messageId)` | 任务结束时（无论成功/失败/取消）由调用方主动清理 |

**⚠️ 重要实现约束：cancel() 只做标记，不删除。**  
若在 cancel() 内部同时执行 replace + remove，存在竞态窗口：在两个操作之间，另一个线程调用 shouldCancel() 时 get() 返回 null，误判为未取消。正确做法是将两个动作分离，remove 延迟到任务完成时的 finally 块中统一执行。

**代码骨架如下：**

```java
@Service
public class RequestCancelService {
    private final ConcurrentHashMap<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    public void register(String messageId) {
        cancelFlags.put(messageId, false);
    }

    /**
     * 原子性地将标志置为 true，返回是否成功取消。
     * 注意：不执行 remove，由调用方在任务结束后统一清理。
     */
    public boolean cancel(String messageId) {
        // 原子替换：仅当当前值为 false 时才改为 true
        return cancelFlags.replace(messageId, false, true);
    }

    /**
     * 检查是否应取消，直接读取 map，不做任何删除操作。
     * 必须在 cancelFlags 中仍有记录时调用（即 register 之后、remove 之前）。
     */
    public boolean shouldCancel(String messageId) {
        Boolean flag = cancelFlags.get(messageId);
        return flag != null && flag;
    }

    /**
     * 任务结束（无论成功/异常/取消）后由调用方触发，释放占用的内存。
     */
    public void remove(String messageId) {
        cancelFlags.remove(messageId);
    }
}
```

---

### 3.3 修改 ChatController

#### 3.3.1 新增取消接口

在 `ChatController` 中添加新端点：

```
DELETE /api/chat/cancel/{messageId}
```

**处理逻辑：**
1. 调用 `requestCancelService.cancel(messageId)` 标记取消
2. 返回统一结果（不等待后端处理结束，只确认标记已设置）

**完整示例：**

```java
@DeleteMapping("/cancel/{messageId}")
public Result<?> cancel(@PathVariable String messageId) {
    boolean ok = requestCancelService.cancel(messageId);
    return Result.success(Map.of("cancelled", ok));
}
```

#### 3.3.2 修改现有 ask 接口

在 `/api/chat/ask` 方法的入口处，用 try-finally 包裹整个处理流程：

```java
String messageId = UUID.randomUUID().toString();
try {
    requestCancelService.register(messageId);
    
    // ... 原有路由和问答逻辑不变 ...
    
    Map<String, Object> data = new HashMap<>();
    data.put("answer",   askResult.getAnswer());
    data.put("sessionId", sessionId);
    data.put("mode",     askResult.getMode() != null ? askResult.getMode() : "");
    data.put("model",    model);
    data.put("costMs",   askResult.getCostMs());
    data.put("refCount", askResult.getRefCount());
    data.put("relevance", askResult.getRelevance());
    data.put("blocked",  askResult.isBlocked());
    data.put("messageId", messageId);          // ← 新增：供前端存储用于取消
    data.put("cancelled", askResult.isCancelled()); // ← 新增：告知前端本次是否被取消
    if (askResult.getTokensIn()  != null) data.put("tokensIn",  askResult.getTokensIn());
    if (askResult.getTokensOut() != null) data.put("tokensOut", askResult.getTokensOut());
    return Result.success(data);
    
} catch (Exception e) {
    log.error("[问答异常] messageId={} err={}", messageId, e.getMessage(), e);
    return Result.error(500, "服务异常：" + e.getMessage());
} finally {
    // 无论成功/异常/取消，均在此处清理 cancelFlag，防止内存泄漏
    requestCancelService.remove(messageId);
}
```

> **两种响应格式的约定：**
> - **正常完成**：`{ "code": 200, "data": { "messageId": "xxx", "cancelled": false, ... } }`
> - **被用户取消**：`{ "code": 200, "data": { "messageId": "xxx", "cancelled": true, "answer": "", ... } }`  
> 前端根据 `cancelled` 字段决定是否显示错误提示或友好 toast。

---

### 3.4 修改 AI 处理逻辑（各 Service 层）

#### 取消检查优先级说明

| 优先级 | 检查位置 | 原因 |
|--------|----------|------|
| ⭐⭐⭐ 最高 | `RagQueryService.ask()` 入口、向量检索前后 | 最耗时（向量计算+LLM调用），尽早放弃省资源 |
| ⭐⭐ 高 | `TextToSqlService.anwserWithSql()` SQL生成前后、SQL执行前后 | SQL生成是重 token 消耗，提前取消可节省成本 |
| ⭐ 低 | `QuestionRouter.route()` | 轻量 LLM 调用（几十毫秒），取消收益有限，放在最后 |

#### 标准模板（在以上各位置插入）

```java
// 在每个关键步骤前（建议放在方法入口和各主要阶段交界处）
if (requestCancelService.shouldCancel(messageId)) {
    log.info("[取消] messageId={} 用户主动中断，提前退出", messageId);
    return AskResult.builder()
            .cancelled(true)
            .answer("")
            .mode("cancelled")
            .build();
}
```

#### RagQueryService.ask() 改造示意

```java
public AskResult ask(String userId, String sessionId, String question, 
                     int topK, double temperature, String messageId) {
    // 【检查点 1】入口
    if (requestCancelService.shouldCancel(messageId)) return buildCancelledResult();

    // ① 向量检索 + 相关性评分
    List<ScoredDocument> scoredDocs = vectorScoreService.scoredSearch(question, topK);
    if (requestCancelService.shouldCancel(messageId)) return buildCancelledResult(); // 【检查点 2】

    // ② 按相关性选策略 → LLM 调用
    // ... 原有逻辑 ...
    if (requestCancelService.shouldCancel(messageId)) return buildCancelledResult(); // 【检查点 3】

    // ③ 落库
    saveAndReturn(sessionId, userId, question, answer, result.getCostMs(), ...);
    return result;
}
```

> **同理适用于 TextToSqlService 和 QuestionRouter。**  
> 注意：每次 `shouldCancel()` 返回 true 后，需在 `finally` 中调用 `requestCancelService.remove(messageId)`，确保 Map 及时释放。

---

### 3.5 前端修改

#### 3.5.1 引入 AbortController（含时序修正）

**⚠️ 关键时序要求：先调后端 cancel 接口，再 abort fetch。**  
若顺序颠倒（先 abort 再调 cancel），fetch 已进入 AbortError 路径，后续的 cancel 请求可能在 cancel service 的 check 尚未建立时就触发 finally remove，导致后端处理线程失去"被取消"的信号。

```javascript
let currentAbortController = null;  // 模块级变量
let currentMessageId = null;        // 保存本次请求的 messageId

async function callBackend(question) {
    // 若有上一个请求在进行，先中止它（防御性保护）
    if (currentAbortController) {
        currentAbortController.abort();
    }
    
    currentAbortController = new AbortController();
    const signal = currentAbortController.signal;

    try {
        const res = await fetch('/api/chat/ask', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question, ... }),
            signal
        });
        
        const data = await res.json();
        // 将后端返回的 messageId 保存到全局变量
        currentMessageId = data.data?.messageId ?? null;
        
        // ... 正常处理响应 ...
        
    } catch (err) {
        if (err.name === 'AbortError') {
            console.log('请求已被取消');
            return;  // 静默忽略，不报错
        }
        throw err;
    } finally {
        currentAbortController = null;
        // 注意：不在这里清除 currentMessageId，因为可能需要用于调试日志
    }
}
```

#### 3.5.2 添加取消按钮 UI

**位置：** 放在发送框右侧，与发送按钮并列，仅在 loading 状态下显示。

**HTML 结构建议：**

```html
<div class="input-actions">
    <button id="cancelBtn" style="display:none" onclick="cancelCurrentRequest()">
        ✕ 取消
    </button>
    <button id="sendBtn" onclick="sendMessage()">发送</button>
</div>
```

#### 3.5.3 实现取消函数（时序修正版）

```javascript
async function cancelCurrentRequest() {
    // 第1步：通知后端取消（必须 await，确保信号送达）
    if (currentMessageId) {
        try {
            await fetch(`/api/chat/cancel/${currentMessageId}`, { method: 'DELETE' });
        } catch (e) {
            // 即使后端调用失败，前端也要继续执行 abort
            console.warn('取消通知发送失败，但前端仍会中止请求', e);
        }
    }
    
    // 第2步：中止正在进行的 HTTP 请求（切断网络传输）
    if (currentAbortController) {
        currentAbortController.abort();
    }
    
    // 第3步：清理事件状态（立即执行，无需等待后端响应）
    isProcessing = false;
    document.getElementById('cancelBtn').style.display = 'none';
    document.getElementById('sendBtn').disabled = !document.getElementById('userInput').value.trim();
    
    // 第4步：移除 loading 气泡
    const tip = document.getElementById('typingIndicator');
    if (tip) tip.remove();
    
    currentMessageId = null;
    showToast('已取消本次问答', 'info');
}
```

#### 3.5.4 状态联动

在 `callBackend()` 开始时和结束时控制按钮可见性：

```javascript
// 请求开始前
document.getElementById('cancelBtn').style.display = 'inline-flex';
document.getElementById('sendBtn').disabled = true;

// 请求结束（finally 块或 success 处理完毕）
document.getElementById('cancelBtn').style.display = 'none';
document.getElementById('sendBtn').disabled = !document.getElementById('userInput').value.trim();
```

---

## 四、数据流总览

```
┌──────────────┐     POST /api/chat/ask      ┌─────────────────┐
│   前端       │ ──────────────────────────► │   ChatController │
│  sendMessage │                             │                  │
└──────────────┘                             └────────┬────────┘
                                                        │
                            ═══════════════════════════╧═══════════════════════════
                            │                                                        │
                    ┌────────▼────────┐                                      ┌────────▼────────┐
                    │  RequestCancel  │                                      │   AI 处理链路    │
                    │  Service        │                                      │                 │
                    │                 │◄──── DELETE /cancel/{id} ─────────── │  RagQueryService │
                    │  register(id)   │           （用户点取消）              │  TextToSqlService│
                    └────────┬────────┘                                      └─────────────────┘
                             │
                             ▼
                   shouldCancel(id)?  →  true → 提前退出 → finally 里 remove(id)
```

---

## 五、注意事项

1. **messageId 必须跨前后端传递**：后端在 `/api/chat/ask` 响应体中返回 `messageId`，前端保存至 `currentMessageId`，取消时携带
2. **取消是尽力而为**：HTTP 层面 `abort()` 一定能停掉网络传输；但后端业务逻辑能否及时退出取决于是否在关键步骤插入了 `shouldCancel()` 检查
3. **避免竞态问题**：`cancel()` 只做原子替换（replace），不做 remove；remove 统一由 finally 块负责，确保并发安全
4. **防内存泄漏**：必须在 every path（成功/异常/取消）的最终出口调用 `remove()`，建议在 Controller 的 `try-finally` 中统一处理
5. **cancel 调用必须 await**：前端 `cancelCurrentRequest()` 中必须先 await 后端 cancel API，再 abort fetch，否则时序颠倒可能导致取消信号丢失
6. **并发场景**：若同 sessionId 有多条 pending 请求，以最新的 messageId 为准，旧的自动失效（可在 requestCancelService 中增加优先级逻辑）
7. **AskResult 构造**：使用 `.builder()` 前需确认类上有 `@Builder` 注解；或使用 setter 链式调用代替

---

## 六、后续可优化的方向

- **SSE 流式输出配合取消**：前端通过 EventSource 接收实时片段，断开连接自然触发服务端流关闭，无需额外取消接口
- **Redis 持久化取消标志**：多实例部署时，用 Redis SET 代替内存 Map，支持水平扩展
- **取消后补偿提示**：若取消发生在 RAG 检索阶段（已消耗 token），可在 UI 上给出友好提示，告知用户部分资源已被消耗

---

## 七、并发多会话支持（Per-Session 锁）

### 7.1 当前问题

现在 `isProcessing` 是全局变量，一旦某个会话在请求中，其他所有会话都无法发送消息。

### 7.2 解决方案：改为 per-session 状态管理

将全局的 `let isProcessing = false` 改为按 sessionId 跟踪：

```javascript
// 模块级：用 Map 替代单一布尔值
const processingSessions = new Map(); // key=sessionId, value={abortController, messageId}
```

**判断是否可发送：**
```javascript
function canSend() {
    const sid = sessionId; // 当前激活会话的 ID
    return !processingSessions.has(sid);
}
```

**开始请求时注册：**
```javascript
processingSessions.set(sessionId, {
    abortController: controller,
    messageId: currentMessageId
});
```

**请求结束时清除：**
```javascript
processingSessions.delete(sessionId);
```

### 7.3 影响范围

| 位置 | 改动 |
|------|------|
| `isProcessing` 全局变量 | 替换为 `Map<sessionId, Info>` |
| `callBackend()` 开头/结尾 | 注册 / 删除 session |
| `cancelCurrentRequest()` | 从 Map 取出对应 controller 再 abort |
| `sendMessage()` 拦截逻辑 | 检查 `canSend()` 而非 `isProcessing` |
| 切换会话 `selectConv()` | 自动停止旧会话的 pending 请求（可选：保留历史） |

### 7.4 额外收益

- 不同会话可真正并发，互不阻塞
- 取消按钮按会话隔离，不会出现"A 会话取消把 B 会话的请求也中止了"的情况
- 切换会话时若有未完成的请求，UI 可以显示"该会话有未完成的消息，点击查看"的提示

---

## 八、切换回会话后保留未完成消息

### 8.1 问题根因

```
用户在 A 会话发消息
  → 前端乐观写入 DOM（用户气泡 + loading 气泡）
  → 后端正在处理（耗时数秒）
  → 用户切换到 B 会话
  → selectConv(B) 清空容器：DOM 里的 A 会话消息被 remove()
  → A 会话后端响应回来 → addMessage(ai回答) 写入 B 会话 → 串台！
```

### 8.2 核心设计原则：前端只是展示层，DB 才是事实来源

```
        ┌─────────────────────────────────────┐
        │           持久化层（MySQL）           │
        │   chat_message 表（user+assistant）   │
        └─────────────────────────────────────┘
                   ▲              ▲
                   │              │
            ①前端乐观写       ②后端事务性写
            （临时DOM）      （正式入库）
                   │              │
        ┌──────────┴──────┬──────┴──────────┐
        │  快速渲染给用户   │   保证刷新/切换不丢│
        └─────────────────┴─────────────────┘
```

### 8.3 具体方案

#### 方案 A：后端预先落库用户消息（推荐，改动最小）

在 `/api/chat/ask` 入口，路由完成后、LLM 调用前，先插入 user 消息：

```java
// ChatController.ask() 里，注册 messageId 后立即落库 user 消息
String messageId = UUID.randomUUID().toString();
requestCancelService.register(messageId);

// 提前写入 user 消息到 DB（此时 answer 为空，status='pending'）
chatMessageMapper.insertPending(userId, sessionId, question, messageId);

try {
    // ... 原有路由和问答逻辑不变 ...
    // LLM 完成后再更新同一条记录的 answer 字段
    chatMessageMapper.updateAnswer(messageId, answer, costMs, tokensIn, tokensOut, mode);
    return Result.success(data);
} catch (Exception e) {
    chatMessageMapper.markFailed(messageId, e.getMessage());
    return Result.error(...);
} finally {
    requestCancelService.remove(messageId);
}
```

前端 `selectConv(el)` 加载历史时，把 `status='pending'` 且还未有 assistant 回复的消息也渲染出来（带 loading 状态）：

```javascript
// selectConv 加载历史时
for (const m of data.data) {
    if (m.status === 'pending') {
        // 渲染用户消息 + loading 气泡，并继续轮询直到 answered
        renderPendingMessage(m);
        pollForResult(m.messageId);
    } else {
        addMessage(m.content, m.role, ...);
    }
}
```

#### 方案 B：前端 IndexedDB 本地缓存（离线可用，但复杂度高）

将每条 pending 消息存到浏览器的 IndexedDB，页面刷新或切换回来后从本地恢复：

```javascript
// 发送前存入本地 IndexedDB
await db.put('pending', { sessionId, messageId, role: 'user', content: question, ts: Date.now() });

// 切换会话返回时恢复
const pending = await db.where('sessionId').equals(currentSessionId).toArray();
pending.forEach(m => addMessage(m.content, m.role));
```

**对比：**

| 维度 | 方案A（预落库） | 方案B（IndexedDB） |
|------|----------------|-------------------|
| 实现难度 | 低，只需加 INSERT | 高，需引入IndexedDB封装 |
| 跨设备生效 | ✅（DB共享） | ❌（只在本机） |
| 防刷新丢失 | ✅ | ✅ |
| 防切换丢失 | ✅ | ✅ |
| 推荐场景 | 单实例部署首选 | 需要离线体验的场景 |

### 8.4 建议优先级

**第一步（立即做）**：方案A 的后端预落库 + `selectConv` 加载 pending 消息。改动集中在 ChatController + ChatMessageMapper，前端只需增加一个 `status='pending'` 的渲染分支。

---

## 九、已修复问题记录

| 版本 | 修复内容 | 涉及文件 |
|------|----------|---------|
| v6 | Redis 密码占位符导致启动失败，改为 `${REDIS_PASSWORD:}` 默认空值；DeepSeek/Agnes API Key 同样处理 | `application.yml` |
| v7 | Spring Boot 无法识别 `${VAR}` 环境变量占位符，引入 `dotenv-java` 库 | `pom.xml`, `SpringAiLanguageApplication.java` |
| v8 | `DotEnv` 类名错误（应为 `Dotenv`），重复依赖 `spring-boot-starter-data-redis`，移除 dotenv 改用 Spring Boot 原生支持 | `pom.xml`, `SpringAiLanguageApplication.java` |
| v9 | TextToSql 记忆查询生成错误 SQL（`WHERE user_id='当前用户ID'`），因不知道 sessionId；强化 Prompt 提示词防 LLM 废话输出；后置校验第一个词是否为 SELECT | `TextToSqlService.java` |
| v10 | 前端消息角色标签和顶部模型徽标硬编码为 DeepSeek V3，不跟随当前选中模型；回答完后徽标被 mode 标签覆盖 | `index.html` |
| v11 | 添加 WebMvcConfig 禁用静态资源缓存，确保前端修改实时生效 | `WebMvcConfig.java` |
| v12 | 修复 TextToSql 记忆查询：传递 sessionId 给 LLM 生成正确 SQL WHERE 条件；强化 Cancel Service 实现避免竞态 | `TextToSqlService.java`, `ChatController.java`, 新增 `RequestCancelService.java` |
| v13 | 实现完整取消功能：后端三段式 cancel 信号（AbortController + cancel接口 + shouldCancel检查点）；前端取消按钮及状态联动 | `index.html`, 全量重构 `RagQueryService`/`TextToSqlService`/`QuestionRouter` |

---

## 十、后续待优化方向

| 优先级 | 方向 | 说明 |
|--------|------|------|
| P0 | 方案A落地（预落库+pending渲染） | 解决切换会话丢消息的根本问题 |
| P0 | 并发多会话（per-session lock） | 将 `isProcessing` 改为 Map 结构 |
| P1 | SSE 流式输出 | 替代同步长请求，天然支持断开即取消 |
| P1 | Redis 持久化取消标志 | 支持多实例部署 |
| P2 | 取消后补偿提示 | 区分"刚取消"和"已完成"两种状态给用户反馈 |
