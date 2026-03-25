# QuestionOS

**认知协同 Agent - 问题校准器**

> 在行动之前，先校准问题。

## 项目简介

QuestionOS 是一个认知协同 Agent，通过多轮结构化对话帮助用户校准问题本质，而非提供解决方案。

**核心理念**：
- 错误的问题比错误的答案更危险
- 清晰度 > 速度
- 校准 > 行动
- 结构 > 情绪

## 技术栈

### 前端
- Next.js 14 + TypeScript
- Tailwind CSS
- Zustand (状态管理)
- Axios (HTTP请求)

### 后端
- Python FastAPI
- SQLAlchemy + PostgreSQL
- 阿里云百炼 AI (qwen-plus)

## 项目结构

```
questionos/
├── frontend/           # Next.js 前端
│   ├── app/           # App Router
│   ├── components/    # React 组件
│   ├── lib/           # 工具库
│   └── types/         # TypeScript 类型
├── backend/           # FastAPI 后端
│   ├── app/
│   │   ├── api/       # API 路由
│   │   ├── models/    # 数据模型
│   │   ├── schemas/   # Pydantic 模型
│   │   ├── services/  # 业务逻辑
│   │   └── prompts/   # AI Prompt模板
│   └── init_db.sql    # 数据库初始化
└── docs/              # 文档
    └── DEV_PLAN.md    # 开发计划
```

## 快速开始

### 前置要求
- Node.js 18+
- Python 3.11+
- PostgreSQL 14+

### 1. 克隆项目

```bash
cd questionos
```

### 2. 启动数据库

```bash
# macOS (Homebrew)
brew services start postgresql@14

# 创建数据库
createdb questionos

# 初始化表结构
psql -d questionos -f backend/init_db.sql
```

### 3. 启动后端

```bash
cd backend

# 创建虚拟环境
python -m venv venv
source venv/bin/activate

# 安装依赖
pip install -r requirements.txt

# 配置环境变量
cp .env.example .env
# 编辑 .env 填入实际值

# 启动服务
uvicorn app.main:app --reload
```

后端运行在 http://localhost:8000

### 4. 启动前端

```bash
cd frontend

# 安装依赖
npm install

# 配置环境变量
cp .env.example .env.local

# 启动开发服务器
npm run dev
```

前端运行在 http://localhost:3000

## 核心功能

### MVP 主链路
- [x] 邮箱注册/登录
- [x] 问题输入首页
- [x] 多轮校准对话
- [x] 基础校准报告
- [x] 历史会话列表

### AI 对话流程
1. 用户输入问题
2. AI 分析问题结构（类型、变量、偏差）
3. AI 生成追问（不超过3个）
4. 用户回答追问
5. AI 更新清晰度评分
6. 达到阈值后生成校准报告

## API 文档

启动后端后访问：
- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc

## 环境变量

### 后端 (.env)
```
DATABASE_URL=postgresql://postgres:password@localhost:5432/questionos
SECRET_KEY=your-secret-key
BAILIAN_API_KEY=your-api-key
BAILIAN_BASE_URL=https://coding.dashscope.aliyuncs.com/v1
BAILIAN_MODEL=qwen-plus
```

### 前端 (.env.local)
```
NEXT_PUBLIC_API_URL=http://localhost:8000
```

## 开发状态

**当前阶段**: 项目初始化

**开发进度**:
- [x] 项目结构创建
- [x] 数据库设计
- [x] AI Prompt模板
- [ ] 后端API实现
- [ ] 前端页面实现
- [ ] 联调测试

## License

MIT