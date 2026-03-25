"""
OpenClaw 调用适配器 - 沙盘推演版本
支持流式输出的修罗场模式
"""

import httpx
import json
import asyncio
from typing import List, Dict, Optional, AsyncGenerator
from dataclasses import dataclass


class SandtableClient:
    """沙盘推演客户端 - 流式调用百炼 API"""
    
    def __init__(self):
        from openai import AsyncOpenAI
        self.client = AsyncOpenAI(
            api_key="sk-sp-55895b74fe584428bac791142e8c38ad",
            base_url="https://coding.dashscope.aliyuncs.com/v1"
        )
    
    async def consult_stream(
        self,
        question: str,
        system_prompt: str,
        previous_messages: Optional[List[Dict]] = None
    ) -> AsyncGenerator[str, None]:
        """
        流式咨询单个 Agent
        
        Args:
            question: 用户问题
            system_prompt: Agent 的系统提示词
            previous_messages: 之前的对话历史 [{role, content}]
        
        Yields:
            流式输出的文本片段
        """
        messages = [
            {"role": "system", "content": system_prompt}
        ]
        
        # 添加之前的对话历史
        if previous_messages:
            messages.extend(previous_messages)
        
        # 添加当前问题
        messages.append({"role": "user", "content": question})
        
        try:
            stream = await self.client.chat.completions.create(
                model="qwen3-coder-plus",
                messages=messages,
                max_tokens=2000,
                temperature=0.7,
                stream=True
            )
            
            async for chunk in stream:
                if chunk.choices and chunk.choices[0].delta.content:
                    yield chunk.choices[0].delta.content
                    
        except Exception as e:
            yield f"\n\n[错误: {str(e)}]"


async def run_sandtable_stream(
    question: str
) -> AsyncGenerator[Dict, None]:
    """
    运行沙盘推演（流式）
    
    执行顺序：
    1. 利益审计师攻击
    2. 风险预测官攻击（看到审计师的输出）
    3. 价值裁判攻击（看到前两者的输出）
    4. 首席整合官收束（看到全部对话）
    
    Args:
        question: 用户问题
    
    Yields:
        事件字典:
        - {"type": "agent_start", "agent_id", "agent_name", "agent_avatar", "order"}
        - {"type": "content", "agent_id", "content"}
        - {"type": "agent_end", "agent_id"}
        - {"type": "error", "agent_id", "message"}
    """
    from .registry import get_attackers, get_integrator
    
    client = SandtableClient()
    conversation_history = []  # 保存完整对话历史
    
    # 获取攻击者和整合官
    attackers = get_attackers()
    integrator = get_integrator()
    
    # 1. 攻击者依次进攻
    for attacker in attackers:
        # 发送开始事件
        yield {
            "type": "agent_start",
            "agent_id": attacker.id,
            "agent_name": attacker.name,
            "agent_avatar": attacker.avatar,
            "order": attacker.execution_order
        }
        
        # 构建问题（如果有历史对话，加入上下文）
        if conversation_history:
            context = "\n\n".join([
                f"【{msg['agent_name']}说】：{msg['content']}"
                for msg in conversation_history
            ])
            attacker_question = f"前面其他攻击者的观点：\n{context}\n\n---\n\n现在轮到你了。基于你的立场，对用户的问题发起攻击：{question}"
        else:
            attacker_question = question
        
        # 流式获取回答
        full_content = ""
        try:
            async for chunk in client.consult_stream(
                question=attacker_question,
                system_prompt=attacker.system_prompt
            ):
                full_content += chunk
                yield {
                    "type": "content",
                    "agent_id": attacker.id,
                    "content": chunk
                }
            
            # 记录到对话历史
            conversation_history.append({
                "role": "assistant",
                "agent_id": attacker.id,
                "agent_name": attacker.name,
                "content": full_content
            })
            
            # 发送结束事件
            yield {
                "type": "agent_end",
                "agent_id": attacker.id,
                "agent_name": attacker.name
            }
            
        except Exception as e:
            yield {
                "type": "error",
                "agent_id": attacker.id,
                "message": str(e)
            }
    
    # 2. 整合官收束
    if integrator:
        yield {
            "type": "agent_start",
            "agent_id": integrator.id,
            "agent_name": integrator.name,
            "agent_avatar": integrator.avatar,
            "order": integrator.execution_order
        }
        
        # 整合官看到完整对话历史
        full_context = "\n\n---\n\n".join([
            f"【{msg['agent_name']}】：{msg['content']}"
            for msg in conversation_history
        ])
        
        integrator_question = f"""用户的问题：{question}

三位攻击者的观点如下：

{full_context}

---

作为首席整合官，请收束这场博弈，输出你的决策沙盘报告。"""
        
        full_content = ""
        try:
            async for chunk in client.consult_stream(
                question=integrator_question,
                system_prompt=integrator.system_prompt
            ):
                full_content += chunk
                yield {
                    "type": "content",
                    "agent_id": integrator.id,
                    "content": chunk
                }
            
            yield {
                "type": "agent_end",
                "agent_id": integrator.id,
                "agent_name": integrator.name
            }
            
        except Exception as e:
            yield {
                "type": "error",
                "agent_id": integrator.id,
                "message": str(e)
            }


# 保留旧接口兼容
class SimpleConsultationClient:
    """简化的咨询客户端 - 直接调用百炼 API"""
    
    def __init__(self):
        from openai import AsyncOpenAI
        self.client = AsyncOpenAI(
            api_key="sk-sp-55895b74fe584428bac791142e8c38ad",
            base_url="https://coding.dashscope.aliyuncs.com/v1"
        )
    
    async def consult(
        self,
        question: str,
        system_prompt: str,
        context: Optional[str] = None,
        previous_responses: Optional[List[Dict]] = None
    ) -> str:
        """咨询单个 Agent"""
        messages = [
            {"role": "system", "content": system_prompt}
        ]
        
        if previous_responses:
            context_text = "\n\n".join([
                f"【{r['agent_name']}说】：{r['content']}"
                for r in previous_responses
            ])
            messages.append({
                "role": "user", 
                "content": f"其他顾问的观点：\n{context_text}\n\n请基于以上观点，从你的专业角度给出你的看法。用户问题：{question}"
            })
        elif context:
            messages.append({"role": "user", "content": f"背景：{context}\n\n问题：{question}"})
        else:
            messages.append({"role": "user", "content": question})
        
        response = await self.client.chat.completions.create(
            model="qwen3-coder-plus",
            messages=messages,
            max_tokens=1500,
            temperature=0.7
        )
        
        return response.choices[0].message.content


async def run_consultation(
    question: str,
    agent_ids: List[str],
    context: Optional[str] = None,
    mode: str = "discussion"
) -> List[Dict]:
    """运行咨询会话（旧接口，保留兼容）"""
    from .registry import get_agent
    
    client = SimpleConsultationClient()
    responses = []
    
    for i, agent_id in enumerate(agent_ids):
        agent = get_agent(agent_id)
        if not agent:
            continue
        
        previous = responses if mode == "discussion" and i > 0 else None
        
        try:
            content = await client.consult(
                question=question,
                system_prompt=agent.system_prompt,
                context=context,
                previous_responses=previous
            )
            
            responses.append({
                "agent_id": agent_id,
                "agent_name": agent.name,
                "agent_avatar": agent.avatar,
                "content": content,
                "order": i + 1
            })
        except Exception as e:
            responses.append({
                "agent_id": agent_id,
                "agent_name": agent.name,
                "agent_avatar": agent.avatar,
                "content": f"抱歉，我暂时无法回答。错误：{str(e)}",
                "order": i + 1,
                "error": True
            })
    
    return responses