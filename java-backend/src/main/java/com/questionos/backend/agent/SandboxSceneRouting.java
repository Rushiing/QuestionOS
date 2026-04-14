package com.questionos.backend.agent;

/**
 * 按审议场景注入「攻防加权」说明，写入 user 侧上下文，不替换各角色 system prompt。
 */
public final class SandboxSceneRouting {
    private SandboxSceneRouting() {}

    public static String contextBlockFor(SandboxDeliberationScene scene) {
        return switch (scene) {
            case BUSINESS -> """
                    ## 审议场景（系统已分类：商业与战略）

                    本场加权：市场结构、定价与单位经济、现金流与融资节奏、竞争动作与激励相容；少用抽象「成功学」。
                    追问须能落到「钱、客户、时间窗口、对手反应」之一。
                    """;
            case ENGINEERING -> """
                    ## 审议场景（系统已分类：工程与架构）

                    本场加权：约束与不变量、复杂度与演进路径、可靠性/性能/安全取舍、技术债偿还节奏。
                    追问须能落到「接口、数据、部署、测试、回滚」之一或具体风险假设。
                    """;
            case LIFE_CROSSROADS -> """
                    ## 审议场景（系统已分类：人生十字路口）

                    本场加权：机会成本与后悔结构、身份与叙事、可逆性；避免替用户做终局决定，逼其澄清排序与底线。
                    """;
            case RELATIONSHIP -> """
                    ## 审议场景（系统已分类：关系与互动）

                    本场加权：边界、沟通协议、重复冲突模式、信任修复成本；少讲大道理，多落到具体互动与下一步试验。
                    """;
            case PSYCHOLOGY -> """
                    ## 审议场景（系统已分类：心理与行为）

                    本场加权：触发因素、环境与系统设计、小步实验与可观测指标；避免临床诊断式断言，保持行为科学口吻。
                    """;
            case CREATIVE -> """
                    ## 审议场景（系统已分类：创作与表达）

                    本场加权：受众与约束、迭代节奏、风格与原创性张力、完成定义；追问须帮助用户缩小「下一稿」范围。
                    """;
            case GENERAL -> """
                    ## 审议场景（系统已分类：综合）

                    议题跨域或信息不足：仍须钉死用户核心议题中的具体对象与矛盾，从利益/风险/价值三棱镜各至少落一刀。
                    """;
        };
    }
}
