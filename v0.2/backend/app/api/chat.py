"""
Chat API 路由 - 对话聊天（核心功能）
包含：发送消息、获取AI响应、流式响应
"""

import time
import json
from typing import AsyncGenerator, Optional
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.api.auth import get_current_active_user
from app.core.database import get_db
from app.core.config import get_settings
from app.models.user import User, Session as SessionModel, ConversationTurn
from app.schemas.schemas import ChatRequest, ChatResponse
from app.services.ai_service import (
    BailianClient, 
    CalibrationChat, 
    QuestionAnalyzer
)

# =============================================================================
# 配置
# =============================================================================

router = APIRouter(tags=["chat"])
settings = get_settings()

# 全局AI服务实例（复用连接）
_ai_client: Optional[BailianClient] = None
_ai_analyzer: Optional[QuestionAnalyzer] = None
_calibration_chat: Optional[CalibrationChat] = None


def get_ai_services():
    """获取或初始化AI服务实例"""
    global _ai_client, _ai_analyzer, _calibration_chat
    
    if _ai_client is None:
        _ai_client = BailianClient(
            api_key=settings.BAILIAN_API_KEY,
            base_url=settings.BAILIAN_BASE_URL,
            model=settings.DEFAULT_MODEL
        )
        _ai_analyzer = QuestionAnalyzer(_ai_client)
        _calibration_chat = CalibrationChat(_ai_client)
    
    return _ai_client, _ai_analyzer, _calibration_chat


# =============================================================================
# 工具函数
# =============================================================================

def save_turn(
    db: Session,
    session_id: UUID,
    role: str,
    content: str,
    reasoning_content: Optional[str] = None,
    tokens_used: Optional[int] = None,
    latency_ms: Optional[int] = None,
    meta_data: Optional[dict] = None
) -> ConversationTurn:
    """保存对话轮次到数据库"""
    turn = ConversationTurn(
        session_id=session_id,
        role=role,
        content=content,
        reasoning_content=reasoning_content,
        tokens_used=tokens_used,
        latency_ms=latency_ms,
        meta_data=meta_data or {}
    )
    db.add(turn)
    db.commit()
    db.refresh(turn)
    return turn


def build_chat_messages(
    session: SessionModel,
    db: Session,
    system_prompt_override: Optional[str] = None
) -> list:
    """构建聊天消息历史"""
    messages = []
    
    # 添加系统提示词
    system_prompt = system_prompt_override or session.system_prompt
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    
    # 添加历史对话（最近20轮）
    turns = db.query(ConversationTurn).filter(
        ConversationTurn.session_id == session.id
    ).order_by(ConversationTurn.created_at.asc()).limit(20).all()
    
    for turn in turns:
        messages.append({
            "role": turn.role,
            "content": turn.content
        })
    
    return messages


# =============================================================================
# API 端点
# =============================================================================

@router.post("/sessions/{session_id}/chat", response_model=ChatResponse)
async def chat(
    session_id: UUID,
    chat_data: ChatRequest,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    发送消息并获取AI响应（非流式）
    
    - **session_id**: 会话ID
    - **message**: 用户消息内容
    - **model**: 可选，覆盖默认模型
    
    返回AI响应
    """
    start_time = time.time()
    
    # 验证会话存在且属于当前用户
    session = db.query(SessionModel).filter(
        SessionModel.id == session_id,
        SessionModel.user_id == current_user.id
    ).first()
    
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Session not found"
        )
    
    if session.is_active != 1:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Session is inactive"
        )
    
    # 获取AI服务
    client, analyzer, calibration = get_ai_services()
    
    # 保存用户消息
    save_turn(
        db=db,
        session_id=session_id,
        role="user",
        content=chat_data.message
    )
    
    # 构建消息历史
    messages = build_chat_messages(session, db)
    messages.append({"role": "user", "content": chat_data.message})
    
    # 确定使用的模型
    model = chat_data.model or session.model or settings.DEFAULT_MODEL
    
    try:
        # 调用AI服务
        response = await client.chat_completion(
            messages=messages,
            model=model,
            temperature=0.7,
            stream=False
        )
        
        latency_ms = int((time.time() - start_time) * 1000)
        
        # 解析响应（提取内容和推理过程）
        ai_content = response if isinstance(response, str) else str(response)
        reasoning_content = None
        
        # 某些模型可能返回JSON格式，尝试解析
        try:
            if ai_content.strip().startswith("{"):
                json_data = json.loads(ai_content)
                if "content" in json_data:
                    ai_content = json_data["content"]
                if "reasoning" in json_data:
                    reasoning_content = json_data["reasoning"]
        except:
            pass  # 非JSON格式，使用原始内容
        
        # 保存AI响应
        turn = save_turn(
            db=db,
            session_id=session_id,
            role="assistant",
            content=ai_content,
            reasoning_content=reasoning_content,
            latency_ms=latency_ms,
            meta_data={"model": model}
        )
        
        return ChatResponse(
            session_id=session_id,
            message=ai_content,
            reasoning_content=reasoning_content,
            model=model,
            tokens_used=turn.tokens_used
        )
        
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"AI service error: {str(e)}"
        )


@router.post("/sessions/{session_id}/chat/stream")
async def chat_stream(
    session_id: UUID,
    chat_data: ChatRequest,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    发送消息并获取AI流式响应
    
    - **session_id**: 会话ID
    - **message**: 用户消息内容
    - **model**: 可选，覆盖默认模型
    
    返回SSE格式的流式响应
    """
    start_time = time.time()
    
    # 验证会话
    session = db.query(SessionModel).filter(
        SessionModel.id == session_id,
        SessionModel.user_id == current_user.id
    ).first()
    
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Session not found"
        )
    
    if session.is_active != 1:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Session is inactive"
        )
    
    # 获取AI服务
    client, analyzer, calibration = get_ai_services()
    
    # 保存用户消息
    save_turn(
        db=db,
        session_id=session_id,
        role="user",
        content=chat_data.message
    )
    
    # 构建消息历史
    messages = build_chat_messages(session, db)
    messages.append({"role": "user", "content": chat_data.message})
    
    # 确定使用的模型
    model = chat_data.model or session.model or settings.DEFAULT_MODEL
    
    async def generate_stream() -> AsyncGenerator[str, None]:
        """生成流式响应"""
        full_content = []
        
        try:
            # 发送开始标记
            yield f"data: {json.dumps({'type': 'start', 'session_id': str(session_id)})}\n\n"
            
            # 调用流式API
            response = await client.chat_completion(
                messages=messages,
                model=model,
                temperature=0.7,
                stream=True
            )
            
            # 流式输出
            async for chunk in response:
                if chunk:
                    full_content.append(chunk)
                    data = {
                        "type": "delta",
                        "delta": chunk,
                        "finish_reason": None
                    }
                    yield f"data: {json.dumps(data)}\n\n"
            
            # 计算延迟
            latency_ms = int((time.time() - start_time) * 1000)
            
            # 发送完成标记
            yield f"data: {json.dumps({'type': 'done', 'finish_reason': 'stop'})}\n\n"
            
            # 保存完整响应到数据库
            final_content = "".join(full_content)
            save_turn(
                db=db,
                session_id=session_id,
                role="assistant",
                content=final_content,
                latency_ms=latency_ms,
                meta_data={"model": model, "stream": True}
            )
            
        except Exception as e:
            # 发送错误
            yield f"data: {json.dumps({'type': 'error', 'message': str(e)})}\n\n"
    
    return StreamingResponse(
        generate_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )


@router.post("/sessions/{session_id}/calibrate")
async def calibrate_question(
    session_id: UUID,
    num_questions: int = 3,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    生成校准追问问题
    
    使用CalibrationChat分析用户原始问题并生成追问
    
    - **session_id**: 会话ID
    - **num_questions**: 生成追问数量（默认3个）
    
    返回追问问题和分析结果
    """
    # 验证会话
    session = db.query(SessionModel).filter(
        SessionModel.id == session_id,
        SessionModel.user_id == current_user.id
    ).first()
    
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Session not found"
        )
    
    # 获取原始问题
    original_question = session.meta_data.get("original_question") if session.meta_data else None
    
    if not original_question:
        # 尝试从第一个用户消息获取
        first_turn = db.query(ConversationTurn).filter(
            ConversationTurn.session_id == session_id,
            ConversationTurn.role == "user"
        ).order_by(ConversationTurn.created_at.asc()).first()
        
        if first_turn:
            original_question = first_turn.content
        else:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="No question found to calibrate"
            )
    
    # 获取AI服务
    client, analyzer, calibration = get_ai_services()
    
    try:
        # 分析问题结构
        analysis = await analyzer.analyze_structure(original_question)
        
        # 创建或更新上下文
        session_id_str = str(session_id)
        existing_context = calibration.get_context(session_id_str)
        
        if existing_context:
            calibration.update_context(
                session_id=session_id_str,
                role="system",
                content=f"更新分析: 清晰度 {analysis.clarity_score}"
            )
        else:
            calibration.create_context(session_id_str, original_question, analysis)
        
        # 生成追问问题
        result = await calibration.generate_questions(session_id_str, num_questions)
        
        return {
            "session_id": session_id,
            "original_question": original_question,
            "analysis": {
                "question_type": analysis.question_type.value,
                "core_variables": analysis.core_variables,
                "cognitive_biases": analysis.cognitive_biases,
                "clarity_score": analysis.clarity_score
            },
            "follow_up_questions": [
                {
                    "question": q.question,
                    "purpose": q.purpose,
                    "target_bias": q.target_bias
                }
                for q in result.questions
            ],
            "expected_clarity_improvement": result.clarity_change
        }
        
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Calibration error: {str(e)}"
        )


@router.get("/sessions/{session_id}/turns")
async def get_session_turns(
    session_id: UUID,
    skip: int = 0,
    limit: int = 50,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    获取会话的所有对话轮次
    
    - **session_id**: 会话ID
    - **skip**: 跳过数量（分页）
    - **limit**: 返回数量限制（默认50）
    """
    # 验证会话
    session = db.query(SessionModel).filter(
        SessionModel.id == session_id,
        SessionModel.user_id == current_user.id
    ).first()
    
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Session not found"
        )
    
    # 查询对话轮次
    turns = db.query(ConversationTurn).filter(
        ConversationTurn.session_id == session_id
    ).order_by(
        ConversationTurn.created_at.asc()
    ).offset(skip).limit(limit).all()
    
    return {
        "session_id": session_id,
        "turns": [
            {
                "id": turn.id,
                "role": turn.role,
                "content": turn.content,
                "reasoning_content": turn.reasoning_content,
                "tokens_used": turn.tokens_used,
                "latency_ms": turn.latency_ms,
                "created_at": turn.created_at
            }
            for turn in turns
        ],
        "total": len(turns)
    }