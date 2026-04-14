-- 沙盘：首轮对用户议题 LLM 分类后的审议场景，整场会话复用
ALTER TABLE qos_conversation_session
    ADD COLUMN sandbox_deliberation_scene VARCHAR(32);
