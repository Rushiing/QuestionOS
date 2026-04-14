package com.questionos.backend.agent;

/**
 * 沙盘首轮对用户核心议题的审议室式分类（与 Agora 六室 loosely 对齐），持久化在会话上整场复用。
 */
public enum SandboxDeliberationScene {
    /** 商业、市场、战略、定价等 */
    BUSINESS,
    /** 工程、架构、代码、技术债等 */
    ENGINEERING,
    /** 人生十字路口、转行、意义感等 */
    LIFE_CROSSROADS,
    /** 伴侣、家庭、职场人际、边界等 */
    RELATIONSHIP,
    /** 焦虑、拖延、倦怠、习惯、恢复等 */
    PSYCHOLOGY,
    /** 写作、创作、卡壳、表达等 */
    CREATIVE,
    /** 无法可靠归类或多域均衡 */
    GENERAL;

    public static SandboxDeliberationScene parseStored(String raw) {
        if (raw == null || raw.isBlank()) {
            return GENERAL;
        }
        try {
            return SandboxDeliberationScene.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GENERAL;
        }
    }
}
