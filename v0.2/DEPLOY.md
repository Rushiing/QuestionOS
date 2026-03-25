# QuestionOS 部署文档

> 给人类开发者的部署指南

---

## 一、项目简介

**QuestionOS** 是一个认知协同 Agent 系统，提供两种模式：

| 模式 | 功能 | 说明 |
|------|------|------|
| 🔍 思维校准 | 帮你理清问题，不给答案 | 单 Agent 对话 |
| ⚔️ 沙盘推演 | 修罗场压力测试，炼化决策 | 多 Agent 博弈 |

**技术栈**：
- 前端：Next.js 14 + TypeScript + Tailwind CSS
- 后端：Python 3 + FastAPI
- AI：阿里云百炼 API

---

## 二、环境要求

### 必需

| 软件 | 版本 | 检查命令 |
|------|------|---------|
| Python | 3.11+ | `python3 --version` |
| Node.js | 18+ | `node --version` |
| npm | 9+ | `npm --version` |

### 可选（生产环境）

| 软件 | 用途 |
|------|------|
| PostgreSQL 14+ | 用户数据持久化 |
| Nginx | 反向代理 |
| Redis | 会话缓存 |

---

## 三、获取代码

### 方式一：解压打包文件

```bash
# 解压到目标目录
tar -xzf QuestionOS-v0.2.tar.gz
cd QuestionOS
```

### 方式二：Git Clone

```bash
git clone <repository-url>
cd QuestionOS
git checkout v0.2
```

---

## 四、后端部署

### 4.1 创建虚拟环境

```bash
cd QuestionOS

# 创建虚拟环境
python3 -m venv backend/venv

# 激活虚拟环境
# macOS/Linux:
source backend/venv/bin/activate
# Windows:
# backend\venv\Scripts\activate
```

### 4.2 安装依赖

```bash
pip install -r requirements.txt
```

**依赖清单** (`requirements.txt`):
```
fastapi>=0.109.0
uvicorn>=0.27.0
httpx>=0.26.0
pydantic>=2.5.0
openai>=1.10.0
```

### 4.3 配置环境变量

创建 `backend/.env` 文件：

```bash
# 复制示例配置
cp backend/.env.example backend/.env
```

编辑 `backend/.env`：

```env
# 阿里云百炼 API（必需）
BAILIAN_API_KEY=your_api_key_here
BAILIAN_BASE_URL=https://coding.dashscope.aliyuncs.com/v1

# JWT 密钥（生产环境必需）
SECRET_KEY=your_random_secret_key_here

# 数据库（v0.3+ 需要）
# DATABASE_URL=postgresql://user:password@localhost:5432/questionos
```

**获取百炼 API Key**：
1. 访问 [阿里云百炼](https://bailian.console.aliyun.com/)
2. 创建应用，获取 API Key
3. 填入 `BAILIAN_API_KEY`

### 4.4 启动后端

**开发环境**：

```bash
# 方式一：统一入口（推荐）
python3 server.py

# 方式二：直接启动 FastAPI
cd backend
uvicorn app.main:app --reload --port 8080
```

**生产环境**：

```bash
# 使用 gunicorn + uvicorn worker
gunicorn server:app -w 4 -k uvicorn.workers.UvicornWorker -b 0.0.0.0:8080
```

### 4.5 验证后端

```bash
# 健康检查
curl http://localhost:8080/health

# 预期返回
{"status":"healthy"}

# 查看 API 文档
# 浏览器打开 http://localhost:8080/docs
```

---

## 五、前端部署

### 5.1 安装依赖

```bash
cd QuestionOS/frontend
npm install
```

### 5.2 配置环境变量

创建 `frontend/.env.local`：

```env
# 后端 API 地址
NEXT_PUBLIC_API_URL=http://localhost:8080
```

**生产环境**：

```env
NEXT_PUBLIC_API_URL=https://your-api-domain.com
```

### 5.3 开发模式

```bash
npm run dev

# 访问 http://localhost:3000
```

### 5.4 生产构建

```bash
# 构建
npm run build

# 启动
npm start

# 访问 http://localhost:3000
```

### 5.5 静态导出（可选）

```bash
# 修改 next.config.js
# output: 'export'

npm run build

# 产物在 frontend/out/ 目录
# 可用任意静态服务器托管
```

---

## 六、端口说明

| 服务 | 默认端口 | 说明 |
|------|---------|------|
| 前端 | 3000 | Next.js 开发服务器 |
| 后端 | 8080 | FastAPI 服务 |
| PostgreSQL | 5432 | 数据库（v0.3+） |

**防火墙配置**：
- 开发环境：开放 3000、8080
- 生产环境：只开放 80/443，通过 Nginx 代理

---

## 七、Nginx 配置（生产环境）

```nginx
# /etc/nginx/sites-available/questionos

server {
    listen 80;
    server_name your-domain.com;

    # 前端
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }

    # 后端 API
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        
        # SSE 支持
        proxy_buffering off;
        proxy_cache off;
    }

    # SSE 流式接口
    location /api/sandtable/turn {
        proxy_pass http://127.0.0.1:8080/api/sandtable/turn;
        proxy_http_version 1.1;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
        chunked_transfer_encoding off;
    }
}
```

启用配置：

```bash
sudo ln -s /etc/nginx/sites-available/questionos /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

---

## 八、systemd 服务（生产环境）

### 后端服务

创建 `/etc/systemd/system/questionos-api.service`：

```ini
[Unit]
Description=QuestionOS API Server
After=network.target

[Service]
Type=simple
User=www-data
WorkingDirectory=/opt/questionos
Environment="PATH=/opt/questionos/backend/venv/bin"
ExecStart=/opt/questionos/backend/venv/bin/python server.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### 前端服务

创建 `/etc/systemd/system/questionos-web.service`：

```ini
[Unit]
Description=QuestionOS Web Server
After=network.target

[Service]
Type=simple
User=www-data
WorkingDirectory=/opt/questionos/frontend
ExecStart=/usr/bin/npm start
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable questionos-api questionos-web
sudo systemctl start questionos-api questionos-web
```

---

## 九、常见问题

### Q1: 后端启动报错 `ModuleNotFoundError`

```bash
# 确保虚拟环境已激活
source backend/venv/bin/activate

# 重新安装依赖
pip install -r requirements.txt
```

### Q2: 前端无法连接后端

1. 检查后端是否启动：`curl http://localhost:8080/health`
2. 检查前端配置：`frontend/.env.local` 中的 `NEXT_PUBLIC_API_URL`
3. 检查 CORS 配置（后端已默认允许所有来源）

### Q3: AI 无响应或报错

1. 检查 API Key 是否正确
2. 检查百炼 API 额度是否用完
3. 查看后端日志：`tail -f server.log`

### Q4: 流式输出不工作

1. 确保 Nginx 配置了 `proxy_buffering off`
2. 确保没有中间件缓存响应

### Q5: 端口被占用

```bash
# 查找占用进程
lsof -i :8080

# 杀掉进程
kill -9 <PID>
```

---

## 十、目录结构

```
QuestionOS/
├── frontend/                # 前端项目
│   ├── app/                 # Next.js App Router
│   │   ├── page.tsx         # 首页
│   │   ├── chat/            # 思维校准页面
│   │   └── consult/         # 沙盘推演页面
│   ├── components/          # 组件
│   ├── lib/                 # 工具函数
│   ├── types/               # TypeScript 类型
│   ├── public/              # 静态资源
│   ├── package.json
│   └── next.config.js
│
├── backend/                 # 后端项目
│   ├── app/
│   │   ├── main.py          # FastAPI 入口
│   │   ├── agents/          # Agent 模块
│   │   │   ├── registry.py  # Agent 配置
│   │   │   └── openclaw_client.py  # AI 客户端
│   │   └── models/          # 数据模型
│   ├── .env                 # 环境变量
│   └── venv/                # 虚拟环境
│
├── server.py                # 统一启动入口
├── requirements.txt         # Python 依赖
├── README.md                # 项目说明
├── CHANGELOG.md             # 版本历史
└── DEPLOY.md                # 本文档
```

---

## 十一、联系与支持

- **文档更新**: 2026-03-13
- **适用版本**: v0.2
- **问题反馈**: 联系项目维护者

---

_部署愉快！🚀_