"""
QuestionOS AI Service Core
阿里云百炼 API 集成服务
"""

import os
import json
from typing import List, Dict, Any, Optional, AsyncGenerator, Union
from dataclasses import dataclass, field
from enum import Enum
import httpx
from openai import AsyncOpenAI, OpenAIError


# =============================================================================
# Prompt 模板
# =============================================================================

STRUCTURE_ANALYSIS_PROMPT = """你是一位专业的问题结构分析师。请分析用户提出的问题，识别其结构特征和潜在的认知偏差。

请从以下维度分析问题：

1. **问题类型 (question_type)**: 
   - closed: 封闭式问题（是/否、选择类）
   - open: 开放式问题（需要详细回答）
   - hypothetical: 假设性问题（如果...会怎样）
   - comparative: 比较性问题（A vs B）
   - causal: 因果问题（为什么、如何导致）

2. **核心变量 (core_variables)**: 列出问题中涉及的关键变量、实体或概念

3. **认知偏差 (cognitive_biases)**: 识别问题中可能存在的认知偏差
   - anchoring: 锚定效应
   - framing: 框架效应
   - confirmation: 确认偏误
   - availability: 可得性启发
   - survivorship: 幸存者偏差
   - authority: 权威偏差
   - status_quo: 现状偏差
   - sunk_cost: 沉没成本谬误
   - false_dichotomy: 非此即彼谬误
   - leading_question: 诱导性问题

4. **清晰度评分 (clarity_score)**: 0-100，评估问题表述的清晰程度

请严格按照以下JSON格式返回分析结果：
{
    "question_type": "closed|open|hypothetical|comparative|causal",
    "core_variables": ["变量1", "变量2", ...],
    "cognitive_biases": ["bias1", "bias2", ...],
    "clarity_score": 75
}

注意：
- 只返回JSON格式，不要添加任何解释性文字
- 如果未发现认知偏差，返回空数组 []
- clarity_score 基于问题是否含糊、是否存在歧义、是否缺少必要上下文来评估
"""

QUESTION_GENERATION_PROMPT = """你是一位专业的校准对话专家。基于用户原始问题和之前的对话上下文，生成有针对性的追问问题。

你的任务是：
1. 澄清模糊或不明确的表述
2. 暴露隐藏的前提假设
3. 挑战可能存在的认知偏差
4. 获取必要的上下文信息

原始问题: {original_question}

当前对话上下文:
{context}

已检测到的认知偏差:
{detected_biases}

当前清晰度评分: {clarity_score}/100

请生成 {num_questions} 个追问问题，要求：
1. 问题应简洁明了，每次只问一个点
2. 优先处理最重要的认知偏差或模糊点
3. 问题要有建设性，帮助用户完善他们的问题

请严格按照以下JSON格式返回：
{
    "questions": [
        {
            "question": "追问问题文本",
            "purpose": "这个问题的目的（澄清/挑战偏差/获取上下文）",
            "target_bias": "针对的认知偏差类型，如果没有则填 null"
        }
    ],
    "detected_biases": ["bias1", "bias2", ...],
    "clarity_change": 5
}

注意：
- clarity_change 表示回答这些问题后预期清晰度提升的幅度（0-20）
- 只返回JSON格式，不要添加解释性文字
"""


# =============================================================================
# 数据模型
# =============================================================================

class QuestionType(str, Enum):
    """问题类型枚举"""
    CLOSED = "closed"
    OPEN = "open"
    HYPOTHETICAL = "hypothetical"
    COMPARATIVE = "comparative"
    CAUSAL = "causal"


class CognitiveBias(str, Enum):
    """认知偏差类型枚举"""
    ANCHORING = "anchoring"
    FRAMING = "framing"
    CONFIRMATION = "confirmation"
    AVAILABILITY = "availability"
    SURVIVORSHIP = "survivorship"
    AUTHORITY = "authority"
    STATUS_QUO = "status_quo"
    SUNK_COST = "sunk_cost"
    FALSE_DICHOTOMY = "false_dichotomy"
    LEADING_QUESTION = "leading_question"


@dataclass
class StructureAnalysisResult:
    """问题结构分析结果"""
    question_type: QuestionType
    core_variables: List[str]
    cognitive_biases: List[str]
    clarity_score: int
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "StructureAnalysisResult":
        return cls(
            question_type=QuestionType(data.get("question_type", "open")),
            core_variables=data.get("core_variables", []),
            cognitive_biases=data.get("cognitive_biases", []),
            clarity_score=data.get("clarity_score", 50)
        )


@dataclass
class FollowUpQuestion:
    """追问问题"""
    question: str
    purpose: str
    target_bias: Optional[str]


@dataclass
class QuestionGenerationResult:
    """问题生成结果"""
    questions: List[FollowUpQuestion]
    detected_biases: List[str]
    clarity_change: int
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "QuestionGenerationResult":
        questions = [
            FollowUpQuestion(
                question=q.get("question", ""),
                purpose=q.get("purpose", ""),
                target_bias=q.get("target_bias")
            )
            for q in data.get("questions", [])
        ]
        return cls(
            questions=questions,
            detected_biases=data.get("detected_biases", []),
            clarity_change=data.get("clarity_change", 0)
        )


@dataclass
class ChatMessage:
    """对话消息"""
    role: str  # "system", "user", "assistant"
    content: str
    timestamp: Optional[float] = None
    
    def to_dict(self) -> Dict[str, str]:
        return {"role": self.role, "content": self.content}


@dataclass
class ChatContext:
    """对话上下文"""
    original_question: str
    messages: List[ChatMessage] = field(default_factory=list)
    current_clarity_score: int = 50
    detected_biases: List[str] = field(default_factory=list)
    
    def add_message(self, role: str, content: str):
        """添加消息到上下文"""
        self.messages.append(ChatMessage(role=role, content=content))
    
    def get_messages_for_api(self) -> List[Dict[str, str]]:
        """获取API格式的消息列表"""
        return [msg.to_dict() for msg in self.messages]
    
    def format_context_string(self) -> str:
        """格式化上下文为字符串"""
        if not self.messages:
            return "暂无对话历史"
        
        lines = []
        for msg in self.messages[-10:]:  # 只取最近10条
            role_label = "用户" if msg.role == "user" else "助手"
            lines.append(f"{role_label}: {msg.content}")
        return "\n".join(lines)


# =============================================================================
# BailianClient 类
# =============================================================================

class BailianClient:
    """
    阿里云百炼 API 客户端
    使用 OpenAI SDK 兼容格式调用
    """
    
    DEFAULT_BASE_URL = "https://coding.dashscope.aliyuncs.com/v1"
    DEFAULT_MODEL = "qwen-plus"
    
    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        timeout: float = 60.0
    ):
        """
        初始化 BailianClient
        
        Args:
            api_key: API 密钥，默认从环境变量 DASHSCOPE_API_KEY 读取
            base_url: API 基础 URL
            model: 默认模型名称
            timeout: 请求超时时间（秒）
        """
        self.api_key = api_key or os.getenv("DASHSCOPE_API_KEY")
        if not self.api_key:
            raise ValueError("API key is required. Provide it directly or set DASHSCOPE_API_KEY environment variable.")
        
        self.base_url = base_url or self.DEFAULT_BASE_URL
        self.model = model or self.DEFAULT_MODEL
        self.timeout = timeout
        
        # 初始化 OpenAI 客户端
        self.client = AsyncOpenAI(
            api_key=self.api_key,
            base_url=self.base_url,
            timeout=httpx.Timeout(timeout)
        )
    
    async def chat_completion(
        self,
        messages: List[Dict[str, str]],
        model: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: Optional[int] = None,
        stream: bool = False,
        **kwargs
    ) -> Union[str, AsyncGenerator[str, None]]:
        """
        调用聊天补全 API
        
        Args:
            messages: 消息列表，格式为 [{"role": "user", "content": "..."}, ...]
            model: 模型名称，默认使用初始化时设置的模型
            temperature: 采样温度，0-2
            max_tokens: 最大生成token数
            stream: 是否使用流式响应
            **kwargs: 其他参数
            
        Returns:
            如果 stream=False，返回完整的响应字符串
            如果 stream=True，返回异步生成器，产生文本片段
            
        Raises:
            OpenAIError: API调用错误
            ValueError: 参数验证错误
        """
        try:
            response = await self.client.chat.completions.create(
                model=model or self.model,
                messages=messages,  # type: ignore
                temperature=temperature,
                max_tokens=max_tokens,
                stream=stream,
                **kwargs
            )
            
            if stream:
                return self._handle_streaming_response(response)
            else:
                # 非流式响应，直接返回内容
                content = response.choices[0].message.content or ""
                return content
                
        except OpenAIError as e:
            raise OpenAIError(f"Bailian API error: {str(e)}")
        except Exception as e:
            raise Exception(f"Unexpected error in chat_completion: {str(e)}")
    
    async def _handle_streaming_response(
        self, response
    ) -> AsyncGenerator[str, None]:
        """处理流式响应"""
        async for chunk in response:
            if chunk.choices and chunk.choices[0].delta.content:
                yield chunk.choices[0].delta.content
    
    async def simple_chat(
        self,
        prompt: str,
        system_prompt: Optional[str] = None,
        **kwargs
    ) -> str:
        """
        简化版聊天接口
        
        Args:
            prompt: 用户输入
            system_prompt: 系统提示词
            **kwargs: 其他参数传递给 chat_completion
            
        Returns:
            AI 回复内容
        """
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})
        
        result = await self.chat_completion(messages=messages, **kwargs)
        
        # 确保返回字符串
        if isinstance(result, str):
            return result
        else:
            # 流式响应，收集所有内容
            chunks = []
            async for chunk in result:
                chunks.append(chunk)
            return "".join(chunks)


# =============================================================================
# QuestionAnalyzer 类
# =============================================================================

class QuestionAnalyzer:
    """
    问题结构分析器
    使用 AI 分析用户问题的结构特征
    """
    
    def __init__(self, client: BailianClient):
        """
        初始化 QuestionAnalyzer
        
        Args:
            client: BailianClient 实例
        """
        self.client = client
    
    async def analyze_structure(
        self,
        question: str,
        context: Optional[str] = None
    ) -> StructureAnalysisResult:
        """
        分析问题结构
        
        Args:
            question: 用户问题文本
            context: 可选的额外上下文
            
        Returns:
            StructureAnalysisResult 包含分析结果
            
        Raises:
            ValueError: JSON 解析错误
            Exception: API 调用错误
        """
        # 构建分析提示
        analysis_prompt = f"{STRUCTURE_ANALYSIS_PROMPT}\n\n用户问题: {question}"
        if context:
            analysis_prompt += f"\n\n额外上下文: {context}"
        
        try:
            # 调用 API 进行分析
            response = await self.client.simple_chat(
                prompt=analysis_prompt,
                temperature=0.3,  # 低温度以获得更确定的结果
                max_tokens=500
            )
            
            # 提取 JSON 内容
            json_content = self._extract_json(response)
            
            # 解析 JSON
            data = json.loads(json_content)
            
            # 构建结果对象
            return StructureAnalysisResult.from_dict(data)
            
        except json.JSONDecodeError as e:
            raise ValueError(f"Failed to parse analysis result as JSON: {str(e)}. Response: {response}")
        except Exception as e:
            raise Exception(f"Error analyzing question structure: {str(e)}")
    
    def _extract_json(self, text: str) -> str:
        """
        从文本中提取 JSON 内容
        
        处理以下情况：
        - 纯 JSON 文本
        - Markdown 代码块包裹的 JSON
        - 混合文本中的 JSON
        """
        text = text.strip()
        
        # 尝试直接解析
        if text.startswith("{") and text.endswith("}"):
            return text
        
        # 查找 Markdown 代码块
        import re
        
        # 查找 ```json ... ``` 格式
        json_block_match = re.search(r'```(?:json)?\s*(\{[\s\S]*?\})\s*```', text)
        if json_block_match:
            return json_block_match.group(1)
        
        # 查找单独的 {...}
        json_match = re.search(r'(\{[\s\S]*?\})', text)
        if json_match:
            return json_match.group(1)
        
        # 如果没找到，返回原文本让调用者处理
        return text


# =============================================================================
# CalibrationChat 类
# =============================================================================

class CalibrationChat:
    """
    校准对话管理器
    管理追问生成和对话上下文
    """
    
    def __init__(self, client: BailianClient):
        """
        初始化 CalibrationChat
        
        Args:
            client: BailianClient 实例
        """
        self.client = client
        self.contexts: Dict[str, ChatContext] = {}  # 存储多个会话的上下文
    
    def create_context(
        self,
        session_id: str,
        original_question: str,
        initial_analysis: StructureAnalysisResult
    ) -> ChatContext:
        """
        创建新的对话上下文
        
        Args:
            session_id: 会话唯一标识
            original_question: 用户原始问题
            initial_analysis: 初始结构分析结果
            
        Returns:
            ChatContext 对象
        """
        context = ChatContext(
            original_question=original_question,
            current_clarity_score=initial_analysis.clarity_score,
            detected_biases=initial_analysis.cognitive_biases.copy()
        )
        
        # 添加系统消息
        context.add_message(
            "system",
            f"你正在帮助用户完善他们的问题。原始问题: {original_question}"
        )
        
        # 存储上下文
        self.contexts[session_id] = context
        
        return context
    
    def get_context(self, session_id: str) -> Optional[ChatContext]:
        """
        获取指定会话的上下文
        
        Args:
            session_id: 会话唯一标识
            
        Returns:
            ChatContext 对象，如果不存在则返回 None
        """
        return self.contexts.get(session_id)
    
    def update_context(
        self,
        session_id: str,
        role: str,
        content: str,
        clarity_score: Optional[int] = None,
        new_biases: Optional[List[str]] = None
    ) -> ChatContext:
        """
        更新对话上下文
        
        Args:
            session_id: 会话唯一标识
            role: 消息角色 ("user" 或 "assistant")
            content: 消息内容
            clarity_score: 更新的清晰度评分（可选）
            new_biases: 新发现的认知偏差（可选）
            
        Returns:
            更新后的 ChatContext 对象
            
        Raises:
            KeyError: 如果 session_id 不存在
        """
        if session_id not in self.contexts:
            raise KeyError(f"Session {session_id} not found. Create context first.")
        
        context = self.contexts[session_id]
        
        # 添加新消息
        context.add_message(role, content)
        
        # 更新清晰度评分
        if clarity_score is not None:
            context.current_clarity_score = clarity_score
        
        # 更新认知偏差列表
        if new_biases:
            for bias in new_biases:
                if bias not in context.detected_biases:
                    context.detected_biases.append(bias)
        
        return context
    
    async def generate_questions(
        self,
        session_id: str,
        num_questions: int = 3
    ) -> QuestionGenerationResult:
        """
        生成追问问题
        
        Args:
            session_id: 会话唯一标识
            num_questions: 要生成的问题数量，默认3个
            
        Returns:
            QuestionGenerationResult 包含生成的问题
            
        Raises:
            KeyError: 如果 session_id 不存在
            ValueError: JSON 解析错误
        """
        context = self.get_context(session_id)
        if not context:
            raise KeyError(f"Session {session_id} not found. Create context first.")
        
        # 格式化提示模板
        prompt = QUESTION_GENERATION_PROMPT.format(
            original_question=context.original_question,
            context=context.format_context_string(),
            detected_biases=json.dumps(context.detected_biases, ensure_ascii=False),
            clarity_score=context.current_clarity_score,
            num_questions=num_questions
        )
        
        try:
            # 调用 API 生成问题
            response = await self.client.simple_chat(
                prompt=prompt,
                temperature=0.7,
                max_tokens=1000
            )
            
            # 提取 JSON
            analyzer = QuestionAnalyzer(self.client)
            json_content = analyzer._extract_json(response)
            
            # 解析结果
            data = json.loads(json_content)
            result = QuestionGenerationResult.from_dict(data)
            
            return result
            
        except json.JSONDecodeError as e:
            raise ValueError(f"Failed to parse generated questions as JSON: {str(e)}. Response: {response}")
        except Exception as e:
            raise Exception(f"Error generating questions: {str(e)}")
    
    def clear_context(self, session_id: str) -> bool:
        """
        清除指定会话的上下文
        
        Args:
            session_id: 会话唯一标识
            
        Returns:
            是否成功清除
        """
        if session_id in self.contexts:
            del self.contexts[session_id]
            return True
        return False
    
    def list_sessions(self) -> List[str]:
        """
        列出所有活跃的会话 ID
        
        Returns:
            会话 ID 列表
        """
        return list(self.contexts.keys())


# =============================================================================
# 便捷函数
# =============================================================================

async def create_ai_service(
    api_key: Optional[str] = None,
    base_url: Optional[str] = None,
    model: Optional[str] = None
) -> Dict[str, Any]:
    """
    便捷函数：创建完整的 AI 服务实例
    
    Args:
        api_key: API 密钥
        base_url: API 基础 URL
        model: 模型名称
        
    Returns:
        包含 client, analyzer, calibration_chat 的字典
        
    Example:
        >>> service = await create_ai_service()
        >>> client = service["client"]
        >>> analyzer = service["analyzer"]
        >>> calibration = service["calibration_chat"]
    """
    client = BailianClient(
        api_key=api_key,
        base_url=base_url,
        model=model
    )
    
    return {
        "client": client,
        "analyzer": QuestionAnalyzer(client),
        "calibration_chat": CalibrationChat(client)
    }


# =============================================================================
# 测试代码
# =============================================================================

if __name__ == "__main__":
    import asyncio
    
    async def test():
        """测试 AI 服务功能"""
        print("=" * 60)
        print("QuestionOS AI Service 测试")
        print("=" * 60)
        
        # 创建服务
        try:
            service = await create_ai_service(
                api_key="sk-sp-55895b74fe584428bac791142e8c38ad",
                base_url="https://coding.dashscope.aliyuncs.com/v1",
                model="qwen-plus"
            )
            
            client = service["client"]
            analyzer = service["analyzer"]
            calibration = service["calibration_chat"]
            
            print("\n✅ 服务初始化成功")
            print(f"   - 客户端: {client.model}")
            print(f"   - 分析器: 就绪")
            print(f"   - 校准对话: 就绪")
            
            # 测试问题
            test_question = "为什么我的创业项目总是失败，是不是我根本不适合创业？"
            
            print(f"\n📝 测试问题: {test_question}")
            print("-" * 60)
            
            # 测试问题分析
            print("\n🔍 正在分析问题结构...")
            analysis = await analyzer.analyze_structure(test_question)
            
            print(f"\n✅ 分析完成:")
            print(f"   - 问题类型: {analysis.question_type.value}")
            print(f"   - 核心变量: {', '.join(analysis.core_variables) or '无'}")
            print(f"   - 认知偏差: {', '.join(analysis.cognitive_biases) or '无'}")
            print(f"   - 清晰度评分: {analysis.clarity_score}/100")
            
            # 测试校准对话
            session_id = "test_session_001"
            context = calibration.create_context(session_id, test_question, analysis)
            
            print(f"\n💬 正在生成追问问题...")
            result = await calibration.generate_questions(session_id, num_questions=2)
            
            print(f"\n✅ 生成完成:")
            for i, q in enumerate(result.questions, 1):
                print(f"\n   问题 {i}: {q.question}")
                print(f"   目的: {q.purpose}")
                if q.target_bias:
                    print(f"   针对偏差: {q.target_bias}")
            
            print(f"\n   预期清晰度提升: +{result.clarity_change}")
            
            # 测试上下文更新
            print(f"\n📝 测试上下文更新...")
            calibration.update_context(
                session_id,
                "user",
                "我之前的创业项目是因为资金链断裂失败的。",
                clarity_score=analysis.clarity_score + result.clarity_change
            )
            
            updated_context = calibration.get_context(session_id)
            print(f"\n✅ 上下文已更新:")
            print(f"   - 消息数量: {len(updated_context.messages)}")
            print(f"   - 当前清晰度: {updated_context.current_clarity_score}/100")
            
            print("\n" + "=" * 60)
            print("✅ 所有测试通过!")
            print("=" * 60)
            
        except Exception as e:
            print(f"\n❌ 测试失败: {str(e)}")
            import traceback
            traceback.print_exc()
    
    # 运行测试
    asyncio.run(test())
