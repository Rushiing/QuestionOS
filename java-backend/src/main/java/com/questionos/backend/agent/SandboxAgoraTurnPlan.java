package com.questionos.backend.agent;

import java.util.List;

/**
 * 按审议室（Agora 六室之一）给出本轮内置四席的<strong>发言顺序与展示名</strong>；
 * 底层仍复用四条内置 system prompt，仅叠加「本轮人格/谱系」前缀。
 */
public final class SandboxAgoraTurnPlan {
    private SandboxAgoraTurnPlan() {}

    public record BuiltinTurn(SandboxSlot slot, String displayName, String personaPrefix) {}

    public static List<BuiltinTurn> fourBuiltin(SandboxDeliberationScene scene) {
        return switch (scene) {
            case BUSINESS -> List.of(
                    new BuiltinTurn(SandboxSlot.AUDITOR, "熊彼特", "你本轮以「熊彼特式创新与周期」视角发言："),
                    new BuiltinTurn(SandboxSlot.VALUE_JUDGE, "查理·芒格", "你本轮以「芒格式格栅与定价权」视角发言："),
                    new BuiltinTurn(SandboxSlot.RISK_OFFICER, "孙子·争战", "你本轮以「争战地形与信息优势」视角发言："),
                    new BuiltinTurn(SandboxSlot.INTEGRATOR, "纳西姆·塔勒布", "你本轮以「尾部风险与反脆弱收束」视角发言：")
            );
            case ENGINEERING -> List.of(
                    new BuiltinTurn(SandboxSlot.AUDITOR, "卡尔·波普尔", "你本轮以「证伪与可检验假设」视角发言："),
                    new BuiltinTurn(SandboxSlot.VALUE_JUDGE, "伊曼努尔·康德", "你本轮以「不变量与义务」视角发言："),
                    new BuiltinTurn(SandboxSlot.RISK_OFFICER, "弗里德里希·尼采", "你本轮以「创造性破坏与选型意志」视角发言："),
                    new BuiltinTurn(SandboxSlot.INTEGRATOR, "路德维希·维特根斯坦", "你本轮以「概念与接口边界」视角发言：")
            );
            case LIFE_CROSSROADS -> List.of(
                    new BuiltinTurn(SandboxSlot.AUDITOR, "让-保罗·萨特", "你本轮以「自由与责任」视角发言："),
                    new BuiltinTurn(SandboxSlot.VALUE_JUDGE, "卡尔·荣格", "你本轮以「阴影与整合」视角发言："),
                    new BuiltinTurn(SandboxSlot.RISK_OFFICER, "维克多·弗兰克尔", "你本轮以「意义与态度」视角发言："),
                    new BuiltinTurn(SandboxSlot.INTEGRATOR, "马可·奥勒留", "你本轮以「可控之域与内在秩序」视角发言：")
            );
            case RELATIONSHIP -> List.of(
                    new BuiltinTurn(SandboxSlot.AUDITOR, "埃里希·弗洛姆", "你本轮以「爱的实践」视角发言："),
                    new BuiltinTurn(SandboxSlot.VALUE_JUDGE, "阿尔弗雷德·阿德勒", "你本轮以「课题分离与共同体」视角发言："),
                    new BuiltinTurn(SandboxSlot.RISK_OFFICER, "庄子", "你本轮以「齐物与不争」视角发言："),
                    new BuiltinTurn(SandboxSlot.INTEGRATOR, "伊曼努尔·康德", "你本轮以「可普遍化与边界」视角发言：")
            );
            case PSYCHOLOGY -> List.of(
                    new BuiltinTurn(SandboxSlot.AUDITOR, "维克多·弗兰克尔", "你本轮以「意义与态度」视角发言："),
                    new BuiltinTurn(SandboxSlot.VALUE_JUDGE, "斯金纳", "你本轮以「环境与行为设计」视角发言："),
                    new BuiltinTurn(SandboxSlot.RISK_OFFICER, "卡尔·荣格", "你本轮以「阴影与个体化」视角发言："),
                    new BuiltinTurn(SandboxSlot.INTEGRATOR, "马可·奥勒留", "你本轮以「可控之域与节奏」视角发言：")
            );
            case CREATIVE -> List.of(
                    new BuiltinTurn(SandboxSlot.AUDITOR, "苏格拉底", "你本轮以「诘问与定义澄清」视角发言："),
                    new BuiltinTurn(SandboxSlot.VALUE_JUDGE, "理查德·费曼", "你本轮以「直觉与清晰解释」视角发言："),
                    new BuiltinTurn(SandboxSlot.RISK_OFFICER, "弗里德里希·尼采", "你本轮以「价值重估与风格勇气」视角发言："),
                    new BuiltinTurn(SandboxSlot.INTEGRATOR, "路德维希·维特根斯坦", "你本轮以「语言边界与完成定义」视角发言：")
            );
            case GENERAL -> List.of(
                    new BuiltinTurn(SandboxSlot.AUDITOR, "苏格拉底", "你本轮以「先把决策对象钉死」视角发言："),
                    new BuiltinTurn(SandboxSlot.RISK_OFFICER, "弗里德里希·尼采", "你本轮以「尾部与断链」视角发言："),
                    new BuiltinTurn(SandboxSlot.VALUE_JUDGE, "丹尼尔·卡尼曼", "你本轮以「利益、动机与偏差」视角发言："),
                    new BuiltinTurn(SandboxSlot.INTEGRATOR, "马可·奥勒留", "你本轮以「最小可验证行动」视角发言：")
            );
        };
    }

    /** 历史气泡里展示名：按当前审议室映射内置槽位；未知则回退原文 id。 */
    public static String displayNameForSpeaker(SandboxDeliberationScene scene, String agentSpeakerId) {
        if (agentSpeakerId == null || agentSpeakerId.isBlank()) {
            return "助手";
        }
        SandboxSlot slot = switch (agentSpeakerId) {
            case "auditor" -> SandboxSlot.AUDITOR;
            case "risk_officer" -> SandboxSlot.RISK_OFFICER;
            case "value_judge" -> SandboxSlot.VALUE_JUDGE;
            case "integrator" -> SandboxSlot.INTEGRATOR;
            default -> null;
        };
        if (slot == null) {
            return agentSpeakerId;
        }
        for (BuiltinTurn t : fourBuiltin(scene)) {
            if (t.slot() == slot) {
                return t.displayName();
            }
        }
        return agentSpeakerId;
    }
}
