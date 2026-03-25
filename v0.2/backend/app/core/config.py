from pydantic_settings import BaseSettings
from functools import lru_cache


class Settings(BaseSettings):
    # Database
    DATABASE_URL: str = "postgresql://user:password@localhost:5432/questionos"
    
    # Security
    SECRET_KEY: str = "your-secret-key"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24 * 7  # 7 days
    
    # CORS
    CORS_ORIGINS: str = "http://localhost:3000,http://127.0.0.1:3000"
    
    # AI - 阿里云百炼
    BAILIAN_API_KEY: str = ""
    BAILIAN_BASE_URL: str = "https://coding.dashscope.aliyuncs.com/v1"
    DEFAULT_MODEL: str = "qwen-max"
    
    # App
    APP_NAME: str = "QuestionOS API"
    DEBUG: bool = False
    
    class Config:
        env_file = ".env"
        
    def get_cors_origins(self) -> list:
        """获取 CORS 允许来源列表"""
        return [origin.strip() for origin in self.CORS_ORIGINS.split(",")]


@lru_cache()
def get_settings() -> Settings:
    return Settings()
