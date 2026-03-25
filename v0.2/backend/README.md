# QuestionOS Backend

QuestionOS 后端 API - 基于 FastAPI + PostgreSQL + 阿里云百炼 AI

## 技术栈

- **框架**: FastAPI
- **数据库**: PostgreSQL + SQLAlchemy
- **AI**: 阿里云百炼 API (OpenAI 兼容)
- **认证**: JWT + python-jose
- **模型**: qwen-max, qwen-plus, glm-5

## 快速开始

### 1. 安装依赖

```bash
cd questionos/backend
pip install -r requirements.txt
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 文件，配置数据库和 API Key
```

### 3. 运行服务

```bash
uvicorn app.main:app --reload
```

服务将在 `http://localhost:8000` 启动

### 4. 查看 API 文档

- Swagger UI: `http://localhost:8000/docs`
- ReDoc: `http://localhost:8000/redoc`

## 项目结构

```
app/
├── main.py              # FastAPI 应用入口
├── api/                 # API 路由
│   └── __init__.py
├── core/                # 核心配置
│   ├── __init__.py
│   ├── config.py        # 应用配置
│   └── database.py      # 数据库连接
├── models/              # SQLAlchemy 模型
│   ├── __init__.py
│   └── user.py          # User, Session, ConversationTurn, UserProfile
├── schemas/             # Pydantic 模型
│   ├── __init__.py
│   └── schemas.py       # 请求/响应模型
└── services/            # 业务逻辑
    ├── __init__.py
    └── ai_service.py    # AI 服务封装
```

## 数据模型

### User (用户)
- id, email, hashed_password, username, is_active, created_at, updated_at

### UserProfile (用户资料)
- id, user_id, display_name, avatar_url, bio
- preferred_model, system_prompt, preferences

### Session (对话会话)
- id, user_id, title, description
- model, system_prompt, is_active, meta_data

### ConversationTurn (对话轮次)
- id, session_id, role, content
- reasoning_content, tokens_used, latency_ms, meta_data

## API 端点

### 基础端点
- `GET /` - 欢迎信息
- `GET /health` - 健康检查

### 认证 (待实现)
- `POST /auth/login` - 登录
- `POST /auth/register` - 注册

### 用户 (待实现)
- `GET /users/me` - 获取当前用户
- `PUT /users/me` - 更新用户信息

### 会话 (待实现)
- `GET /sessions` - 获取会话列表
- `POST /sessions` - 创建会话
- `GET /sessions/{id}` - 获取会话详情
- `DELETE /sessions/{id}` - 删除会话

### 聊天 (待实现)
- `POST /chat` - 发送消息
- `POST /chat/stream` - 流式聊天

## 阿里云百炼配置

默认配置:
- Base URL: `https://coding.dashscope.aliyuncs.com/v1`
- 可用模型: `qwen-max`, `qwen-plus`, `glm-5`
- API Key: 在 `.env` 中配置 `BAILIAN_API_KEY`

## 开发计划

- [x] 项目结构搭建
- [x] 数据模型定义
- [x] AI 服务封装
- [ ] 认证系统 (JWT)
- [ ] API 路由实现
- [ ] WebSocket 支持
- [ ] 文件上传/附件
- [ ] 会话管理
- [ ] 流式响应
