# Schemas 包 - Pydantic 模型定义

from pydantic import BaseModel, EmailStr, ConfigDict
from typing import Optional, List, Dict, Any
from datetime import datetime
from uuid import UUID


# ==================== User Schemas ====================

class UserBase(BaseModel):
    email: EmailStr
    username: Optional[str] = None


class UserCreate(UserBase):
    """用户注册请求模型"""
    password: str


class UserLogin(BaseModel):
    """用户登录请求模型"""
    email: EmailStr
    password: str


class UserUpdate(BaseModel):
    username: Optional[str] = None
    email: Optional[EmailStr] = None


class UserResponse(BaseModel):
    """用户响应模型（不包含敏感信息）"""
    id: UUID
    email: EmailStr
    username: Optional[str] = None
    is_active: int
    created_at: datetime
    updated_at: datetime
    
    model_config = ConfigDict(from_attributes=True)


# ==================== UserProfile Schemas ====================

class UserProfileBase(BaseModel):
    display_name: Optional[str] = None
    bio: Optional[str] = None
    preferred_model: Optional[str] = "qwen-max"
    system_prompt: Optional[str] = None


class UserProfileCreate(UserProfileBase):
    pass


class UserProfileUpdate(UserProfileBase):
    avatar_url: Optional[str] = None
    preferences: Optional[Dict[str, Any]] = None


class UserProfileResponse(UserProfileBase):
    id: UUID
    user_id: UUID
    avatar_url: Optional[str]
    preferences: Dict[str, Any]
    created_at: datetime
    updated_at: datetime
    
    model_config = ConfigDict(from_attributes=True)


# ==================== Session Schemas ====================

class SessionBase(BaseModel):
    title: Optional[str] = None
    description: Optional[str] = None
    model: Optional[str] = "qwen-max"
    system_prompt: Optional[str] = None


class SessionCreate(SessionBase):
    pass


class SessionUpdate(BaseModel):
    title: Optional[str] = None
    description: Optional[str] = None
    is_active: Optional[int] = None


class SessionResponse(SessionBase):
    id: UUID
    user_id: UUID
    is_active: int
    meta_data: Dict[str, Any]
    created_at: datetime
    updated_at: datetime
    
    model_config = ConfigDict(from_attributes=True)


class SessionListResponse(SessionResponse):
    turn_count: Optional[int] = None  # 对话轮次数


# ==================== ConversationTurn Schemas ====================

class ConversationTurnBase(BaseModel):
    role: str  # 'user', 'assistant', 'system'
    content: str


class ConversationTurnCreate(ConversationTurnBase):
    pass


class ConversationTurnResponse(ConversationTurnBase):
    id: UUID
    session_id: UUID
    reasoning_content: Optional[str] = None
    tokens_used: Optional[int] = None
    latency_ms: Optional[int] = None
    meta_data: Dict[str, Any]
    created_at: datetime
    
    model_config = ConfigDict(from_attributes=True)


# ==================== Chat Schemas ====================

class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    """聊天请求模型"""
    session_id: Optional[UUID] = None  # 可选，不传则创建新会话
    message: str
    model: Optional[str] = None  # 可选，覆盖默认模型
    stream: bool = False


class ChatResponse(BaseModel):
    """聊天响应模型"""
    session_id: UUID
    message: str
    reasoning_content: Optional[str] = None
    model: str
    tokens_used: Optional[int] = None
    latency_ms: Optional[int] = None
    created_at: Optional[datetime] = None


class StreamChatResponse(BaseModel):
    delta: str
    finish_reason: Optional[str] = None


# ==================== AI Analysis Schemas ====================

class StructureAnalysisResponse(BaseModel):
    """问题结构分析响应"""
    problem_type: str  # 问题类型: technical, conceptual, creative, analytical, etc.
    complexity_level: str  # 复杂度: low, medium, high
    key_concepts: List[str]  # 关键概念列表
    required_context: List[str]  # 需要的上下文
    suggested_approach: str  # 建议的处理方式
    estimated_turns: int  # 预计需要的对话轮数


class FollowUpQuestionsResponse(BaseModel):
    """追问问题响应"""
    questions: List[str]  # 追问问题列表
    reasoning: Optional[str] = None  # 生成追问的理由
    priority: Optional[List[int]] = None  # 问题优先级（1-5，5最高）


# ==================== Auth Schemas ====================

class Token(BaseModel):
    """JWT Token响应"""
    access_token: str
    token_type: str = "bearer"
    expires_in: Optional[int] = None  # 过期时间（秒）


class TokenData(BaseModel):
    """Token数据模型"""
    email: Optional[str] = None
    user_id: Optional[UUID] = None
    exp: Optional[datetime] = None


class LoginRequest(BaseModel):
    """登录请求（已废弃，请使用UserLogin）"""
    email: EmailStr
    password: str


class RegisterRequest(UserCreate):
    """注册请求（已废弃，请使用UserCreate）"""
    pass
