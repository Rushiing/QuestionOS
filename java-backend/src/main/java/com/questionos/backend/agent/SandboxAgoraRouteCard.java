package com.questionos.backend.agent;

/**
 * 沙盘首轮「审议路由」卡片：以 Agora 审议室与思想家面板为主叙事（中文），QuestionOS 实现细节一笔带过。
 */
public final class SandboxAgoraRouteCard {
    private SandboxAgoraRouteCard() {}

    public static String markdown(SandboxDeliberationScene scene, boolean hasThirdPartyAgents) {
        Room r = roomFor(scene);
        StringBuilder sb = new StringBuilder();
        sb.append("### 🧭 审议路由（步骤 ②）\n\n");
        sb.append("你的问题已归入 **").append(r.roomTitle).append("**（").append(r.subtitle).append("）。\n\n");
        sb.append("**Agora 思想谱系参照（帮助理解本室提问重心；不是界面上的发言者实名列表）**  \n");
        sb.append(r.panelIntro).append("\n\n");
        sb.append("**本站本轮实际轮转发言者**  \n");
        sb.append("**苏格拉底**（诘问与概念澄清）→ **尼采**（代价与叙事重估）→ **卡尼曼**（偏差校准）→ **马可·奥勒留**（可控域内综合收束）。\n\n");
        sb.append("**审议角度与追问焦点**\n\n");
        sb.append("| 审议角度 | 本轮追问焦点 |\n");
        sb.append("|----------|----------------|\n");
        for (String[] row : r.tableRows) {
            sb.append("| ").append(row[0]).append(" | ").append(row[1]).append(" |\n");
        }
        sb.append("\n");
        if (hasThirdPartyAgents) {
            sb.append("已接入 **外聘智能体**：将在轮转中与上述角度交替，补充外视角与追问。\n\n");
        }
        sb.append("> 下列界面发言将**依次围绕上表审议角度**展开；上段历史人物谱系与本轮四位轮转发言者**分工一致、姓名不必相同**。\n\n");
        sb.append("接下来进入 **多角色轮流拆解**，请先看第一位专家发言，再按需追问。\n");
        return sb.toString();
    }

    private record Room(String roomTitle, String subtitle, String panelIntro, String[][] tableRows) {}

    private static Room roomFor(SandboxDeliberationScene scene) {
        return switch (scene) {
            case BUSINESS -> new Room(
                    "集市",
                    "商业与战略",
                    "约瑟夫·熊彼特、查理·芒格、孙子、尼可罗·马基雅维利、纳西姆·塔勒布、丹尼尔·卡尼曼 等——侧重创新破坏与周期、格栅思维与定价权、争战地形与竞品、权力与激励相容、尾部风险与反脆弱、认知偏差与决策质量。",
                    new String[][] {
                            {"创新破坏与周期", "新业务/降价/扩张是否经得起现金流与护城河检验"},
                            {"格栅思维与定价权", "单位经济、毛利与「不做什么」的边界"},
                            {"争战地形与竞品", "对手反应、渠道与信息优势"},
                            {"权力与激励相容", "关键人、利益相关方真实会怎么动"},
                            {"尾部与反脆弱", "小概率灾难下的生存线与预案"},
                            {"认知偏差", "团队最容易高估/低估的一处判断"}
                    }
            );
            case ENGINEERING -> new Room(
                    "锻造坊",
                    "工程与架构",
                    "卡尔·波普尔、伊曼努尔·康德、奥卡姆的威廉、弗里德里希·尼采、路德维希·维特根斯坦 及工程向智者——侧重证伪与可检验假设、不变量与义务、复杂度剃刀、创造性破坏、概念澄清。",
                    new String[][] {
                            {"证伪与可检验假设", "哪一条断言最先可以被实验或监控证伪"},
                            {"不变量与约束", "架构里绝不能被打破的边界条件"},
                            {"复杂度与演进", "最小可行拆分、技术债偿还节奏"},
                            {"失效链与单点", "一旦崩了会连锁到哪里"},
                            {"动机与叙事", "选型背后谁在推动、短期炫技还是长期可维护"},
                            {"概念与接口边界", "名词是否混用、模块契约是否自洽"}
                    }
            );
            case LIFE_CROSSROADS -> new Room(
                    "神谕所",
                    "人生十字路口与存在抉择",
                    "让-保罗·萨特、马可·奥勒留、卡尔·荣格、维克多·弗兰克尔、弗里德里希·尼采、丹尼尔·卡尼曼 等——侧重自由与责任、可控之域、阴影与整合、意义、价值重估与偏差。",
                    new String[][] {
                            {"机会成本与可逆性", "选 A 弃 B 的账本与后悔结构"},
                            {"最坏叙事", "若失败最先失去的身份、关系或资源"},
                            {"意义与阴影", "表面目标下未被承认的驱动力"},
                            {"态度与微小自由", "当下仍能改写叙事的一处选择"},
                            {"价值重估", "「必须如此」里有多少是社会脚本"},
                            {"偏差自检", "你在用哪条捷径故事说服自己"}
                    }
            );
            case RELATIONSHIP -> new Room(
                    "火炉边",
                    "关系与家庭",
                    "埃里希·弗洛姆、阿尔弗雷德·阿德勒、庄子、伊曼努尔·康德、马可·奥勒留、艾伦·瓦茨 等——侧重爱的实践、课题分离与共同体、齐物与不争、可普遍化、内在平静与隐喻。",
                    new String[][] {
                            {"隐性契约与投入", "双方各自默认却未说清的期待"},
                            {"重复冲突模式", "每次吵到同一句就卡死的那一环"},
                            {"边界与尊重", "哪里该停、哪里该给空间"},
                            {"可普遍化检验", "若互换立场你是否仍认同自己的做法"},
                            {"修复成本与信任", "道歉与补偿是否对齐伤害"},
                            {"共同体感觉", "「我们」还能不能一起定义下一步"}
                    }
            );
            case PSYCHOLOGY -> new Room(
                    "诊疗室",
                    "心理韧性与行为",
                    "斯金纳、维克多·弗兰克尔、马可·奥勒留、丹尼尔·卡尼曼、庄子、卡尔·荣格 等——侧重环境设计、意义与态度、可控之域、偏差、无为与流动、个体化。",
                    new String[][] {
                            {"触发与环境", "什么情境最容易复发"},
                            {"下行螺旋", "拖延/倦怠/失眠如何互相喂养"},
                            {"微小实验", "下一周只改一个可观测变量"},
                            {"意义与逃避", "忙碌是否在挡住真正要面对的事"},
                            {"身体与节奏", "睡眠、运动、社交是否被系统性牺牲"},
                            {"整合阴影", "你讨厌自己的哪一面其实在保护你"}
                    }
            );
            case CREATIVE -> new Room(
                    "工作坊",
                    "创作与表达",
                    "苏格拉底、老子、艾伦·瓦茨、弗里德里希·尼采、奥卡姆的威廉、理查德·费曼、路德维希·维特根斯坦 等——侧重诘问、无为与减、心流隐喻、价值重估、剃刀、直觉与清晰。",
                    new String[][] {
                            {"受众与完成定义", "写给谁、什么叫「够好」"},
                            {"删减与约束", "少做哪一块反而更成立"},
                            {"灵感与依赖", "卡壳时最先断的是哪条外部拐杖"},
                            {"原创与风格", "模仿、致敬与抄袭的界线"},
                            {"可教性与反馈", "哪一句给别人读最让你紧张"},
                            {"下一稿检验", "最小可发布增量是什么"}
                    }
            );
            case GENERAL -> new Room(
                    "广场统筹",
                    "跨域或信息不足时的均衡检视",
                    "在 Agora 中由广场路由再分室；此处先以多镜均衡审视，待信息补足后可再收窄审议室。",
                    new String[][] {
                            {"利益与激励", "谁在什么约束下会怎么选"},
                            {"尾部与执行", "最坏情况与最先断的链"},
                            {"动机与主体性", "这是不是你真的想要的叙事"},
                            {"综合收束", "下一步最小可验证行动"}
                    }
            );
        };
    }

    /** 审议室中文名（与路由卡片一致） */
    public static String roomTitle(SandboxDeliberationScene scene) {
        return roomFor(scene).roomTitle();
    }

    /** 审议室副标题 */
    public static String roomSubtitle(SandboxDeliberationScene scene) {
        return roomFor(scene).subtitle();
    }
}
