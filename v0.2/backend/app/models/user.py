from sqlalchemy import Column, String, DateTime, Text, JSON, ForeignKey, Integer
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import relationship
from app.core.database import Base
import uuid
from datetime import datetime


class User(Base):
    """用户模型"""
    __tablename__ = "users"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email = Column(String(255), unique=True, nullable=False, index=True)
    hashed_password = Column(String(255), nullable=False)
    username = Column(String(100), nullable=True)
    is_active = Column(Integer, default=1)  # 1=active, 0=inactive
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    # 关系
    sessions = relationship("Session", back_populates="user", cascade="all, delete-orphan")
    profile = relationship("UserProfile", back_populates="user", uselist=False, cascade="all, delete-orphan")


class UserProfile(Base):
    """用户资料模型"""
    __tablename__ = "user_profiles"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id = Column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), unique=True, nullable=False)
    
    # 基本信息
    display_name = Column(String(100), nullable=True)
    avatar_url = Column(String(500), nullable=True)
    bio = Column(Text, nullable=True)
    
    # AI 个性化设置
    preferred_model = Column(String(50), default="qwen-max")  # qwen-max, qwen-plus, glm-5
    system_prompt = Column(Text, nullable=True)  # 用户自定义系统提示词
    
    # 元数据
    preferences = Column(JSON, default=dict)  # 其他偏好设置
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    # 关系
    user = relationship("User", back_populates="profile")


class Session(Base):
    """对话会话模型"""
    __tablename__ = "sessions"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id = Column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    
    # 会话信息
    title = Column(String(200), nullable=True)  # 会话标题（可自动生成）
    description = Column(Text, nullable=True)
    
    # 模型配置
    model = Column(String(50), default="qwen-max")
    system_prompt = Column(Text, nullable=True)  # 会话级别的系统提示词
    
    # 状态
    is_active = Column(Integer, default=1)
    
    # 元数据
    meta_data = Column(JSON, default=dict)  # 额外元数据
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    # 关系
    user = relationship("User", back_populates="sessions")
    turns = relationship("ConversationTurn", back_populates="session", cascade="all, delete-orphan", order_by="ConversationTurn.created_at")


class ConversationTurn(Base):
    """对话轮次模型 - 存储用户和AI的每一次交互"""
    __tablename__ = "conversation_turns"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    session_id = Column(UUID(as_uuid=True), ForeignKey("sessions.id", ondelete="CASCADE"), nullable=False, index=True)
    
    # 消息内容
    role = Column(String(20), nullable=False)  # 'user', 'assistant', 'system'
    content = Column(Text, nullable=False)
    
    # 可选的思考过程（某些模型支持）
    reasoning_content = Column(Text, nullable=True)
    
    # 元数据
    tokens_used = Column(Integer, nullable=True)  # 使用的token数
    latency_ms = Column(Integer, nullable=True)  # 响应延迟（毫秒）
    meta_data = Column(JSON, default=dict)  # 其他元数据（如模型参数、工具调用等）
    
    created_at = Column(DateTime, default=datetime.utcnow)
    
    # 关系
    session = relationship("Session", back_populates="turns")
