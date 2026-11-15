# 前端模式显示 + SQL快筛修复指南

> 日期：2026-11-15  
> 问题：①前端始终显示"RAG检索"，不同模式无区分；②Text-to-SQL关键词匹配过窄导致数据库查询功能失效

---

## 一、问题现象

### 1.1 前端模式标签始终不变

所有问答（无论知识库有/无相关文档）在气泡底部都显示 `🔍 RAG 检索`，无法区分三种回答来源：

| 实际模式 | 日志 mode | 应显示的文字 |
|---------|----------|------------|
| strict RAG | `rag` | 🔍 RAG 检索 · 相关度 0.89 |
| 混合回答 | `rag_with_knowledge` | 🧩 混合回答（资料+通用知识） |
| 纯通用知识 | `knowledge` | 🧠 通用知识回答 |
| 数据库查询 | `sql` | 🗄 Text-to-SQL |

**根因：** `application.yml` 的阈值配置不合理，所有问题的相关性分数都落在 `0.4~0.7` 区间，永远命中"中置信度 → 混合模式"分支。

---

### 1.2 SQL查询走不通

测试问题：`帮我看看厦门有哪些航班` / `查询北京起飞的航班`

预期：命中 `isLikelySqlQuestion()` → 走 Text-to-SQL 路径  
实际：走 RAG 路径，返回"参考资料中没有相关信息"

**根因：** `ChatController.isLikelySqlQuestion()` 的条件词太窄，缺少 `"航班"`、`"员工"`、`"订单"`、`"请假"` 等业务名词，条件B永远为 false。

---

## 二、修复方案

### 2.1 调高 RAG 严格模式的门槛（application.yml）

文件：`src/main/resources/application.yml`

```yaml
app:
  rag:
    chunk-size: 500
    chunk-overlap: 50
    # 提高严格 RAG 阈值，让真正相关的文档更容易触发严格模式
    high-confidence-threshold: 0.75    # ← 原值 0.7，提高到 0.75
    med-confidence-threshold: 0.4      # ← 维持不变
    min-top-k: 5
    top-k: 5
    rerank-top-k: 10
```

**说明：**
- 当前日志里所有 relevance 稳定在 `0.68~0.73`，正好卡在 0.7 边界
- 提高到 0.75 后，知识库高度相关的内容进入 `strict RAG` 模式（直接引用资料回答）
- 不相关内容 relevance 通常 < 0.4，进入 `knowledge` 模式（模型自由发挥）

---

### 2.2 扩展 SQL 快筛关键词（ChatController.java）

文件：`src/main/java/.../controller/ChatController.java`

找到 `isLikelySqlQuestion` 方法（约第 146 行），替换为：

```java
/**
 * 启发式快筛：问题包含明确的数据查询意图时，直接走 Text-to-SQL 路径
 * 避免对这类问题先做无意义的向量检索
 */
private boolean isLikelySqlQuestion(String q) {
    if (q == null || q.isBlank()) return false;

    // 条件A：有查询/统计意图
    boolean hasQueryIntent = q.matches(".*[查查查找问问看看].*")
            || q.contains("多少") || q.contains("统计")
            || q.contains("总数") || q.contains("哪些")
            || q.contains("排名") || q.contains("趋势");

    // 条件B：常见业务名词（根据实际数据库表字段补充）
    boolean hasBizKeyword = q.contains("航班") || q.contains("机票")
            || q.contains("员工") || q.contains("部门")
            || q.contains("订单") || q.contains("销售额")
            || q.contains("销售") || q.contains("库存")
            || q.contains("工资") || q.contains("报销")
            || q.contains("请假") || q.contains("考勤")
            || q.contains("业绩") || q.contains("客户")
            || q.contains("合同") || q.contains("项目")
            || q.contains("记录") || q.contains("数据")
            || q.contains("人数") || q.contains("金额");

    return hasQueryIntent && hasBizKeyword;
}
```

**补充说明：** 如果之后发现还有漏网的 SQL 类问题，继续在 `hasBizKeyword` 里追加词即可。

---

### 2.3 前端根据 mode 动态展示（index.html）

#### （1）加一个辅助函数（放在 `<script>` 顶部任意位置）

```javascript
// 根据后端返回的 mode 和 relevance 生成显示文字
function getModeLabel(mode, relevance) {
  if (!mode) return '🔍 RAG 检索';
  switch (mode) {
    case 'sql':
      return '🗄 Text-to-SQL';
    case 'knowledge':
      return '🧠 通用知识回答';
    case 'rag_with_knowledge':
      return '🧩 混合回答';
    case 'rag':
      return '🔍 RAG 检索 · 相关度 ' + relevance.toFixed(2);
    default:
      return '🔍 RAG 检索';
  }
}
```

#### （2）更新 `callBackend` 成功分支

把原来的：
```javascript
addMessage(answer, 'ai', srcArr, null, costMs, refCount, mode, relevance);
```

改为（在 addMessage 调用之后，补一行更新顶部状态栏）：
```javascript
addMessage(answer, 'ai', srcArr, null, costMs, refCount, mode, relevance);
// 更新顶部 mode badge
const badge = document.querySelector('.model-badge');
if (badge) badge.textContent = getModeLabel(mode, relevance);
currentMode = mode;
```

#### （3）`addMessage` 模板行微调

原来第 943 行的 meta 行已经正确读取了 `mode` 变量，无需改动。确认该行存在：
```javascript
${role === 'ai' ? `<div class="msg-meta">
  <span>⏱ 耗时 ${(displayCostMs/1000).toFixed(1)}s</span>
  ${displayRefCount > 0 ? '<span>📄 引用 ' + displayRefCount + ' 条资料</span>' : ''}
  ${mode === 'knowledge' ? '<span>🧠 通用知识回答</span>' : 
    mode === 'sql' ? '<span>🗄 Text-to-SQL</span>' : 
    mode === 'rag_with_knowledge' ? '<span>🧩 混合回答(RAG+知识)</span>' : 
    `<span>🔍 RAG 检索</span>`}${displayRelevance ? ' · 相关度 ' + displayRelevance : ''}
</div>` : ''}
```

---

## 三、验证方式

启动应用后，依次发送以下问题并观察：

| 测试问题 | 预期模式 | 预期显示 |
|---------|---------|---------|
| `Git pull 是什么意思` | knowledge | 🧠 通用知识回答 |
| `什么是设计模式` | rag_with_knowledge | 🧩 混合回答 |
| `Linux命令有哪些` | rag / rag_with_knowledge | 🔍 RAG 检索（带相关度） |
| `帮我查一下有多少员工` | sql | 🗄 Text-to-SQL |
| `查一下北京的航班有哪些` | sql | 🗄 Text-to-SQL |
| `帮我看看厦门到上海的航班` | sql | 🗄 Text-to-SQL |

同时观察顶部 `model-badge` 区域文字是否随每次提问变化。

---

## 四、架构全景（改后）

```
用户提问
   │
   ├─ isLikelySqlQuestion() 命中？
   │      ↓ YES                     ↓ NO
   │  TextToSqlService          RagQueryService.ask()
   │  ┌─────────────┐           ┌──────────────────┐
   │  │ 生成SQL →执行→整理答案 │  embeddingModel.embed(query)
   │  └─────────────┘           ┌↓相似度score        │
   │  mode = "sql"              │ score ≥ 0.75 → buildStrictPrompt()  │
   │                            │ 0.4~0.75  → buildMixedPrompt()    │
   │                            │ score < 0.4  → buildOpenPrompt()  │
   │                            └──────────────────┘
   │                                      │
   ▼                                      ▼
frontend.getModeLabel()  →  展示对应图标+文字
```
