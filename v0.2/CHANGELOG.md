# QuestionOS 版本管理

## 版本命名规则

- **主版本号 (Major)**: 重大架构变更或产品方向调整
- **次版本号 (Minor)**: 功能新增、重要优化
- **修订号 (Patch)**: Bug 修复、小改进

格式: `v{Major}.{Minor}.{Patch}`

---

## 版本历史

### v0.2 - 沙盘推演体验优化版

**发布日期**: 2026-03-13

**功能更新**:
- ✅ Agent 输出格式优化（结构化 markdown，清晰分区）
- ✅ 流式输出解锁机制优化（agent_end 事件驱动，即时解锁）
- ✅ 首页示例问题可带入沙盘推演
- ✅ 完整 markdown 渲染样式支持
- ✅ 沙盘推演团队展示区添加"虚位以待"占位图标
- ✅ 输入框滚动条优化（overflow-hidden）

**Bug 修复**:
- 🐛 修复 conversation_history 缺少 agent_id 导致强制换人逻辑不生效
- 🐛 修复 AI 输出代码块包裹导致 markdown 不渲染
- 🐛 修复 async generator 未正确关闭导致 Task destroyed 警告
- 🐛 修复整合官无法输出问题

**技术改进**:
- 🔧 后端添加代码块过滤（自动去除 markdown 代码块标记）
- 🔧 async generator 正确关闭，避免资源泄漏
- 🔧 Agent Prompt 重构，输出格式更结构化

**Agent 输出格式示例**:
```
💰 利益审计师：
**核心账本**
[关键利益点]

**关键数据**
- [数据要求]

❓ [问题]

🏛️ 首席整合官：
## 🏛️ 整合报告
### ⚔️ 博弈复盘
### 📊 决策沙盘
### ❓ 终极提问
```

---

### v0.1 - MVP 验证版

**发布日期**: 2026-03-12

**核心功能**:
- ✅ 双模式入口：思维校准 + 沙盘推演
- ✅ 沙盘推演：多 Agent 对话（利益审计师、风险预测官、价值裁判）
- ✅ 首席整合官：博弈复盘 + 决策沙盘
- ✅ 流式输出支持
- ✅ Agent 智能选择（根据对话历史自动换人）
- ✅ 强制换人逻辑（同一 Agent 不连续发言）
- ✅ 首页示例问题快速开始

**技术架构**:
- 前端：Next.js + TypeScript + Tailwind CSS
- 后端：Python FastAPI
- AI：阿里云百炼 API
- 数据流：SSE 流式输出

---

## 开发中 (Upcoming)

### v0.3 - 计划中

**规划功能**:
- [ ] Agent 自主接入机制
- [ ] 会话历史持久化
- [ ] 用户账号系统
- [ ] 邮箱验证登录
- [ ] PostgreSQL 数据库集成

---

## 打包文件说明

| 文件 | 内容 | 用途 |
|------|------|------|
| `QuestionOS-v0.x.tar.gz` | 前端 + 后端源码 | 完整部署包 |
| `QuestionOS-frontend.tar.gz` | 仅前端源码 | 前端独立部署 |

**打包内容**:
- `frontend/` - Next.js 前端源码
- `backend/` - FastAPI 后端源码
- `server.py` - 统一启动入口
- `README.md` - 项目说明

**排除内容**:
- `node_modules/` - 前端依赖（需 npm install）
- `.next/` - 构建缓存（需 npm run build）
- `.git/` - Git 历史
- `*.log` - 日志文件
- `__pycache__/` - Python 缓存

---

## 部署说明

### 环境要求
- Python 3.11+
- Node.js 18+
- PostgreSQL 14+（v0.3+ 需要）

### 启动步骤

```bash
# 1. 解压
tar -xzf QuestionOS-v0.2.tar.gz

# 2. 后端
cd QuestionOS
python3 -m venv backend/venv
source backend/venv/bin/activate
pip install -r requirements.txt
python3 server.py

# 3. 前端
cd frontend
npm install
npm run build
npm start

# 4. 访问
# 前端: http://localhost:3000
# 后端: http://localhost:8080
```

---

## 版本管理原则

1. **向后兼容**: Minor 版本保持 API 兼容
2. **渐进增强**: 新功能通过配置开关控制
3. **清晰文档**: 每个版本更新 CHANGELOG
4. **Git Tag**: 每个版本打 tag 标记
5. **打包存档**: 稳定版本打包存档到桌面

---

_最后更新: 2026-03-13_