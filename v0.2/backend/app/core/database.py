"""
Database 模块 - 数据库连接和会话管理
提供 SQLAlchemy 引擎、会话工厂和依赖注入函数
"""

from sqlalchemy import create_engine, text
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, Session
from sqlalchemy.exc import SQLAlchemyError, OperationalError
from typing import Generator
import logging

from app.core.config import get_settings

settings = get_settings()
logger = logging.getLogger(__name__)

# ==================== 数据库引擎配置 ====================

def get_engine():
    """
    获取配置好的 SQLAlchemy 引擎
    
    Returns:
        Engine: SQLAlchemy 引擎实例
    """
    return create_engine(
        settings.DATABASE_URL,
        pool_pre_ping=True,  # 连接前 ping 检查，避免使用失效连接
        pool_recycle=3600,   # 1小时后回收连接
        pool_size=10,        # 连接池大小
        max_overflow=20,     # 最大溢出连接数
        echo=settings.DEBUG  # DEBUG 模式下打印 SQL
    )


# 全局引擎实例
engine = get_engine()

# 会话工厂
SessionLocal = sessionmaker(
    autocommit=False,
    autoflush=False,
    bind=engine
)

# 声明基类
Base = declarative_base()


# ==================== 依赖注入 ====================

def get_db() -> Generator[Session, None, None]:
    """
    获取数据库会话的依赖注入函数
    
    用于 FastAPI 依赖注入系统，确保每个请求都有独立的数据库会话，
    并在请求结束后自动关闭。
    
    Yields:
        Session: SQLAlchemy 数据库会话
    
    Example:
        @app.get("/items/")
        def read_items(db: Session = Depends(get_db)):
            return db.query(Item).all()
    """
    db = SessionLocal()
    try:
        yield db
        db.commit()  # 自动提交事务（如果没有异常）
    except SQLAlchemyError as e:
        db.rollback()  # 发生异常时回滚
        logger.error(f"Database error: {e}")
        raise
    finally:
        db.close()  # 确保会话关闭


def get_db_session() -> Session:
    """
    直接获取数据库会话（非生成器版本）
    
    用于后台任务或脚本中直接获取会话。
    注意：使用此方法需要手动管理会话生命周期（commit/close）。
    
    Returns:
        Session: SQLAlchemy 数据库会话
    
    Example:
        db = get_db_session()
        try:
            user = db.query(User).first()
            db.commit()
        finally:
            db.close()
    """
    return SessionLocal()


# ==================== 数据库初始化 ====================

def create_tables() -> bool:
    """
    创建所有数据库表
    
    根据 models 中定义的模型自动创建对应的表结构。
    如果表已存在则不会重复创建。
    
    Returns:
        bool: 创建成功返回 True，失败返回 False
    """
    try:
        # 导入所有模型确保它们被注册到 Base.metadata
        from app.models import User, UserProfile, Session, ConversationTurn
        
        # 创建所有表
        Base.metadata.create_all(bind=engine)
        logger.info("Database tables created successfully")
        return True
    
    except OperationalError as e:
        logger.error(f"Database connection error: {e}")
        return False
    except Exception as e:
        logger.error(f"Error creating tables: {e}")
        return False


def drop_tables() -> bool:
    """
    删除所有数据库表（危险操作！）
    
    仅用于开发/测试环境重置数据库。
    
    Returns:
        bool: 删除成功返回 True，失败返回 False
    """
    try:
        if not settings.DEBUG:
            logger.warning("Cannot drop tables in non-DEBUG mode")
            return False
        
        Base.metadata.drop_all(bind=engine)
        logger.warning("All database tables dropped")
        return True
    except Exception as e:
        logger.error(f"Error dropping tables: {e}")
        return False


def test_connection() -> bool:
    """
    测试数据库连接
    
    Returns:
        bool: 连接成功返回 True，失败返回 False
    """
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        logger.info("Database connection test successful")
        return True
    except Exception as e:
        logger.error(f"Database connection test failed: {e}")
        return False


# ==================== 便捷函数 ====================

def init_database() -> bool:
    """
    初始化数据库 - 测试连接并创建表
    
    Returns:
        bool: 初始化成功返回 True
    """
    if not test_connection():
        logger.error("Cannot connect to database")
        return False
    
    return create_tables()
