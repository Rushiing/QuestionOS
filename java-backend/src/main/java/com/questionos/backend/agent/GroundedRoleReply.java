package com.questionos.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.questionos.backend.domain.ConversationMessage;
import com.questionos.backend.domain.MessageRole;

import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Role output separates exact user evidence from explicitly conditional reasoning. */
final class GroundedRoleReply {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern QUANTITY = Pattern.compile(
            "(?:(?:至少|至多|最少|最多|超过|不足|不超过|不低于|低于|大于|少于|>=|<=|≥|≤|>|<)(?:需要|需|耗时|花费)?)?(?:"
                    + "[0-9０-９]+(?:[.．][0-9０-９]+)?\\s*(?:[%％]|[万亿]?(?:元|天|日|周|月|年|人|名|位|个|倍|小时|分钟|次|笔))?"
                    + "|(?:百分之|千分之)[零〇一二两三四五六七八九十百千万亿点]+"
                    + "|[一二两三四五六七八九十]+分之[一二两三四五六七八九十]+"
                    + "|半(?:天|日|周|月|年|小时|分钟)"
                    + "|[零〇一二两三四五六七八九十百千万亿点]+\\s*(?:个月|元|万|千|百|%|％|天|日|周|月|年|人|名|位|个|成|倍|小时|分钟|次|笔))(?:以内|以上|以下|左右|内)?");

    static String systemPrompt(String speaker) {
        String perspective = switch (speaker) {
            case "risk_officer" -> "检查下行风险及其发生条件，不夸大损失。";
            case "value_judge" -> "校准证据强弱，提出能改变判断的验证动作。";
            default -> "检查概念、来源和前提，指出可验证的证据缺口。";
        };
        return """
                你是沙盘审议角色。以用户最新任务为准；历史模型发言是待检验观点，不是事实。
                输出JSON对象，字段必须全部存在：
                {"source_id":"用户原话索引中的一个消息ID","uncertainty":"尚未确认的关键前提",
                "condition":"本轮推演成立所需且尚未验证的前提","consequence":"只有该前提成立才可能发生的影响",
                "proposal":"建议采取的验证动作","question":"一个核心追问"}
                引用由程序从source_id原样呈现，禁止自己重写、摘取或补充用户事实。选择与本轮问题最相关的用户消息。
                其余字段不是事实陈述：uncertainty只写缺口；condition列出所需前提；consequence写条件影响；proposal明确是建议。
                不归因用户没有表达的动机、选择或承诺，不把假设事件当已发生。前序观点若错误，围绕其缺失前提作条件分析。
                condition和consequence中的数字、比例、金额、人数和工期只能原样引用用户给出的数量及上下限表达；不新增精确推算或示例数字（包括中文数字）。
                在缺乏可验证的计算依据时，用定性条件表达，不能创造工期、损失、效果或退款条款。不要给示例数字。
                proposal和question若提出新期限或阈值，必须明确是建议，不能变成用户承诺。至少/至多等上下限不能颠倒。
                不输出Markdown或额外字段。每个分析字段简短直接，question只问一个可独立回答的问题。
                """ + perspective;
    }

    static String sourceIndex(List<ConversationMessage> history) {
        StringBuilder input = new StringBuilder("\n## 用户原话索引（ID只用于source_id，不输出到其他字段）\n");
        for (var message : history) {
            if (message.role() == MessageRole.USER) {
                input.append(message.messageId()).append(": ").append(message.content()).append("\n");
            }
        }
        return input.toString();
    }

    static String render(String raw, List<ConversationMessage> history) {
        return render(raw, history, false);
    }

    static String render(String raw, List<ConversationMessage> history, boolean omitUnsupportedJudgment) {
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("回答必须是合法JSON对象");
        }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("回答必须是JSON对象");
        String sourceId = field(node, "source_id");
        ConversationMessage source = history.stream()
                .filter(m -> m.role() == MessageRole.USER && m.messageId().equals(sourceId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("source_id必须引用用户原话索引，不得引用角色消息"));
        List<String> errors = new ArrayList<>();
        String uncertainty = field(node, "uncertainty");
        String condition = analysis(node, history, "condition", errors);
        String consequence = analysis(node, history, "consequence", errors);
        String proposal = field(node, "proposal");
        String question = firstQuestion(field(node, "question"));
        if (!errors.isEmpty() && !omitUnsupportedJudgment) throw new IllegalArgumentException(String.join("；", errors));
        return "**依据：你的原话**\n> " + source.content().replace("\n", "\n> ")
                + "\n\n**尚待确认**：" + uncertainty
                + (errors.isEmpty() ? "\n\n**条件推演（未验证）**\n- 前提：" + condition
                + "\n- 在此前提下可能：" + consequence
                : "\n\n**核对提示**：这段推演包含无法从你的原话核实的数量或工期，已省略。以下建议仍需验证。")
                + "\n\n**建议验证**：" + proposal + "\n\n❓ " + question;
    }

    static String firstQuestion(String question) {
        for (int i = 0; i < question.length(); i++) {
            if (question.charAt(i) == '?' || question.charAt(i) == '？') return question.substring(0, i + 1);
        }
        return question;
    }

    static String analysis(JsonNode node, List<ConversationMessage> history, String name, List<String> errors) {
        String value = field(node, name);
        var quantities = QUANTITY.matcher(value);
        while (quantities.find()) {
            String quantity = quantities.group().trim();
            boolean supplied = history.stream().filter(m -> m.role() == MessageRole.USER && m.content() != null)
                    .flatMap(m -> QUANTITY.matcher(m.content()).results())
                    .anyMatch(match -> quantitySupported(match.group(), quantity));
            if (!supplied) {
                errors.add(name + "包含无来源数量「" + quantity + "」，删除该推算或改为定性条件");
            }
        }
        return value;
    }

    private static boolean quantitySupported(String supplied, String proposed) {
        String source = supplied.replaceAll("\\s|需要|耗时|花费|需", "");
        String target = proposed.replaceAll("\\s|需要|耗时|花费|需", "");
        if (source.equals(target)) return true;
        // A bare supplied duration may be used as a conditional planning window;
        // a supplied lower/upper bound must never be dropped or reversed.
        String bounds = "^(?:至少|至多|最少|最多|不超过|不低于|超过|不足|低于|大于|少于|>=|<=|≥|≤|>|<)|(?:以内|以上|以下|左右|内)$";
        return source.equals(source.replaceAll(bounds, "")) && source.equals(target.replaceAll(bounds, ""));
    }

    static String field(JsonNode node, String name) {
        if (!node.path(name).isTextual() || node.path(name).asText().isBlank()) {
            throw new IllegalArgumentException("缺少非空字符串字段：" + name);
        }
        return node.path(name).asText().trim();
    }
}
