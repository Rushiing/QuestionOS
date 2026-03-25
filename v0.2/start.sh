#!/bin/bash
# QuestionOS 一键启动脚本 - 同时启动后端和前端

cd "$(dirname "$0")"

# 清理函数：Ctrl+C 时同时停止两个服务
cleanup() {
  echo ""
  echo "正在停止服务..."
  kill $BACKEND_PID $FRONTEND_PID 2>/dev/null
  exit 0
}
trap cleanup SIGINT SIGTERM

# 启动后端
echo "启动后端 (http://localhost:8080)..."
source venv/bin/activate
python server.py &
BACKEND_PID=$!

# 等待后端就绪
sleep 2

# 启动前端
echo "启动前端 (http://localhost:3000)..."
cd frontend && npm run dev &
FRONTEND_PID=$!

echo ""
echo "✅ 服务已启动"
echo "   前端: http://localhost:3000"
echo "   后端: http://localhost:8080"
echo ""
echo "按 Ctrl+C 停止所有服务"
echo ""

wait
