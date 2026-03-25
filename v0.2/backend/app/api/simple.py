"""
Simple API - 无需登录的简化接口
用于快速体验问题校准功能
"""

import time
import json
import uuid
from typing import Optional
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.core.config import get_settings
from app.services.ai_service import (
    BailianClient, 
    CalibrationChat, 
    QuestionAnalyzer
)

router = APIRouter(tags=["simple"])
settings = get_settings()

# 全局AI服务实例
_ai_client: Optional[BailianClient] = None
_calibration_chat: Optional[CalibrationChat] = None

# 内存中存储会话上下文（简化版，生产环境应使用数据库）
_sessions: dict = {}


def get_ai_services():
    """获取或初始化AI服务实例"""
    global _ai_client, _calibration_chat
    
    if _ai_client is None:
        _ai_client = BailianClient(
            api_key=settings.BAILIAN_API_KEY,
            base_url=settings.BAILIAN_BASE_URL,
            model=settings.DEFAULT_MODEL
        )
        _calibration_chat = CalibrationChat(_ai_client)
    
    return _ai_client, _calibration_chat


class SimpleChatRequest(BaseModel):
    """简化聊天请求"""
    message: str
    session_id: Optional[str] = None


class SimpleChatResponse(BaseModel):
    """简化聊天响应"""
    session_id: str
    message: str
    clarity_score: int
    is_complete: bool
    analysis: Optional[dict] = None


class SessionResponse(BaseModel):
    """会话创建响应"""
    session_id: str
    message: str


@router.post("/start", response_model=SessionResponse)
async def start_session():
    """
    创建新的匿名会话
    
    返回一个session_id，用于后续对话
    """
    session_id = str(uuid.uuid4())
    _sessions[session_id] = {
        "messages": [],
        "clarity_score": 50,
        "original_question": None,
        "analysis": None
    }
    
    return SessionResponse(
        session_id=session_id,
        message="会话创建成功，请输入你的问题"
    )


@router.post("/chat", response_model=SimpleChatResponse)
async def simple_chat(request: SimpleChatRequest):
    """
    简化聊天接口 - 无需登录
    
    流程：
    1. 如果是新问题，分析问题结构并生成追问
    2. 如果是回答，更新清晰度并继续追问或生成报告
    
    - **message**: 用户消息
    - **session_id**: 可选，已有会话ID
    """
    start_time = time.time()
    
    # 获取或创建会话
    if request.session_id and request.session_id in _sessions:
        session_id = request.session_id
        session = _sessions[session_id]
    else:
        session_id = str(uuid.uuid4())
        session = {
            "messages": [],
            "clarity_score": 50,
            "original_question": None,
            "analysis": None
        }
        _sessions[session_id] = session
    
    # 获取AI服务
    client, calibration = get_ai_services()
    
    # 添加用户消息
    session["messages"].append({
        "role": "user",
        "content": request.message
    })
    
    # 判断是第一个问题还是后续回答
    is_first_message = session["original_question"] is None
    
    if is_first_message:
        # 第一个问题：分析结构并生成追问
        session["original_question"] = request.message
        
        try:
            # 分析问题结构
            analyzer = QuestionAnalyzer(client)
            analysis = await analyzer.analyze_structure(request.message)
            
            session["analysis"] = {
                "question_type": analysis.question_type.value,
                "core_variables": analysis.core_variables,
                "cognitive_biases": analysis.cognitive_biases,
                "clarity_score": analysis.clarity_score
            }
            session["clarity_score"] = analysis.clarity_score
            
            # 创建校准上下文
            calibration.create_context(session_id, request.message, analysis)
            
            # 生成追问
            result = await calibration.generate_questions(session_id, num_questions=2)
            
            # 构建AI响应
            if result.questions:
                questions_text = "\n\n".join([
                    f"{i+1}. {q.question}"
                    for i, q in enumerate(result.questions)
                ])
                
                # 检测到的偏差说明
                bias_info = ""
                if analysis.cognitive_biases:
                    bias_names = {
                        "anchoring": "锚定效应",
                        "framing": "框架效应",
                        "confirmation": "确认偏误",
                        "availability": "可得性启发",
                        "survivorship": "幸存者偏差",
                        "false_dichotomy": "非此即彼",
                        "leading_question": "诱导性问题"
                    }
                    detected = [bias_names.get(b, b) for b in analysis.cognitive_biases[:3]]
                    if detected:
                        bias_info = f"\n\n🔍 我注意到你的问题中可能存在：{'、'.join(detected)}"
                
                ai_message = f"""我已经分析了你的问题，当前清晰度：{analysis.clarity_score}/100

{bias_info}

为了帮你更清晰地定义这个问题，我想了解：{questions_text}"""
            else:
                ai_message = f"我已经分析了你的问题，当前清晰度：{analysis.clarity_score}/100。\n\n请告诉我更多关于这个问题的背景信息。"
            
            # 添加AI消息
            session["messages"].append({
                "role": "assistant",
                "content": ai_message
            })
            
            return SimpleChatResponse(
                session_id=session_id,
                message=ai_message,
                clarity_score=session["clarity_score"],
                is_complete=False,
                analysis=session["analysis"]
            )
            
        except Exception as e:
            raise HTTPException(
                status_code=500,
                detail=f"AI分析失败: {str(e)}"
            )
    
    else:
        # 后续回答：更新清晰度，判断是否需要继续追问
        try:
            # 更新上下文
            calibration.update_context(
                session_id=session_id,
                role="user",
                content=request.message
            )
            
            # 提升清晰度（模拟，实际应根据AI分析）
            clarity_boost = 10 + min(len(request.message) // 20, 15)
            session["clarity_score"] = min(100, session["clarity_score"] + clarity_boost)
            
            # 判断是否达到阈值
            if session["clarity_score"] >= 80:
                # 生成最终校准报告
                report = await _generate_calibration_report(client, session)
                
                # 添加AI消息
                session["messages"].append({
                    "role": "assistant",
                    "content": report
                })
                
                return SimpleChatResponse(
                    session_id=session_id,
                    message=report,
                    clarity_score=session["clarity_score"],
                    is_complete=True,
                    analysis=session["analysis"]
                )
            else:
                # 继续追问
                result = await calibration.generate_questions(session_id, num_questions=1)
                
                if result.questions:
                    ai_message = f"感谢你的回答！当前清晰度：{session['clarity_score']}/100\n\n{result.questions[0].question}"
                else:
                    ai_message = f"感谢你的回答！当前清晰度：{session['clarity_score']}/100\n\n还有其他需要补充的信息吗？"
                
                # 添加AI消息
                session["messages"].append({
                    "role": "assistant",
                    "content": ai_message
                })
                
                return SimpleChatResponse(
                    session_id=session_id,
                    message=ai_message,
                    clarity_score=session["clarity_score"],
                    is_complete=False,
                    analysis=session["analysis"]
                )
                
        except Exception as e:
            raise HTTPException(
                status_code=500,
                detail=f"对话处理失败: {str(e)}"
            )


async def _generate_calibration_report(client: BailianClient, session: dict) -> str:
    """生成校准报告"""
    
    original = session["original_question"]
    analysis = session.get("analysis", {})
    messages = session["messages"]
    
    # 构建报告生成提示
    report_prompt = f"""你是一位专业的问题校准专家。请根据以下信息生成一份简洁的问题校准报告。

原始问题：
{original}

分析结果：
- 问题类型：{analysis.get('question_type', '未知')}
- 核心变量：{', '.join(analysis.get('core_variables', []))}
- 认知偏差：{', '.join(analysis.get('cognitive_biases', [])) or '无'}
- 最终清晰度：{session['clarity_score']}/100

对话历史：
{json.dumps([{'role': m['role'], 'content': m['content'][:100]} for m in messages[-6:]], ensure_ascii=False, indent=2)}

请生成一份校准报告，包含：
1. 问题重述（更清晰的版本）
2. 核心洞察（关键发现）
3. 建议下一步（不提供解决方案，只建议思考方向）

格式要求：简洁明了，使用emoji增加可读性。字数控制在300字以内。"""

    try:
        response = await client.chat_completion(
            messages=[{"role": "user", "content": report_prompt}],
            temperature=0.7
        )
        return response if isinstance(response, str) else str(response)
    except Exception:
        # 如果AI生成失败，返回默认报告
        return f"""📋 校准报告

✅ 问题清晰度：{session['clarity_score']}/100

🎯 问题重述：
{original}

💡 核心洞察：
通过对话，我们澄清了问题的关键要素和背景。

📌 建议下一步：
继续深入思考这个问题的核心目标是什么，以及有哪些可量化的成功标准。"""


@router.get("/session/{session_id}")
async def get_session(session_id: str):
    """获取会话信息"""
    if session_id not in _sessions:
        raise HTTPException(status_code=404, detail="会话不存在")
    
    session = _sessions[session_id]
    return {
        "session_id": session_id,
        "clarity_score": session["clarity_score"],
        "original_question": session["original_question"],
        "message_count": len(session["messages"]),
        "analysis": session["analysis"]
    }