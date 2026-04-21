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
        sb.append("> 下列界面发言按上表**审议角度**与本室轮转协议依次展开（已接外聘时穿插外视角）。\n\n");
        sb.append("接下来进入 **多角色轮流拆解**，请先看第一位专家发言，再按需追问。\n");
        return sb.toString();
    }

    private record Room(String roomTitle, String subtitle, String[][] tableRows) {}

    private static Room roomFor(SandboxDeliberationScene scene) {
        return switch (scene) {
            case BUSINESS -> new Room(
                    "集市",
                    "商业与战略",
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
                    new String[][] {
                            {"母题与母句", "一句话说清作品在回应什么命题"},
                            {"结构取舍", "删去哪一条支线反而更锋利"},
                            {"声音与真诚", "哪里在模仿、哪里在借他人嘴说话"},
                            {"节奏与留白", "哪里该停一拍让读者喘息"},
                            {"读者契约", "读者凭什么继续读下一页"},
                            {"发布门槛", "最小可展示增量是什么、给谁看"}
                    }
            );
            case GENERAL -> new Room(
                    "广场统筹",
                    "跨域或信息不足时的均衡检视",
                    new String[][] {
                            {"议题钉死", "用一句话写出可执行的决策对象"},
                            {"约束清单", "钱/人/时间/合规里哪条是硬顶"},
                            {"备选方案", "除当前想法外还有哪两条路"},
                            {"信息缺口", "缺哪三个数据就无法继续判断"},
                            {"风险扫描", "最坏情况里最先断的是哪一环"},
                            {"下一步实验", "本周只验证一个可观测指标"}
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
