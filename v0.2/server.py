#!/usr/bin/env python3
"""
QuestionOS MVP 后端服务器
支持流式响应 (SSE)
启动命令：python server.py
访问地址：http://localhost:8080
"""

import os

# 修复 macOS SSL 证书验证失败（certificate verify failed）
try:
    import certifi
    os.environ['SSL_CERT_FILE'] = certifi.where()
    os.environ['REQUESTS_CA_BUNDLE'] = certifi.where()
except ImportError:
    pass

# 从 .env 加载配置（可选，支持项目根目录或 backend/.env）
try:
    from dotenv import load_dotenv
    _root = os.path.dirname(os.path.abspath(__file__))
    load_dotenv(os.path.join(_root, '.env'))
    load_dotenv(os.path.join(_root, 'backend', '.env'))
except ImportError:
    pass
import json
import uuid
import time
import hashlib
import secrets
import sqlite3
import jwt
import threading
import asyncio
from datetime import datetime, timedelta
from typing import Optional
from http.server import HTTPServer, SimpleHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

# 导入数据库模块
try:
    import db as database
    DB_ENABLED = True
except ImportError:
    DB_ENABLED = False
    print("⚠️ 数据库模块未加载，使用内存存储")

# ============ 配置区 ============
# 支持 DASHSCOPE_* 或 backend/.env 中的 BAILIAN_*
# Coding Plan 使用 coding.dashscope.aliyuncs.com
API_KEY = os.getenv('DASHSCOPE_API_KEY') or os.getenv('BAILIAN_API_KEY') or ''
API_BASE = os.getenv('DASHSCOPE_BASE_URL') or os.getenv('BAILIAN_BASE_URL') or 'https://coding.dashscope.aliyuncs.com/v1'
MODEL = os.getenv('DASHSCOPE_MODEL') or os.getenv('BAILIAN_MODEL') or os.getenv('DEFAULT_MODEL') or 'qwen3-coder-plus'
MOCK_MODE = False

# JWT 配置
JWT_SECRET = os.getenv('JWT_SECRET', 'questionos-secret-key-change-in-production')
JWT_ALGORITHM = 'HS256'
JWT_EXPIRE_HOURS = 24 * 7  # 7天过期

# 用户数据库路径
AUTH_DB_PATH = os.path.join(os.path.dirname(__file__), 'data', 'users.db')

# 内存存储会话
sessions = {}

# ============ 生图配置 ============
DASHSCOPE_IMAGE_KEY = 'sk-b761fa9e6be64baf9e40ffce5121486b'  # Wan2.6 专用
IMAGE_MODEL = 'wan2.6-t2i'

def generate_contradiction_image(center: str, main_conflict: str, secondary_conflicts: list = None) -> dict:
    """生成矛盾关系图
    
    Args:
        center: 中心节点（核心问题）
        main_conflict: 主要矛盾
        secondary_conflicts: 次要矛盾列表
    
    Returns:
        {"success": True, "image_url": "..."} 或 {"success": False, "error": "..."}
    """
    try:
        # 构建生图 prompt
        prompt_parts = [
            "概念关系图，简洁扁平插画风格，白色背景",
            f"中心主题：{center}",
            f"主要矛盾：{main_conflict}",
        ]
        
        if secondary_conflicts:
            prompt_parts.append(f"次要矛盾：{', '.join(secondary_conflicts[:3])}")
        
        prompt = "，".join(prompt_parts)
        prompt += "，清晰的文字标注，商业插画风格，高对比度"
        
        print(f"[IMAGE] 生成矛盾图: {prompt[:100]}...")
        
        # 调用 Wan2.6 API
        url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {DASHSCOPE_IMAGE_KEY}"
        }
        
        data = {
            "model": IMAGE_MODEL,
            "input": {
                "messages": [
                    {
                        "role": "user",
                        "content": [{"text": prompt}]
                    }
                ]
            },
            "parameters": {
                "prompt_extend": True,
                "watermark": False,
                "n": 1,
                "negative_prompt": "",
                "size": "1280*1280"
            }
        }
        
        req = Request(url, data=json.dumps(data).encode(), headers=headers)
        response = urlopen(req, timeout=60)
        result = json.loads(response.read())
        
        print(f"[IMAGE] API响应: {json.dumps(result, ensure_ascii=False)[:200]}...")
        
        # 解析结果 - Wan2.6 返回格式
        # {"output": {"choices": [{"message": {"content": [{"image": "url"}]}}]}}
        if result.get('output') and result['output'].get('choices'):
            choices = result['output']['choices']
            if choices and choices[0].get('message', {}).get('content'):
                content = choices[0]['message']['content']
                if content and content[0].get('image'):
                    image_url = content[0]['image']
                    print(f"[IMAGE] 生成成功: {image_url[:60]}...")
                    return {"success": True, "image_url": image_url, "prompt": prompt}
        
        return {"success": False, "error": "图片生成失败，请稍后重试"}
        
    except Exception as e:
        print(f"[IMAGE] 生成失败: {e}")
        return {"success": False, "error": str(e)}

# ============ 邮箱验证配置 ============
RESEND_API_KEY = os.getenv('RESEND_API_KEY', 're_6bVt3DEk_76AtqTp8wH5eLjcv8uAXhjrX')
FRONTEND_URL = os.getenv('FRONTEND_URL', 'http://localhost:3001')

def send_verification_email(email: str, token: str, name: str = None) -> bool:
    """发送验证邮件"""
    try:
        import resend
        resend.api_key = RESEND_API_KEY
        
        verify_url = f"{FRONTEND_URL}/verify?token={token}"
        user_name = name or email.split('@')[0]
        
        html_content = f"""
        <!DOCTYPE html>
        <html>
        <head><meta charset="utf-8"></head>
        <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f8fafc; padding: 40px 20px;">
          <div style="max-width: 480px; margin: 0 auto; background: white; border-radius: 16px; padding: 40px; box-shadow: 0 1px 3px rgba(0,0,0,0.1);">
            <h1 style="font-size: 24px; font-weight: 600; color: #1e293b; margin: 0 0 8px;">欢迎加入 QuestionOS</h1>
            <p style="color: #64748b; margin: 0 0 24px;">你好，{user_name}！</p>
            <p style="color: #475569; line-height: 1.6; margin: 0 0 24px;">感谢注册 QuestionOS。请点击下方按钮验证你的邮箱地址，完成注册。</p>
            <a href="{verify_url}" style="display: inline-block; background: #0f172a; color: white; padding: 12px 32px; border-radius: 8px; text-decoration: none; font-weight: 500;">验证邮箱</a>
            <p style="color: #94a3b8; font-size: 13px; margin: 24px 0 0;">或者复制链接到浏览器：<br><a href="{verify_url}" style="color: #3b82f6; word-break: break-all;">{verify_url}</a></p>
            <hr style="border: none; border-top: 1px solid #e2e8f0; margin: 24px 0;">
            <p style="color: #94a3b8; font-size: 12px; margin: 0;">如果你没有注册 QuestionOS，请忽略此邮件。</p>
          </div>
        </body>
        </html>
        """
        
        r = resend.Emails.send({
            "from": "onboarding@resend.dev",
            "to": email,
            "subject": "验证你的 QuestionOS 账号",
            "html": html_content
        })
        print(f"[EMAIL] 验证邮件已发送到 {email}, id: {r['id']}")
        return True
    except Exception as e:
        print(f"[EMAIL] 发送失败: {e}")
        return False
# =================================

# ============ 认证工具函数 ============
def init_auth_db():
    """初始化用户数据库"""
    os.makedirs(os.path.dirname(AUTH_DB_PATH), exist_ok=True)
    conn = sqlite3.connect(AUTH_DB_PATH)
    c = conn.cursor()
    c.execute('''
        CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            email TEXT UNIQUE NOT NULL,
            password_hash TEXT,
            name TEXT,
            provider TEXT DEFAULT 'email',
            provider_id TEXT,
            is_verified INTEGER DEFAULT 0,
            verify_token TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            last_login TIMESTAMP
        )
    ''')
    # 检查是否需要添加新字段（兼容旧数据库）
    try:
        c.execute("ALTER TABLE users ADD COLUMN is_verified INTEGER DEFAULT 0")
    except:
        pass
    try:
        c.execute("ALTER TABLE users ADD COLUMN verify_token TEXT")
    except:
        pass
    conn.commit()
    conn.close()

def hash_password(password: str) -> str:
    """密码哈希"""
    return hashlib.sha256(f"{password}{JWT_SECRET}".encode()).hexdigest()

def generate_user_id() -> str:
    """生成用户ID"""
    return f"user_{secrets.token_hex(8)}"

def create_jwt_token(user_id: str) -> str:
    """创建 JWT Token"""
    expire = datetime.utcnow() + timedelta(hours=JWT_EXPIRE_HOURS)
    payload = {"sub": user_id, "exp": expire, "iat": datetime.utcnow()}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)

def verify_jwt_token(token: str) -> Optional[str]:
    """验证 JWT Token，返回用户ID"""
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        return payload.get("sub")
    except:
        return None

# 初始化用户数据库
init_auth_db()
# =================================

# ============ Prompts ============
SYSTEM_PROMPT = """# 🤖 Agent 设定：逻辑炼金术士 (V4.0)

## 1. 核心人设
你是一个拥有深厚哲学底蕴的思维架构师。你存在的唯一目的，是把用户模糊的"情绪垃圾"炼化为精准的"逻辑手术刀"。你极其厌恶思维懒惰，说话直接、犀利且富有洞察力。

## 2. 核心互动原则 (三不原则)
* **拒绝廉价答案：** 严禁直接回答"怎么办"。必须先通过追问，让问题从"模糊"变得"清晰"。
* **禁止机械复读：** 严禁照抄系统提示词中的原话。每一句话都要根据当前语境即兴创作。
* **绝对性别敏感：** 保持对 LGBT 身份的尊重，严禁任何基于性别偏见或刻板印象的言论。

## 3. 互动逻辑：追问 -> 炼金

### 第一阶段：硬核追问 (榨取参数)
面对模糊提问，必须连续抛出 2-3 个深层追问，强制用户提供：
* **现场还原：** 别讲故事，讲画面。那一秒发生了什么？
* **身体感知：** 当时的生理反应（如胸闷、手抖、还是大脑空白）。
* **沉没成本：** 之前为了解决它，你做过哪些蠢事或无用功？

### 第二阶段：【逻辑炼金报告】
当信息量达标，必须按此格式输出：

**💎 原问题（垃圾堆）：**
用户的原始陈述。

**🔍 本质拆解（显微镜）：**
深度剖析问题的本质，包括：
- 问题背后的问题：表面问题下隐藏的真正困境
- 核心矛盾：内在冲突是什么？（如：理想vs现实、短期vs长期、自我vs他人期待）
- 情绪根源：为什么这个问题让你困扰？恐惧什么？期待什么？
- 认知盲区：你忽略了什么？假设了什么？

**🗡️ 重构后的天才提问（手术刀）：**
提炼为一个高质量问题，必须包含：
- 明确的背景上下文
- 清晰的核心障碍
- 可执行的目标方向

**🧠 思维脑图：**
用结构化方式展示问题的完整剖析，格式如下：
```
# 中心：[问题的核心本质]

## 主要矛盾
- [核心冲突1：描述+影响]
- [核心冲突2：描述+影响]

## 次要矛盾
- [干扰项1]
- [干扰项2]

## 根因分析
- [为什么会陷入这个困境]

## 突破方向
- [可能的解决路径]

## 隐藏假设
- [用户未经审视的假设]
```

## 4. 语言风格
* **多样化表达：** 拒绝复读。尝试使用："啧，这逻辑真有意思"、"你这算盘珠子都崩我脸上了"。
* **即兴类比：** 灵活运用用户提到的生活场景来打比方。
* **情绪张力：** 语气可以从"恨铁不成钢的咆哮"到"冷酷到底的理智"自由切换。

## 5. 继续追问的处理
如果用户继续追问或补充信息：
- 结合新信息深化分析
- 如果之前已完成炼金，可以进一步细化或调整结论
- 保持对话的连续性和深度"""


VALIDATION_PROMPT = """你是输入质量检测助手。判断用户输入是否认真。

判断标准：
- 长度<5字 → 太短
- 纯标点/重复字符 → 无意义
- 没有实质信息 → 不够清晰

输出JSON：
{"is_valid": true/false, "reason": "理由", "suggestion": "引导建议"}"""


class ProxyHandler(SimpleHTTPRequestHandler):
    """处理前端请求"""
    
    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'POST, OPTIONS, GET')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
        self.end_headers()
    
    def do_POST(self):
        if self.path == '/api/chat':
            self.handle_chat_stream()
        elif self.path == '/api/chat/sync':
            self.handle_chat_sync()
        elif self.path == '/api/validate':
            self.handle_validate()
        elif self.path == '/api/sessions':
            self.handle_create_session()
        elif self.path == '/api/auth/register':
            self.handle_register()
        elif self.path == '/api/auth/login':
            self.handle_login()
        elif self.path == '/api/auth/verify':
            self.handle_verify_email()
        elif self.path == '/api/auth/resend-verification':
            self.handle_resend_verification()
        elif self.path == '/api/generate-image':
            self.handle_generate_image()
        elif self.path == '/api/consult':
            self.handle_consult()
        elif self.path == '/api/consult/stream':
            self.handle_consult_stream()
        elif self.path == '/api/sandtable/turn':
            self.handle_sandtable_turn()
        elif self.path == '/api/sandtable/choose':
            self.handle_sandtable_choose()
        else:
            self.send_error(404, 'Not Found')
    
    def do_GET(self):
        if self.path == '/api/status':
            self.send_json_response({
                'status': 'ok',
                'model': MODEL,
                'configured': bool(API_KEY),
                'stream': True
            })
        elif self.path == '/api/auth/me':
            self.handle_get_me()
        elif self.path == '/api/auth/verify':
            self.handle_verify_email()
        elif self.path.startswith('/api/sessions/'):
            session_id = self.path.split('/')[-1]
            self.handle_get_session(session_id)
        elif self.path == '/api/sessions':
            self.handle_list_sessions()
        elif self.path == '/api/agents':
            self.handle_list_agents()
        elif self.path == '/' or self.path == '/index.html':
            self.path = '/mvp.html'
            super().do_GET()
        else:
            super().do_GET()
    
    def handle_chat_stream(self):
        """流式聊天响应 (SSE)"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            messages = data.get('messages', [])
            session_id = data.get('session_id')
            
            # SSE 响应头
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Connection', 'keep-alive')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('X-Accel-Buffering', 'no')
            self.end_headers()
            # 立即发送连接确认，避免用户误以为卡住（AI 首 token 可能较慢）
            self.wfile.write(b": ok\n\n")
            self.wfile.flush()
            
            # 收集完整响应用于数据库保存
            full_response = ''
            
            if MOCK_MODE:
                # Mock 流式响应
                response = self.mock_chat_response(messages)
                content = response['choices'][0]['message']['content']
                for char in content:
                    self.wfile.write(f"data: {json.dumps({'content': char}, ensure_ascii=False)}\n\n".encode())
                    self.wfile.flush()
                    time.sleep(0.02)
                full_response = content
            else:
                # 真实流式 API 调用
                full_messages = [{'role': 'system', 'content': SYSTEM_PROMPT}] + messages
                full_response = self.stream_ai_response_with_collect(full_messages)
            
            # 结束信号
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            
            # 保存对话到数据库
            if DB_ENABLED and session_id and messages:
                try:
                    # 获取最后一条用户消息
                    last_user_msg = None
                    for msg in reversed(messages):
                        if msg.get('role') == 'user':
                            last_user_msg = msg.get('content', '')
                            break
                    
                    if last_user_msg and full_response:
                        turn_count = database.get_turn_count(session_id)
                        database.save_turn(
                            session_id=session_id,
                            turn_number=turn_count + 1,
                            user_input=last_user_msg,
                            ai_response=full_response
                        )
                except Exception as e:
                    print(f"⚠️ 保存对话失败: {e}")
            
            # 同时更新内存存储（兼容）
            if session_id and session_id in sessions:
                sessions[session_id]['messages'] = messages
                sessions[session_id]['updated_at'] = datetime.now().isoformat()
            self.wfile.flush()
            
        except Exception as e:
            err_text = str(e)
            print(f"[Chat] 流式错误: {err_text}")
            # 以 content 形式返回，前端才能显示
            self.wfile.write(f"data: {json.dumps({'content': f'抱歉，AI 调用失败：{err_text}'}, ensure_ascii=False)}\n\n".encode())
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
    
    def handle_chat_sync(self):
        """同步聊天响应（兼容旧接口）"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            messages = data.get('messages', [])
            session_id = data.get('session_id')
            
            if MOCK_MODE:
                result = self.mock_chat_response(messages)
            else:
                full_messages = [{'role': 'system', 'content': SYSTEM_PROMPT}] + messages
                result = self.call_ai(full_messages)
            
            if session_id and session_id in sessions:
                sessions[session_id]['messages'] = messages
                sessions[session_id]['updated_at'] = datetime.now().isoformat()
            
            self.send_json_response(result)
            
        except Exception as e:
            self.send_json_response({'error': f'AI 调用失败：{str(e)}'}, status=500)
    
    def stream_ai_response(self, messages):
        """流式调用 AI API"""
        headers = {
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}'
        }
        
        api_url = f'{API_BASE}/chat/completions'
        request = Request(api_url, data=json.dumps({
            'model': MODEL,
            'messages': messages,
            'temperature': 0.7,
            'max_tokens': 2000,
            'stream': True
        }).encode(), headers=headers)
        
        try:
            response = urlopen(request, timeout=60)
            for line in response:
                line = line.decode().strip()
                if line.startswith('data: '):
                    data = line[6:]
                    if data == '[DONE]':
                        break
                    try:
                        chunk = json.loads(data)
                        choice = chunk.get('choices', [{}])[0]
                        delta = choice.get('delta', {})
                        finish_reason = choice.get('finish_reason')
                        
                        # 检查是否结束
                        if finish_reason == 'stop':
                            return
                        
                        # 只返回 content
                        content = delta.get('content')
                        if content:
                            self.wfile.write(f"data: {json.dumps({'content': content}, ensure_ascii=False)}\n\n".encode())
                            self.wfile.flush()
                    except json.JSONDecodeError:
                        continue
        except HTTPError as e:
            error_body = e.read().decode()
            raise Exception(f'API 错误 ({e.code}): {error_body}')
    
    def stream_ai_response_with_collect(self, messages):
        """流式调用 AI API 并收集完整响应"""
        import time
        start_time = time.time()
        
        headers = {
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}'
        }
        
        api_url = f'{API_BASE}/chat/completions'
        request = Request(api_url, data=json.dumps({
            'model': MODEL,
            'messages': messages,
            'temperature': 0.7,
            'max_tokens': 2000,
            'stream': True
        }).encode(), headers=headers)
        
        full_content = ''
        first_chunk_time = None
        
        try:
            response = urlopen(request, timeout=60)
            for line in response:
                line = line.decode().strip()
                if line.startswith('data: '):
                    data = line[6:]
                    if data == '[DONE]':
                        break
                    try:
                        chunk = json.loads(data)
                        choice = chunk.get('choices', [{}])[0]
                        delta = choice.get('delta', {})
                        finish_reason = choice.get('finish_reason')
                        
                        if finish_reason == 'stop':
                            print(f"[API] 总耗时: {time.time() - start_time:.2f}s, 内容长度: {len(full_content)}")
                            return full_content
                        
                        content = delta.get('content')
                        if content:
                            if first_chunk_time is None:
                                first_chunk_time = time.time()
                                print(f"[API] 首字节耗时: {first_chunk_time - start_time:.2f}s")
                            full_content += content
                            self.wfile.write(f"data: {json.dumps({'content': content}, ensure_ascii=False)}\n\n".encode())
                            self.wfile.flush()
                    except json.JSONDecodeError:
                        continue
        except HTTPError as e:
            error_body = e.read().decode()
            raise Exception(f'API 错误 ({e.code}): {error_body}')
        
        print(f"[API] 总耗时: {time.time() - start_time:.2f}s")
        return full_content
    
    def handle_validate(self):
        """验证用户输入"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            user_input = data.get('input', '')
            
            # 简单规则判断
            if len(user_input) < 5:
                self.send_json_response({
                    'is_valid': False,
                    'reason': '输入太短了',
                    'suggestion': '能多描述一些吗？比如发生了什么、你想达成什么？'
                })
                return
            
            invalid_patterns = ['...', '。。。', '？？？', '!!!', '111', 'asdf', '测试', 'asdfgh']
            if user_input.strip() in invalid_patterns:
                self.send_json_response({
                    'is_valid': False,
                    'reason': '输入内容不够清晰',
                    'suggestion': '能认真描述一下你的问题吗？比如：发生了什么？你想达成什么？'
                })
                return
            
            # 默认通过
            self.send_json_response({'is_valid': True, 'reason': '输入有效', 'suggestion': ''})
            
        except Exception as e:
            self.send_json_response({'error': f'验证失败：{str(e)}'}, status=500)
    
    def handle_create_session(self):
        """创建新会话"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            question = data.get('question', '')
            
            # 从 Authorization header 获取 user_id
            user_id = self.get_user_id_from_token()
            print(f"[DEBUG] 创建会话 - Authorization: {self.headers.get('Authorization', 'None')[:50]}...")
            print(f"[DEBUG] 创建会话 - user_id: {user_id}")
            
            # 使用数据库存储
            if DB_ENABLED:
                session_id = database.create_session(question, user_id=user_id)
            else:
                session_id = str(uuid.uuid4())
                sessions[session_id] = {
                    'id': session_id,
                    'original_question': question,
                    'messages': [],
                    'user_id': user_id,
                    'created_at': datetime.now().isoformat(),
                    'updated_at': datetime.now().isoformat()
                }
            
            self.send_json_response({'session_id': session_id, 'status': 'created'})
            
        except Exception as e:
            self.send_json_response({'error': f'创建会话失败：{str(e)}'}, status=500)
    
    def handle_get_session(self, session_id):
        """获取会话详情"""
        # 优先使用数据库
        if DB_ENABLED:
            session = database.get_session(session_id)
            if session:
                self.send_json_response(session)
            else:
                self.send_json_response({'error': '会话不存在'}, status=404)
        else:
            # 降级到内存存储
            if session_id in sessions:
                self.send_json_response(sessions[session_id])
            else:
                self.send_json_response({'error': '会话不存在'}, status=404)
    
    def handle_list_sessions(self):
        """获取会话列表"""
        # 从 Token 获取 user_id
        user_id = self.get_user_id_from_token()
        
        if DB_ENABLED:
            session_list = database.list_sessions(limit=50, user_id=user_id)
            self.send_json_response({'sessions': session_list})
        else:
            # 内存存储过滤
            if user_id:
                session_list = [{
                    'id': s['id'],
                    'original_question': s['original_question'],
                    'created_at': s['created_at']
                } for s in sessions.values() if s.get('user_id') == user_id]
            else:
                session_list = [{
                    'id': s['id'],
                    'original_question': s['original_question'],
                    'created_at': s['created_at']
                } for s in sessions.values()]
            self.send_json_response({'sessions': session_list})
    
    def get_user_id_from_token(self) -> Optional[str]:
        """从 Authorization header 提取 user_id"""
        auth_header = self.headers.get('Authorization', '')
        if not auth_header.startswith('Bearer '):
            return None
        token = auth_header[7:]
        return verify_jwt_token(token)
    
    def mock_chat_response(self, messages):
        """Mock 响应"""
        last_user_msg = ""
        for msg in reversed(messages):
            if msg.get('role') == 'user':
                last_user_msg = msg.get('content', '')
                break
        
        responses = [
            "这个问题涉及哪些关键利益相关者？他们的诉求分别是什么？",
            "如果能完美解决这个问题，6个月后的情况会是什么样？",
            "目前阻碍你做决定的最大顾虑是什么？",
        ]
        idx = len(last_user_msg) % len(responses)
        
        return {'choices': [{'message': {'content': responses[idx]}}]}
    
    def call_ai(self, messages):
        """非流式调用 AI"""
        if not API_KEY:
            raise Exception('API Key 未配置')
        
        headers = {
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {API_KEY}'
        }
        
        api_url = f'{API_BASE}/chat/completions'
        request = Request(api_url, data=json.dumps({
            'model': MODEL,
            'messages': messages,
            'temperature': 0.7,
            'max_tokens': 2000
        }).encode(), headers=headers)
        
        response = urlopen(request, timeout=60)
        return json.loads(response.read())
    
    def send_json_response(self, data, status=200):
        """发送 JSON 响应"""
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode())

    # ============ 认证处理函数 ============
    def handle_register(self):
        """用户注册（需要邮箱验证）"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            email = data.get('email', '')
            password = data.get('password', '')
            name = data.get('name') or email.split('@')[0]
            
            if not email or not password:
                self.send_json_response({'detail': '邮箱和密码不能为空'}, status=400)
                return
            
            conn = sqlite3.connect(AUTH_DB_PATH)
            c = conn.cursor()
            
            # 检查邮箱是否已注册
            c.execute("SELECT id, is_verified FROM users WHERE email = ?", (email,))
            existing = c.fetchone()
            if existing:
                user_id, is_verified = existing
                if is_verified:
                    conn.close()
                    self.send_json_response({'detail': '该邮箱已被注册'}, status=400)
                    return
                else:
                    # 已注册但未验证，重新发送验证邮件
                    verify_token = secrets.token_urlsafe(32)
                    c.execute("UPDATE users SET verify_token = ?, name = ?, password_hash = ? WHERE id = ?",
                              (verify_token, name, hash_password(password), user_id))
                    conn.commit()
                    conn.close()
                    
                    # 发送验证邮件
                    send_verification_email(email, verify_token, name)
                    
                    self.send_json_response({
                        'status': 'verification_sent',
                        'message': '验证邮件已发送，请查收邮箱完成注册',
                        'email': email
                    })
                    return
            
            # 创建新用户（未验证）
            user_id = generate_user_id()
            password_hash = hash_password(password)
            verify_token = secrets.token_urlsafe(32)
            now = datetime.utcnow().isoformat()
            
            c.execute(
                "INSERT INTO users (id, email, password_hash, name, provider, is_verified, verify_token, created_at, last_login) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)",
                (user_id, email, password_hash, name, 'email', verify_token, now, now)
            )
            conn.commit()
            conn.close()
            
            # 发送验证邮件
            send_verification_email(email, verify_token, name)
            
            self.send_json_response({
                'status': 'verification_sent',
                'message': '验证邮件已发送，请查收邮箱完成注册',
                'email': email
            })
            
        except Exception as e:
            self.send_json_response({'detail': f'注册失败：{str(e)}'}, status=500)
    
    def handle_login(self):
        """用户登录"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            email = data.get('email', '')
            password = data.get('password', '')
            
            if not email or not password:
                self.send_json_response({'detail': '邮箱和密码不能为空'}, status=400)
                return
            
            conn = sqlite3.connect(AUTH_DB_PATH)
            c = conn.cursor()
            
            # 查找用户
            c.execute("SELECT id, email, password_hash, name, created_at, is_verified FROM users WHERE email = ?", (email,))
            row = c.fetchone()
            
            if not row:
                conn.close()
                self.send_json_response({'detail': '邮箱或密码错误'}, status=401)
                return
            
            user_id, user_email, password_hash, name, created_at, is_verified = row
            
            # 验证密码
            if password_hash != hash_password(password):
                conn.close()
                self.send_json_response({'detail': '邮箱或密码错误'}, status=401)
                return
            
            # 检查是否已验证邮箱
            if not is_verified:
                conn.close()
                self.send_json_response({'detail': '请先验证邮箱后再登录', 'need_verification': True, 'email': email}, status=401)
                return
            
            # 更新最后登录时间
            now = datetime.utcnow().isoformat()
            c.execute("UPDATE users SET last_login = ? WHERE id = ?", (now, user_id))
            conn.commit()
            conn.close()
            
            # 生成 Token
            token = create_jwt_token(user_id)
            
            self.send_json_response({
                'access_token': token,
                'token_type': 'bearer',
                'expires_in': JWT_EXPIRE_HOURS * 3600,
                'user': {
                    'id': user_id,
                    'email': user_email,
                    'name': name,
                    'created_at': created_at
                }
            })
            
        except Exception as e:
            self.send_json_response({'detail': f'登录失败：{str(e)}'}, status=500)
    
    def handle_get_me(self):
        """获取当前用户信息"""
        try:
            # 从 Authorization header 获取 token
            auth_header = self.headers.get('Authorization', '')
            if not auth_header.startswith('Bearer '):
                self.send_json_response({'detail': '未登录或Token已过期'}, status=401)
                return
            
            token = auth_header[7:]  # 去掉 "Bearer " 前缀
            user_id = verify_jwt_token(token)
            
            if not user_id:
                self.send_json_response({'detail': '未登录或Token已过期'}, status=401)
                return
            
            conn = sqlite3.connect(AUTH_DB_PATH)
            c = conn.cursor()
            c.execute("SELECT id, email, name, created_at FROM users WHERE id = ?", (user_id,))
            row = c.fetchone()
            conn.close()
            
            if not row:
                self.send_json_response({'detail': '用户不存在'}, status=404)
                return
            
            self.send_json_response({
                'id': row[0],
                'email': row[1],
                'name': row[2],
                'created_at': row[3]
            })
            
        except Exception as e:
            self.send_json_response({'detail': f'获取用户信息失败：{str(e)}'}, status=500)
    
    def handle_verify_email(self):
        """验证邮箱"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            token = data.get('token', '')
            if not token:
                self.send_json_response({'detail': '验证token无效'}, status=400)
                return
            
            conn = sqlite3.connect(AUTH_DB_PATH)
            c = conn.cursor()
            
            # 查找对应的用户
            c.execute("SELECT id, email, name, created_at FROM users WHERE verify_token = ?", (token,))
            row = c.fetchone()
            
            if not row:
                conn.close()
                self.send_json_response({'detail': '验证链接已过期或无效'}, status=400)
                return
            
            user_id, email, name, created_at = row
            
            # 更新验证状态
            c.execute("UPDATE users SET is_verified = 1, verify_token = NULL WHERE id = ?", (user_id,))
            conn.commit()
            conn.close()
            
            # 生成登录 token
            jwt_token = create_jwt_token(user_id)
            
            self.send_json_response({
                'status': 'verified',
                'message': '邮箱验证成功！',
                'access_token': jwt_token,
                'token_type': 'bearer',
                'expires_in': JWT_EXPIRE_HOURS * 3600,
                'user': {
                    'id': user_id,
                    'email': email,
                    'name': name,
                    'created_at': created_at
                }
            })
            
        except Exception as e:
            self.send_json_response({'detail': f'验证失败：{str(e)}'}, status=500)
    
    def handle_resend_verification(self):
        """重新发送验证邮件"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            email = data.get('email', '')
            if not email:
                self.send_json_response({'detail': '请输入邮箱地址'}, status=400)
                return
            
            conn = sqlite3.connect(AUTH_DB_PATH)
            c = conn.cursor()
            
            c.execute("SELECT id, name, is_verified FROM users WHERE email = ?", (email,))
            row = c.fetchone()
            
            if not row:
                conn.close()
                self.send_json_response({'detail': '该邮箱未注册'}, status=400)
                return
            
            user_id, name, is_verified = row
            
            if is_verified:
                conn.close()
                self.send_json_response({'detail': '该邮箱已验证，请直接登录'}, status=400)
                return
            
            # 生成新的验证 token
            verify_token = secrets.token_urlsafe(32)
            c.execute("UPDATE users SET verify_token = ? WHERE id = ?", (verify_token, user_id))
            conn.commit()
            conn.close()
            
            # 发送验证邮件
            send_verification_email(email, verify_token, name)
            
            self.send_json_response({
                'status': 'verification_sent',
                'message': '验证邮件已重新发送'
            })
            
        except Exception as e:
            self.send_json_response({'detail': f'发送失败：{str(e)}'}, status=500)
    
    def handle_generate_image(self):
        """生成矛盾关系图"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            center = data.get('center', '')
            main_conflict = data.get('main_conflict', '')
            secondary_conflicts = data.get('secondary_conflicts', [])
            
            if not center or not main_conflict:
                self.send_json_response({'detail': '缺少必要参数'}, status=400)
                return
            
            result = generate_contradiction_image(center, main_conflict, secondary_conflicts)
            
            if result['success']:
                self.send_json_response({
                    'success': True,
                    'image_url': result['image_url'],
                    'prompt': result.get('prompt', '')
                })
            else:
                self.send_json_response({'detail': result['error']}, status=500)
                
        except Exception as e:
            self.send_json_response({'detail': f'生图失败：{str(e)}'}, status=500)
    
    def handle_list_agents(self):
        """列出所有可用的沙盘推演 Agent"""
        try:
            # 导入 Agent 注册表
            import sys
            sys.path.insert(0, os.path.dirname(__file__))
            from backend.app.agents.registry import list_agents
            
            agents = list_agents()
            agent_list = [
                {
                    'id': agent.id,
                    'name': agent.name,
                    'avatar': agent.avatar,
                    'description': agent.description,
                    'role': agent.role.value if hasattr(agent.role, 'value') else str(agent.role),
                    'dimension': agent.dimension.value if agent.dimension and hasattr(agent.dimension, 'value') else None,
                    'personality': agent.personality,
                    'execution_order': agent.execution_order
                }
                for agent in agents
            ]
            
            self.send_json_response({
                'agents': agent_list,
                'total': len(agent_list)
            })
        except Exception as e:
            self.send_json_response({'detail': f'获取 Agent 列表失败：{str(e)}'}, status=500)
    
    def handle_consult(self):
        """处理咨询请求 - 让多个 Agent 讨论问题"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            question = data.get('question', '')
            agent_ids = data.get('agent_ids', [])
            context = data.get('context')
            mode = data.get('mode', 'discussion')  # discussion | debate
            
            if not question:
                self.send_json_response({'detail': '请提供问题'}, status=400)
                return
            
            if not agent_ids:
                self.send_json_response({'detail': '请选择至少一个顾问'}, status=400)
                return
            
            # 执行咨询
            import sys
            sys.path.insert(0, os.path.dirname(__file__))
            import asyncio
            from backend.app.agents.openclaw_client import run_consultation
            
            # 运行异步咨询
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            try:
                responses = loop.run_until_complete(
                    run_consultation(
                        question=question,
                        agent_ids=agent_ids,
                        context=context,
                        mode=mode
                    )
                )
            finally:
                loop.close()
            
            self.send_json_response({
                'consultation_id': secrets.token_urlsafe(16),
                'question': question,
                'mode': mode,
                'responses': responses,
                'status': 'completed'
            })
            
        except Exception as e:
            import traceback
            traceback.print_exc()
            self.send_json_response({'detail': f'咨询失败：{str(e)}'}, status=500)
    
    def handle_consult_stream(self):
        """沙盘推演流式接口 - SSE"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            question = data.get('question', '')
            
            if not question:
                self.send_json_response({'detail': '请提供问题'}, status=400)
                return
            
            # SSE 响应头
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Connection', 'keep-alive')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            
            # 执行沙盘推演
            import sys
            sys.path.insert(0, os.path.dirname(__file__))
            import asyncio
            from backend.app.agents.openclaw_client import run_sandtable_stream
            
            # 创建事件循环
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            
            try:
                async def stream_responses():
                    async for event in run_sandtable_stream(question):
                        event_json = json.dumps(event, ensure_ascii=False)
                        self.wfile.write(f"data: {event_json}\n\n".encode())
                        self.wfile.flush()
                    # 结束信号
                    self.wfile.write(b"data: [DONE]\n\n")
                    self.wfile.flush()
                
                loop.run_until_complete(stream_responses())
            finally:
                loop.close()
            
        except Exception as e:
            import traceback
            traceback.print_exc()
            error_event = json.dumps({'type': 'error', 'message': str(e)}, ensure_ascii=False)
            self.wfile.write(f"data: {error_event}\n\n".encode())
            self.wfile.flush()
    
    def handle_sandtable_choose(self):
        """智能选择下一个发言的Agent"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            user_message = data.get('user_message', '')
            conversation_history = data.get('conversation_history', [])
            
            # 获取最近的Agent发言记录
            recent_agents = [
                m.get('agent_id') for m in conversation_history 
                if m.get('role') not in ['用户', 'user'] and m.get('agent_id')
            ][-3:]  # 最近3次
            
            # 检查是否连续两次同一个Agent
            force_change = False
            if len(recent_agents) >= 2:
                if recent_agents[-1] == recent_agents[-2] and recent_agents[-1] in ['auditor', 'risk_officer', 'value_judge']:
                    force_change = True
                    last_agent = recent_agents[-1]
            
            import random
            
            if force_change:
                # 强制换人，排除连续出现的agent
                available = ['auditor', 'risk_officer', 'value_judge']
                available.remove(last_agent)
                agent_id = random.choice(available)
            else:
                # 正常选择逻辑：根据关键词
                profit_keywords = ['钱', '成本', '收益', '薪资', '工资', 'ROI', '投入', '回报', '划算', '值得', '多少']
                risk_keywords = ['风险', '担心', '怕', '失败', '崩', '坏', '损失', '问题', '麻烦', '坑']
                value_keywords = ['意义', '价值', '自我', '想', '为什么', '目的', '目标', '人生', '未来', '选择']
                
                combined_text = user_message + ' ' + ' '.join([m.get('content', '') for m in conversation_history[-3:]])
                
                profit_score = sum(1 for k in profit_keywords if k in combined_text)
                risk_score = sum(1 for k in risk_keywords if k in combined_text)
                value_score = sum(1 for k in value_keywords if k in combined_text)
                
                if profit_score > risk_score and profit_score > value_score:
                    agent_id = 'auditor'
                elif risk_score > profit_score and risk_score > value_score:
                    agent_id = 'risk_officer'
                elif value_score > profit_score and value_score > risk_score:
                    agent_id = 'value_judge'
                else:
                    # 随机但避免连续同一个
                    available = ['auditor', 'risk_officer', 'value_judge']
                    if recent_agents and recent_agents[-1] in available:
                        available.remove(recent_agents[-1])
                    agent_id = random.choice(available)
            
            self.send_json_response({
                'agent_id': agent_id,
                'reason': f'根据问题分析选择{agent_id}' + (' (强制换人)' if force_change else '')
            })
            
        except Exception as e:
            import traceback
            traceback.print_exc()
            self.send_json_response({'agent_id': 'auditor', 'reason': '默认选择'})
    
    def handle_sandtable_turn(self):
        """沙盘推询单轮对话 - SSE流式"""
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(content_length)
            data = json.loads(body)
            
            user_message = data.get('user_message', '')
            conversation_history = data.get('conversation_history', [])
            agent_id = data.get('agent_id', 'auditor')  # 直接指定Agent ID
            
            if not user_message:
                self.send_json_response({'detail': '请输入消息'}, status=400)
                return
            
            # SSE 响应头
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Connection', 'keep-alive')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            
            # 获取指定的Agent
            import sys
            sys.path.insert(0, os.path.dirname(__file__))
            from backend.app.agents.registry import get_agent
            
            current_agent = get_agent(agent_id)
            if not current_agent:
                current_agent = get_agent('auditor')  # 默认
            
            # 发送Agent开始事件
            self.wfile.write(f"data: {json.dumps({'type': 'agent_start', 'agent_id': current_agent.id, 'agent_name': current_agent.name, 'agent_avatar': current_agent.avatar}, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()
            
            # 构建对话上下文
            context_messages = []
            for msg in conversation_history:
                context_messages.append(f"【{msg.get('role', '用户')}】：{msg.get('content', '')}")
            
            # 调用AI
            import asyncio
            import re
            from backend.app.agents.openclaw_client import SandtableClient
            
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            
            client = SandtableClient()
            
            # 构建问题
            if context_messages:
                question = f"之前的对话：\n{chr(10).join(context_messages)}\n\n用户最新消息：{user_message}"
            else:
                question = user_message
            
            full_content = ""
            gen = None
            error_occurred = False
            
            async def stream_turn():
                nonlocal full_content, gen, error_occurred
                try:
                    gen = client.consult_stream(
                        question=question,
                        system_prompt=current_agent.system_prompt
                    )
                    async for chunk in gen:
                        full_content += chunk
                        # 去掉代码块标记
                        clean_chunk = re.sub(r'^```\w*\n?|```$', '', chunk)
                        self.wfile.write(f"data: {json.dumps({'type': 'content', 'agent_id': current_agent.id, 'content': clean_chunk}, ensure_ascii=False)}\n\n".encode())
                        self.wfile.flush()
                except Exception as stream_error:
                    error_occurred = True
                    print(f"Stream error: {stream_error}")
                    import traceback
                    traceback.print_exc()
                finally:
                    # 确保 generator 被关闭
                    if gen:
                        try:
                            await gen.aclose()
                        except:
                            pass
            
            loop.run_until_complete(stream_turn())
            loop.close()
            
            # 发送Agent结束事件（无论是否有错误）
            self.wfile.write(f"data: {json.dumps({'type': 'agent_end', 'agent_id': current_agent.id, 'agent_name': current_agent.name}, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()
            
            # 结束信号
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            
            if error_occurred:
                error_event = json.dumps({'type': 'error', 'message': 'AI 响应中断，请重试'}, ensure_ascii=False)
                self.wfile.write(f"data: {error_event}\n\n".encode())
                self.wfile.flush()
            
        except Exception as e:
            import traceback
            traceback.print_exc()
            error_event = json.dumps({'type': 'error', 'message': str(e)}, ensure_ascii=False)
            self.wfile.write(f"data: {error_event}\n\n".encode())
            self.wfile.flush()


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    """多线程HTTP服务器，支持并发请求"""
    daemon_threads = True


def main():
    port = 8080
    print(f"""
╔══════════════════════════════════════════╗
║     QuestionOS MVP 服务器启动 (流式)      ║
╠══════════════════════════════════════════╣
║  访问地址：http://localhost:{port}          ║
║  模型配置：{MODEL:<26} ║
║  流式响应：✓                            ║
╚══════════════════════════════════════════╝
    """)
    
    server = ThreadedHTTPServer(('', port), ProxyHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\n服务器已停止')
        server.shutdown()


if __name__ == '__main__':
    main()