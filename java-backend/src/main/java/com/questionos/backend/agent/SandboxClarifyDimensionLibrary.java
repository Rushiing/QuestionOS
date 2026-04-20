package com.questionos.backend.agent;

import java.util.List;
import java.util.Map;

/**
 * 沙盘步骤①追问维度库：为每个审议室定义 3~4 个关键追问维度，
 * 指导 {@link MainCalibrateAgent#generateSandboxStep1ClarifyFollowup} 生成针对性追问。
 *
 * <p>设计原则：
 * <ul>
 *   <li>维度应该是该审议室**特有的**、**必不可少的**信息缺口</li>
 *   <li>维度按递进顺序排列：从「钉死决策对象」→「明确约束」→「深化动机」</li>
 *   <li>LLM 在追问时应选择 1~2 个维度，围绕维度展开具体问句（而非列举维度本身）</li>
 * </ul>
 */
public final class SandboxClarifyDimensionLibrary {
    private SandboxClarifyDimensionLibrary() {}

    public record Dimension(String id, String title, String description) {}

    public record RoomDimensions(String roomName, List<Dimension> dimensions) {}

    /**
     * 获取指定审议室的追问维度列表。
     * 返回顺序为推荐的追问递进顺序。
     */
    public static RoomDimensions getDimensions(SandboxDeliberationScene scene) {
        return switch (scene) {
            case BUSINESS -> businessDimensions();
            case ENGINEERING -> engineeringDimensions();
            case LIFE_CROSSROADS -> lifeCrossroadsDimensions();
            case RELATIONSHIP -> relationshipDimensions();
            case PSYCHOLOGY -> psychologyDimensions();
            case CREATIVE -> creativeDimensions();
            case GENERAL -> generalDimensions();
        };
    }

    private static RoomDimensions businessDimensions() {
        return new RoomDimensions("集市", List.of(
                new Dimension(
                        "decision_option",
                        "核心商业选择",
                        "明确是在定价/融资/扩张/转向/入场/退出中的哪一个选择上纠结；不同选择的信息诉求完全不同"
                ),
                new Dimension(
                        "hard_constraints",
                        "硬约束清单",
                        "资金量级/融资时限/团队人数上限/市场窗口期/合规要求——哪条是现在最紧的？"
                ),
                new Dimension(
                        "info_gap",
                        "关键信息缺口",
                        "竞争对手反应/用户需求验证/成本结构/渠道成本/现金流压力——最怕错的假设是哪个？"
                )
        ));
    }

    private static RoomDimensions engineeringDimensions() {
        return new RoomDimensions("锻造坊", List.of(
                new Dimension(
                        "tech_tradeoff",
                        "技术选型的权衡",
                        "是在性能/可维护性/开发速度/扩展性中的哪个权衡上拿不准；不同权衡涉及的约束完全不同"
                ),
                new Dimension(
                        "cost_dimension",
                        "成本维度最紧的是哪条",
                        "当下最紧的是开发时间/维护成本/计算资源/学习成本/技术债偿还——这决定了架构的方向"
                ),
                new Dimension(
                        "verify_path",
                        "最小验证路径",
                        "这个架构选择如何用最快的方式得到反馈；需要写多少代码、多久能看到收效"
                )
        ));
    }

    private static RoomDimensions lifeCrossroadsDimensions() {
        return new RoomDimensions("神谕所", List.of(
                new Dimension(
                        "concrete_choice",
                        "具体抉择是什么",
                        "是转行/留任/创业/深造/地理迁移中的哪一个；「迷茫」太宽泛，得钉死一个具体的 vs 关系才能对话"
                ),
                new Dimension(
                        "hard_boundary",
                        "硬边界与底线",
                        "家庭期待/财务压力/身体健康/时间窗口中，哪条是绝对不能妥协的约束"
                ),
                new Dimension(
                        "value_driver",
                        "这次选择背后最在乎什么",
                        "是安全感/自由度/成长/影响力/归属/自我证明中的哪个；同一个选择不同人的动机完全不同"
                ),
                new Dimension(
                        "reversibility",
                        "选择的可逆性",
                        "这条路走错了能不能回头；多少年后会「后悔点」；哪些资源一旦失去就再难找回"
                )
        ));
    }

    private static RoomDimensions relationshipDimensions() {
        return new RoomDimensions("火炉边", List.of(
                new Dimension(
                        "conflict_pattern",
                        "反复冲突的那一点",
                        "每次吵到哪句话就卡住；是表面问题（谁做家务）还是深层冲突（被看见/被尊重）"
                ),
                new Dimension(
                        "mutual_need",
                        "双方真实的诉求",
                        "对方真正期待的是什么（安全感/肯定/帮助/自由），你真正想要什么——有时这个都没说清"
                ),
                new Dimension(
                        "repair_history",
                        "过去化解冲突的记忆",
                        "之前成功化解过吗，是怎么做的；或者每次都是退让/冷战，没有真正的修复"
                )
        ));
    }

    private static RoomDimensions psychologyDimensions() {
        return new RoomDimensions("诊疗室", List.of(
                new Dimension(
                        "trigger_moment",
                        "触发节点是什么",
                        "什么具体场景/时间/人/事件最容易激发拖延/焦虑/倦怠；越具体越容易着手改变"
                ),
                new Dimension(
                        "vicious_loop",
                        "当前的恶性循环",
                        "拖延导致焦虑导致睡眠差导致更难开始——这个链条长什么样，哪一环最先要打破"
                ),
                new Dimension(
                        "micro_change",
                        "最小可改的一项",
                        "从睡眠/运动/社交/工作时段/饮食中，当下最可能立刻改的是哪个，需要什么外部条件"
                )
        ));
    }

    private static RoomDimensions creativeDimensions() {
        return new RoomDimensions("工作坊", List.of(
                new Dimension(
                        "creation_stage",
                        "卡在哪个创作环节",
                        "是立意不清/结构混乱/完成度不够/害怕发表中的哪一个；每个环节的解法完全不同"
                ),
                new Dimension(
                        "reader_contract",
                        "读者应该怎样读这个作品",
                        "你希望读者得到什么体验/思考/感受；清晰的「读者契约」能指导内容的删改"
                ),
                new Dimension(
                        "mvp_version",
                        "最小可展示版本",
                        "70% 完成度的版本你敢分享吗；很多创作卡在「必须完美」而永远不发表"
                )
        ));
    }

    private static RoomDimensions generalDimensions() {
        return new RoomDimensions("广场统筹", List.of(
                new Dimension(
                        "decision_object",
                        "决策对象钉死",
                        "「迷茫」太宽，你到底在决策什么——是职业/财务/关系/创意/人生方向中的哪一个"
                ),
                new Dimension(
                        "constraints",
                        "约束清单",
                        "钱/人/时间/身体/合规/社会期待——哪条最硬，哪条有灵活空间"
                ),
                new Dimension(
                        "risk_scan",
                        "最坏情景下最先断的是什么",
                        "失败了最怕失去什么（收入/身份/关系/时间），先保护那条底线"
                )
        ));
    }

    /**
     * 为指定场景生成追问维度的 Prompt 提示段落。
     * 用于注入到 {@link MainCalibrateAgent#SANDBOX_STEP1_CLARIFY_PROMPT} 的上下文中。
     */
    public static String generateDimensionHint(SandboxDeliberationScene scene) {
        RoomDimensions dims = getDimensions(scene);
        StringBuilder sb = new StringBuilder();
        sb.append("## 本审议室的关键追问维度（供参考，帮助选择追问方向）\n\n");
        for (Dimension d : dims.dimensions) {
            sb.append("- **").append(d.title).append("**：").append(d.description).append("\n");
        }
        sb.append("\n");
        sb.append("注：不要直接重述维度本身，而是**选择 1~2 个维度，围绕该维度用用户已有的具体语境生成一个自然的追问**。\n");
        sb.append("例如，若选中「核心选择」维度，不要问「你的核心选择是什么」，而是根据用户已说的词语具体化。\n");
        return sb.toString();
    }
}
