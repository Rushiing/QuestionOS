package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Final reports quote user evidence verbatim instead of asking a model to restate facts. */
final class GroundedReport {
    static final String PROMPT = """
            你是沙盘最终整合官。按最新用户选择收束，不替用户创造选择或承诺。
            只输出JSON：
            {"source_ids":["相关用户消息ID"],"unknowns":["未确认的重要信息"],
            "judgments":[{"condition":"需要成立的前提","consequence":"在此前提下的影响或分歧"}],
            "plan":[{"action":"建议的验证动作","owner":"建议负责人","check":"可观察证据","stop":"建议停止条件"}]}
            source_ids选择最相关的用户更正或明确选择，最多三个。首条用户议题将由程序自动引用，不需重复选择。
            事实只由程序原样引用，其他字段不能重写用户事实或把历史角色的猜测当成事实。
            unknowns列现金余额、支出、工期口径、修复效果、付款性质等与本次决策相关的缺口，不需凑齐。
            judgments列真正的条件判断或分歧，最多三条。condition和consequence不输出用户未给出的精确数量、计算结果或示例数字；必须原样保留至少/至多等数量限定。
            预算不代表可用现金，不能与收入相加推算现金余量。月流失率不是固定人数，不能直接换算短期收入损失。
            plan最多三个具体动作，负责人和停止条件都是建议，不能写成既有约定；以用户最新选择为边界，不引入不允许的交付承诺。
            不做未经验证的财务计算，缺余额和支出时只建议先核实这些输入。依据用户明确的验证周期安排计划，不自行承诺产品交付日期。
            每个字段简洁直接，不嵌套Markdown表格。不要把假设中的付款当成真实到账，不为凑冲突制造对立。
            """;
    private static final ObjectMapper JSON = new ObjectMapper();

    static String render(String raw, List<ConversationMessage> history) {
        return render(raw, history, false);
    }

    static String render(String raw, List<ConversationMessage> history, boolean omitUnsupportedJudgment) {
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("报告必须为合法JSON");
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("报告必须为JSON对象");
        var ids = new LinkedHashSet<String>();
        history.stream().filter(m -> m.role() == MessageRole.USER).findFirst().ifPresent(m -> ids.add(m.messageId()));
        for (var id : array(root, "source_ids", 3)) {
            if (!id.isTextual()) throw new IllegalArgumentException("source_ids必须为用户消息ID");
            ids.add(id.asText());
        }
        StringBuilder out = new StringBuilder("## 🏛️ 整合报告\n### 用户原话与待确认信息\n");
        for (String id : ids) {
            var source = history.stream().filter(m -> m.role() == MessageRole.USER && m.messageId().equals(id))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("报告只能引用用户消息ID"));
            out.append("\n> ").append(source.content().replace("\n", "\n> ")).append("\n");
        }
        out.append("\n**尚待确认**\n");
        for (var item : array(root, "unknowns", 5)) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("unknowns必须为非空文本");
            out.append("- ").append(item.asText()).append("\n");
        }
        out.append("\n### 条件判断（未验证）\n");
        var errors = new ArrayList<String>();
        for (var item : array(root, "judgments", 3)) {
            var itemErrors = new ArrayList<String>();
            String condition = GroundedRoleReply.analysis(item, history, "condition", itemErrors);
            String consequence = GroundedRoleReply.analysis(item, history, "consequence", itemErrors);
            errors.addAll(itemErrors);
            if (!itemErrors.isEmpty()) continue;
            out.append("- **前提**：").append(condition).append("\n  **在此前提下可能**：").append(consequence).append("\n");
        }
        if (!errors.isEmpty()) {
            if (!omitUnsupportedJudgment) throw new IllegalArgumentException(String.join("；", errors));
            out.append("\n**核对提示**：部分推演的数量或工期无法从用户原话核实，已省略；以下建议仍需验证。\n");
        }
        out.append("\n### 建议的验证计划\n");
        for (var item : array(root, "plan", 3)) {
            out.append("\n- **建议动作**：").append(GroundedRoleReply.field(item, "action"))
                    .append("\n  - **建议负责人**：").append(GroundedRoleReply.field(item, "owner"))
                    .append("\n  - **验证依据**：").append(GroundedRoleReply.field(item, "check"))
                    .append("\n  - **建议停止条件**：").append(GroundedRoleReply.field(item, "stop")).append("\n");
        }
        return out.toString();
    }

    private static JsonNode array(JsonNode root, String name, int max) {
        JsonNode items = root.path(name);
        if (!items.isArray() || items.isEmpty() || items.size() > max) {
            throw new IllegalArgumentException(name + "必须为非空数组且不超过" + max + "项");
        }
        return items;
    }
}
