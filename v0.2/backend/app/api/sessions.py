"""
Sessions API 路由 - 会话管理
包含：创建会话、获取会话列表、获取详情、删除会话
"""

from typing import List, Optional
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from sqlalchemy import func

from app.api.auth import get_current_active_user
from app.core.database import get_db
from app.models.user import User, Session as SessionModel, ConversationTurn
from app.schemas.schemas import (
    SessionCreate,
    SessionResponse,
    SessionListResponse,
    SessionUpdate
)
from app.services.ai_service import BailianClient, QuestionAnalyzer
from app.core.config import get_settings

# =============================================================================
# 配置
# =============================================================================

router = APIRouter(prefix="/sessions", tags=["sessions"])
settings = get_settings()

# =============================================================================
# 工具函数
# =============================================================================

def get_ai_services():
    """获取AI服务实例"""
    client = BailianClient(
        api_key=settings.BAILIAN_API_KEY,
        base_url=settings.BAILIAN_BASE_URL,
        model=settings.DEFAULT_MODEL
    )
    analyzer = QuestionAnalyzer(client)
    return client, analyzer


def generate_session_title(question: str, max_length: int = 50) -> str:
    """根据问题生成会话标题"""
    # 简单实现：截取问题前50个字符
    title = question.strip()
    if len(title) > max_length:
        title = title[:max_length] + "..."
    return title


# =============================================================================
# API 端点
# =============================================================================

@router.post("", response_model=SessionResponse, status_code=status.HTTP_201_CREATED)
async def create_session(
    session_data: SessionCreate,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    创建新会话
    
    - **title**: 会话标题（可选，不传则自动生成）
    - **description**: 会话描述（可选）
    - **model**: 使用的AI模型（可选，默认使用用户偏好或系统默认）
    - **system_prompt**: 系统提示词（可选）
    
    返回创建的会话信息
    """
    # 获取用户资料以确定默认模型
    user_profile = current_user.profile
    preferred_model = session_data.model or (
        user_profile.preferred_model if user_profile else settings.DEFAULT_MODEL
    )
    
    # 创建会话
    db_session = SessionModel(
        user_id=current_user.id,
        title=session_data.title,
        description=session_data.description,
        model=preferred_model,
        system_prompt=session_data.system_prompt,
        is_active=1,
        meta_data={}
    )
    
    db.add(db_session)
    db.commit()
    db.refresh(db_session)
    
    return db_session


@router.post("/with-question", response_model=dict, status_code=status.HTTP_201_CREATED)
async def create_session_with_question(
    question: str,
    model: Optional[str] = None,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    创建新会话并初始化第一个问题
    
    - **question**: 初始问题（必需）
    - **model**: 使用的AI模型（可选）
    
    返回会话信息和AI分析结果
    """
    # 获取用户资料
    user_profile = current_user.profile
    preferred_model = model or (
        user_profile.preferred_model if user_profile else settings.DEFAULT_MODEL
    )
    
    # 自动生成标题
    title = generate_session_title(question)
    
    # 创建会话
    db_session = SessionModel(
        user_id=current_user.id,
        title=title,
        model=preferred_model,
        is_active=1,
        meta_data={"original_question": question}
    )
    
    db.add(db_session)
    db.commit()
    db.refresh(db_session)
    
    # 分析问题结构
    try:
        client, analyzer = get_ai_services()
        analysis = await analyzer.analyze_structure(question)
        
        # 存储分析结果到会话元数据
        db_session.meta_data.update({
            "analysis": {
                "question_type": analysis.question_type.value,
                "core_variables": analysis.core_variables,
                "cognitive_biases": analysis.cognitive_biases,
                "clarity_score": analysis.clarity_score
            }
        })
        db.commit()
        
        return {
            "session": {
                "id": db_session.id,
                "user_id": db_session.user_id,
                "title": db_session.title,
                "description": db_session.description,
                "model": db_session.model,
                "is_active": db_session.is_active,
                "meta_data": db_session.meta_data,
                "created_at": db_session.created_at,
                "updated_at": db_session.updated_at
            },
            "analysis": {
                "question_type": analysis.question_type.value,
                "core_variables": analysis.core_variables,
                "cognitive_biases": analysis.cognitive_biases,
                "clarity_score": analysis.clarity_score
            }
        }
    except Exception as e:
        # 即使分析失败也返回会话
        return {
            "session": {
                "id": db_session.id,
                "user_id": db_session.user_id,
                "title": db_session.title,
                "description": db_session.description,
                "model": db_session.model,
                "is_active": db_session.is_active,
                "meta_data": db_session.meta_data,
                "created_at": db_session.created_at,
                "updated_at": db_session.updated_at
            },
            "analysis": None,
            "analysis_error": str(e)
        }


@router.get("", response_model=List[SessionListResponse])
async def get_sessions(
    skip: int = 0,
    limit: int = 20,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    获取当前用户的会话列表
    
    - **skip**: 跳过数量（分页）
    - **limit**: 返回数量限制（默认20）
    
    返回会话列表（包含对话轮次数）
    """
    # 查询会话和对话轮次数量
    sessions_with_count = db.query(
        SessionModel,
        func.count(ConversationTurn.id).label("turn_count")
    ).outerjoin(
        ConversationTurn, SessionModel.id == ConversationTurn.session_id
    ).filter(
        SessionModel.user_id == current_user.id
    ).group_by(
        SessionModel.id
    ).order_by(
        SessionModel.updated_at.desc()
    ).offset(skip).limit(limit).all()
    
    result = []
    for session, turn_count in sessions_with_count:
        session_data = {
            "id": session.id,
            "user_id": session.user_id,
            "title": session.title,
            "description": session.description,
            "model": session.model,
            "system_prompt": session.system_prompt,
            "is_active": session.is_active,
            "meta_data": session.meta_data,
            "created_at": session.created_at,
            "updated_at": session.updated_at,
            "turn_count": turn_count
        }
        result.append(session_data)
    
    return result


@router.get("/{session_id}", response_model=dict)
async def get_session(
    session_id: UUID,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    获取会话详情
    
    - **session_id**: 会话ID
    
    返回会话详情（包含所有对话轮次）
    """
    # 查询会话
    session = db.query(SessionModel).filter(
        SessionModel.id == session_id,
        SessionModel.user_id == current_user.id
    ).first()
    
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Session not found"
        )
    
    # 查询所有对话轮次
    turns = db.query(ConversationTurn).filter(
        ConversationTurn.session_id == session_id
    ).order_by(ConversationTurn.created_at.asc()).all()
    
    turns_data = []
    for turn in turns:
        turns_data.append({
            "id": turn.id,
            "session_id": turn.session_id,
            "role": turn.role,
            "content": turn.content,
            "reasoning_content": turn.reasoning_content,
            "tokens_used": turn.tokens_used,
            "latency_ms": turn.latency_ms,
            "meta_data": turn.meta_data,
            "created_at": turn.created_at
        })
    
    return {
        "session": {
            "id": session.id,
            "user_id": session.user_id,
            "title": session.title,
            "description": session.description,
            "model": session.model,
            "system_prompt": session.system_prompt,
            "is_active": session.is_active,
            "meta_data": session.meta_data,
            "created_at": session.created_at,
            "updated_at": session.updated_at
        },
        "turns": turns_data,
        "turn_count": len(turns_data)
    }


@router.put("/{session_id}", response_model=SessionResponse)
async def update_session(
    session_id: UUID,
    session_data: SessionUpdate,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    更新会话信息
    
    - **session_id**: 会话ID
    - **title**: 新标题（可选）
    - **description**: 新描述（可选）
    - **is_active**: 激活状态（可选）
    """
    # 查询会话
    session = db.query(SessionModel).filter(
        SessionModel.id == session_id,
        SessionModel.user_id == current_user.id
    ).first()
    
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Session not found"
        )
    
    # 更新字段
    if session_data.title is not None:
        session.title = session_data.title
    if session_data.description is not None:
        session.description = session_data.description
    if session_data.is_active is not None:
        session.is_active = session_data.is_active
    
    db.commit()
    db.refresh(session)
    
    return session


@router.delete("/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_session(
    session_id: UUID,
    current_user: User = Depends(get_current_active_user),
    db: Session = Depends(get_db)
):
    """
    删除会话
    
    - **session_id**: 会话ID
    
    删除会话及其所有对话轮次（级联删除）
    """
    # 查询会话
    session = db.query(SessionModel).filter(
        SessionModel.id == session_id,
        SessionModel.user_id == current_user.id
    ).first()
    
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Session not found"
        )
    
    # 删除会话（对话轮次会自动级联删除）
    db.delete(session)
    db.commit()
    
    return None