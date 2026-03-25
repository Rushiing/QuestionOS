"""
Security 模块 - 认证和加密相关功能
提供 JWT Token 生成/验证、密码哈希等功能
"""

from datetime import datetime, timedelta, timezone
from typing import Optional, Dict, Any, Union
from passlib.context import CryptContext
from jose import JWTError, jwt
from fastapi import HTTPException, status
from pydantic import EmailStr
from uuid import UUID

from app.core.config import get_settings

settings = get_settings()

# 密码哈希上下文 - 使用 bcrypt 算法
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

# JWT 算法
ALGORITHM = "HS256"


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """
    验证明文密码是否与哈希密码匹配
    
    Args:
        plain_password: 用户输入的明文密码
        hashed_password: 数据库中存储的哈希密码
    
    Returns:
        bool: 密码是否匹配
    """
    return pwd_context.verify(plain_password, hashed_password)


def get_password_hash(password: str) -> str:
    """
    生成密码的哈希值
    
    Args:
        password: 明文密码
    
    Returns:
        str: 哈希后的密码
    
    Raises:
        ValueError: 密码为空或太短
    """
    if not password or len(password) < 6:
        raise ValueError("Password must be at least 6 characters long")
    
    return pwd_context.hash(password)


def create_access_token(
    token_data: Dict[str, Any],
    expires_delta: Optional[timedelta] = None
) -> str:
    """
    创建 JWT 访问令牌
    
    Args:
        token_data: 要编码到 token 中的数据 (如: {"sub": user_email, "user_id": str(user_id)})
        expires_delta: 过期时间增量，默认为设置中的 ACCESS_TOKEN_EXPIRE_MINUTES
    
    Returns:
        str: 编码后的 JWT token
    
    Raises:
        HTTPException: 创建 token 失败时
    """
    try:
        # 复制数据，避免修改原始数据
        to_encode = token_data.copy()
        
        # 计算过期时间
        if expires_delta:
            expire = datetime.now(timezone.utc) + expires_delta
        else:
            expire = datetime.now(timezone.utc) + timedelta(
                minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES
            )
        
        # 添加过期时间到数据
        to_encode.update({"exp": expire})
        
        # 生成 JWT token
        encoded_jwt = jwt.encode(to_encode, settings.SECRET_KEY, algorithm=ALGORITHM)
        
        return encoded_jwt
    
    except JWTError as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to create access token: {str(e)}"
        )
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Unexpected error creating token: {str(e)}"
        )


def decode_token(token: str) -> Optional[Dict[str, Any]]:
    """
    解码并验证 JWT Token
    
    Args:
        token: JWT token 字符串
    
    Returns:
        Optional[Dict]: 解码后的 token 数据，验证失败返回 None
    
    Raises:
        HTTPException: Token 过期或无效时
    """
    try:
        # 解码 token
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=[ALGORITHM])
        
        # 验证必要字段
        if payload.get("sub") is None and payload.get("email") is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Token missing required fields",
                headers={"WWW-Authenticate": "Bearer"},
            )
        
        return payload
    
    except jwt.ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has expired",
            headers={"WWW-Authenticate": "Bearer"},
        )
    except jwt.JWTClaimsError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token claims",
            headers={"WWW-Authenticate": "Bearer"},
        )
    except JWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not validate credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )


def get_token_expires_at(token: str) -> Optional[datetime]:
    """
    获取 token 的过期时间（不解密验证）
    
    Args:
        token: JWT token 字符串
    
    Returns:
        Optional[datetime]: 过期时间，解析失败返回 None
    """
    try:
        # 只解码不验证签名
        payload = jwt.get_unverified_claims(token)
        exp_timestamp = payload.get("exp")
        
        if exp_timestamp:
            return datetime.fromtimestamp(exp_timestamp, tz=timezone.utc)
        return None
    except Exception:
        return None


# ==================== 便捷函数 ====================

def create_user_token(user_id: Union[UUID, str], email: str) -> str:
    """
    为用户创建访问令牌
    
    Args:
        user_id: 用户ID
        email: 用户邮箱
    
    Returns:
        str: JWT token
    """
    token_data = {
        "sub": email,  # subject 标准字段
        "email": email,
        "user_id": str(user_id),
        "type": "access"
    }
    
    return create_access_token(token_data)
