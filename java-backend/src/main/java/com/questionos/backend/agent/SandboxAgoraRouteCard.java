package com.questionos.backend.agent;

/**
 * 沙盘首轮「审议路由」卡片：按 Agora 审议室命名，说明与内置四角色 + 可选外聘的对应关系（叙事对齐，非调用真实 Agora）。
 */
public final class SandboxAgoraRouteCard {
    private SandboxAgoraRouteCard() {}

    public static String markdown(SandboxDeliberationScene scene, boolean hasThirdPartyAgents) {
        Room r = roomFor(scene);
        StringBuilder sb = new StringBuilder();
        sb.append("### 🧭 审议路由\n\n");
        sb.append("你的问题已归入 **Agora 式审议室**：`").append(r.agoraCommand).append("` **").append(r.roomTitleZh)
                .append("**（").append(r.roomSubtitle).append("）\n\n");
        sb.append("**Agora 该室典型面板**（参考 [geekjourneyx/agora](https://github.com/geekjourneyx/agora)）：\n");
        sb.append(r.agoraPanelLine).append("\n\n");
        sb.append("**本场 QuestionOS 沙盘将这样匹配**：\n\n");
        sb.append("| QuestionOS 角色 | 与上面对位的审议侧重 |\n");
        sb.append("|------------------|----------------------|\n");
        for (String[] row : r.matchRows) {
            sb.append("| ").append(row[0]).append(" | ").append(row[1]).append(" |\n");
        }
        if (hasThirdPartyAgents) {
            sb.append("| **外聘 Agent（OpenClaw）** | 在轮转中与内置攻防位交替，提供外视角补充或追问。 |\n");
        }
        sb.append("\n");
        sb.append("接下来进入 **多角色轮流拆解**：请先看第一位专家发言，再按需追问。\n");
        return sb.toString();
    }

    private record Room(
            String agoraCommand,
            String roomTitleZh,
            String roomSubtitle,
            String agoraPanelLine,
            String[][] matchRows
    ) {}

    private static Room roomFor(SandboxDeliberationScene scene) {
        return switch (scene) {
            case BUSINESS -> new Room(
                    "/bazaar",
                    "集市",
                    "商业与战略",
                    "Schumpeter、Munger、Sun Tzu、Machiavelli、Taleb、Kahneman 等",
                    new String[][] {
                            {"**利益审计师**", "Munger 式多模型 / 单位经济、定价权与 ROI；Kahneman 式「算清自己的偏差成本」"},
                            {"**风险预测官**", "Taleb 式尾部与反脆弱；Sun Tzu 式竞品与 terrain"},
                            {"**价值裁判**", "Machiavelli 式激励与真实行为；与「长期原则」之间的张力"},
                            {"**首席整合官**", "Agora 协调者：收束正反、输出可执行的决策沙盘"}
                    }
            );
            case ENGINEERING -> new Room(
                    "/forge",
                    "锻造坊",
                    "工程与架构",
                    "Popper、Kant、Occam、Nietzsche、Wittgenstein 及 Council 工程向成员等",
                    new String[][] {
                            {"**利益审计师**", "Occam 式复杂度审计：改动带来的长期维护与人力成本"},
                            {"**风险预测官**", "Popper 式证伪：最可能让系统崩盘的单点与失效链"},
                            {"**价值裁判**", "技术选型背后的动机：可维护性 vs 炫技、短期交付 vs 债务"},
                            {"**首席整合官**", "把架构取舍拉回「用户核心议题」上的可验证决策表"}
                    }
            );
            case LIFE_CROSSROADS -> new Room(
                    "/oracle",
                    "神谕所",
                    "人生十字路口与存在抉择",
                    "Sartre、Aurelius、Jung、Frankl、Nietzsche、Kahneman 等",
                    new String[][] {
                            {"**利益审计师**", "机会成本与可逆性：选 A 放弃 B 的账本"},
                            {"**风险预测官**", "最坏叙事与「后悔结构」：若失败最先失去什么"},
                            {"**价值裁判**", "Frankl/Jung 式：意义、阴影与「这真的是你要的吗」"},
                            {"**首席整合官**", "把存在张力收成可执行的小步实验与观察指标"}
                    }
            );
            case RELATIONSHIP -> new Room(
                    "/hearth",
                    "火炉边",
                    "关系与家庭",
                    "Fromm、Adler、Zhuangzi、Kant、Aurelius、Watts 等",
                    new String[][] {
                            {"**利益审计师**", "关系中的「隐性契约」与持续投入产出"},
                            {"**风险预测官**", "重复冲突模式恶化后的崩盘场景"},
                            {"**价值裁判**", "边界、尊重与「共同体感觉」的张力"},
                            {"**首席整合官**", "把人际张力收成可沟通的下一步协议"}
                    }
            );
            case PSYCHOLOGY -> new Room(
                    "/clinic",
                    "诊疗室",
                    "心理韧性与行为",
                    "Skinner、Frankl、Aurelius、Kahneman、Zhuangzi、Jung 等",
                    new String[][] {
                            {"**利益审计师**", "习惯/环境设计的「支付意愿」：你愿意为改变付出什么日常成本"},
                            {"**风险预测官**", "拖延、倦怠、复发的下行螺旋"},
                            {"**价值裁判**", "Frankl 式意义与自我异化：你在用忙碌逃避什么"},
                            {"**首席整合官**", "把心理目标落成一周内的微实验与复盘"}
                    }
            );
            case CREATIVE -> new Room(
                    "/atelier",
                    "工作坊",
                    "创作与表达",
                    "Socrates、Lao Tzu、Watts、Nietzsche、Occam、Feynman、Wittgenstein 等",
                    new String[][] {
                            {"**利益审计师**", "受众、时间与完成定义：创作项目的「交付经济学」"},
                            {"**风险预测官**", "灵感依赖、完美主义与弃稿链路的崩盘点"},
                            {"**价值裁判**", "风格、原创性与「为谁写」的主体性"},
                            {"**首席整合官**", "把创作卡壳收成下一稿的可检验约束"}
                    }
            );
            case GENERAL -> new Room(
                    "/agora",
                    "广场路由",
                    "跨域或未落入单一审议室",
                    "由 Router 在 Agora 中再分室；此处先以均衡四镜审视",
                    new String[][] {
                            {"**利益审计师**", "利益 / 成本 / 激励维度"},
                            {"**风险预测官**", "尾部与执行风险维度"},
                            {"**价值裁判**", "动机与主体性维度"},
                            {"**首席整合官**", "综合裁决与下一步验证"}
                    }
            );
        };
    }
}
