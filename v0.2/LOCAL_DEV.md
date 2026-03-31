# QuestionOS 本地开发指南

## 环境要求

- Python 3.11+
- Node.js 18+
- npm 9+

## 快速启动

### 方式一：一键启动（推荐）

```bash
cd /Users/xihe/Desktop/QuestionOS-v0.2
./start.sh
```

同时启动后端和前端，按 `Ctrl+C` 停止所有服务。

### 方式二：分别启动

```bash
# 终端 1 - 后端
cd /Users/xihe/Desktop/QuestionOS-v0.2
source venv/bin/activate
python server.py

# 终端 2 - 前端
cd frontend
npm run dev
```

- 后端：**http://localhost:8080**
- 前端：**http://localhost:3000**

## 环境变量（可选）

### 后端

`server.py` 从环境变量或 `.env` 读取；**API Key 等敏感信息不要写进代码仓库**。

| 变量 | 说明 | 默认值 |
|------|------|--------|
| DASHSCOPE_API_KEY | 阿里云百炼 API Key | （无，请在环境或 `.env` 中配置） |
| DASHSCOPE_BASE_URL | API 地址 | https://coding.dashscope.aliyuncs.com/v1 |
| JWT_SECRET | JWT 密钥 | 开发默认值 |
| RESEND_API_KEY | 邮箱验证（Resend） | （无，需要发信时再配置） |
| DASHSCOPE_IMAGE_KEY | 生图专用 Key（可选） | 未设置时回退到 DASHSCOPE_API_KEY / BAILIAN_API_KEY |
| FRONTEND_URL | 前端地址（验证链接） | http://localhost:3001 |

### 前端

`frontend/.env.local` 已配置：

```
NEXT_PUBLIC_API_URL=http://localhost:8080
```

## 数据存储

- **用户认证**：SQLite → `data/users.db`（自动创建）
- **会话**：内存存储（无 db 模块时）或数据库

## 常用命令

```bash
# 后端开发（当前无热重载，需手动重启）
python server.py

# 前端开发（热重载）
cd frontend && npm run dev

# 前端生产构建
cd frontend && npm run build && npm start
```
