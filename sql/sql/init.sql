-- 初始化 SQL：创建核心表
-- MySQL 启动时会自动执行此文件

-- 知识库文档表
CREATE TABLE IF NOT EXISTS kb_document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name   VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    file_type   VARCHAR(50)   NOT NULL COMMENT '文件类型: pdf/docx/txt/md',
    file_size   BIGINT        NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    knowledge_base_id VARCHAR(64) NOT NULL COMMENT '所属知识库ID',
    uploaded_by VARCHAR(64)   NOT NULL COMMENT '上传者userId',
    status      VARCHAR(20)   NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/COMPLETED/FAILED',
    error_msg   TEXT          COMMENT '失败原因',
    upload_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kb_id (knowledge_base_id),
    INDEX idx_uploaded_by (uploaded_by),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

-- 文本切块表
CREATE TABLE IF NOT EXISTS kb_chunk (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id          BIGINT        NOT NULL COMMENT '关联文档id',
    chunk_id        VARCHAR(64)   NOT NULL COMMENT '向量库中的唯一id',
    content         MEDIUMTEXT    NOT NULL COMMENT '切块文本内容',
    knowledge_base_id VARCHAR(64) NOT NULL COMMENT '所属知识库ID（冗余，便于查询）',
    chunk_index     INT           NOT NULL DEFAULT 0 COMMENT '在文档中的顺序',
    word_count      INT           NOT NULL DEFAULT 0 COMMENT '字符数',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc_id (doc_id),
    INDEX idx_chunk_id (chunk_id),
    INDEX idx_kb_id (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文本切块表';

-- 多轮对话消息表
CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)   NOT NULL COMMENT '会话ID（UUID）',
    user_id     VARCHAR(64)   NOT NULL COMMENT '用户ID',
    role        VARCHAR(10)   NOT NULL COMMENT 'user / assistant / system',
    content     MEDIUMTEXT    NOT NULL COMMENT '消息内容',
    model       VARCHAR(64)   COMMENT '使用的模型名称',
    tokens_in   INT           COMMENT '输入token数',
    tokens_out  INT           COMMENT '输出token数',
    cost_ms     INT           COMMENT '耗时毫秒',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    INDEX idx_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表';

-- 操作日志表
CREATE TABLE IF NOT EXISTS log_operation (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(64)   NOT NULL COMMENT '操作用户',
    operation   VARCHAR(50)   NOT NULL COMMENT '操作类型: CHAT/UPLOAD/QUERY',
    target      VARCHAR(255)  COMMENT '操作目标（如知识库ID、文档名）',
    request     TEXT          COMMENT '请求摘要',
    response    TEXT          COMMENT '响应摘要',
    cost_ms     INT           DEFAULT 0 COMMENT '耗时毫秒',
    success     TINYINT(1)    DEFAULT 1 COMMENT '是否成功',
    error_msg   TEXT          COMMENT '错误信息',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_time (user_id, create_time),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';

-- 敏感词表
CREATE TABLE IF NOT EXISTS sensitive_word (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    word        VARCHAR(100)  NOT NULL COMMENT '敏感词',
    level       VARCHAR(20)   NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK=直接拦截 REPLACE=替换为***',
    replace_str VARCHAR(50)   COMMENT '替换内容（level=REPLACE时有效）',
    enable      TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_word (word)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='敏感词表';

-- 初始插入一些测试敏感词
INSERT IGNORE INTO sensitive_word (word, level, replace_str) VALUES
('敏感词示例1', 'BLOCK', ''),
('敏感词示例2', 'BLOCK', '');
