package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import java.util.List;
import java.util.Objects;

final class SdbScaPromptComposer {
    private static final int MAX_RESEARCH_CONTEXT_CHARACTERS = 120_000;
    private static final int MAX_ANONYMOUS_CONTEXT_CHARACTERS = 150_000;
    private static final String ARTIFACT_SCHEMA = """

只返回一个 JSON 对象，不使用 Markdown 代码块，不输出隐藏思维链，不提及你的厂商、模型、节点、角色名或其他候选身份。结构必须为：
{"summary":"...","argument":"...","confidence":0.0,"claims":[{"statement":"...","evidence_refs":["..."]}],"evidence_refs":["..."],"challenges":["..."],"revision_notes":["..."],"forecast":null,"opportunity":null}
""";
    private static final String BALLOT_SCHEMA = """

只返回 JSON，不解释排序过程，不输出任何身份信息。preference_tiers 从最优到最差排列，同一层表示并列；每个匿名候选必须且只能出现一次：
{"preference_tiers":[["candidate_x"],["candidate_y","candidate_z"]]}
""";

    String proposal(WorkflowExecutionContext execution, WorkflowNodeDefinition node) {
        var roleInstruction = expandRoleTemplate(execution, node);
        return """
                你正在参加 SDB-SCA 同时双盲研究。此阶段与其他席位完全隔离。
                基于同一份冻结研究快照形成独立、可证伪的方案；不得猜测其他席位观点。

                研究请求：
                %s

                冻结研究快照：
                %s

                席位任务：
                %s
                %s
                """.formatted(
                execution.requestSummary(),
                bounded(execution.researchContext(), MAX_RESEARCH_CONTEXT_CHARACTERS),
                roleInstruction,
                proposalSchema(execution));
    }

    String critique(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            DecisionPanelCandidateView candidate) {
        return """
                你正在参加 SDB-SCA 双盲交叉评审。候选来源已永久脱敏。
                仅审阅给定匿名方案，指出证据缺口、逻辑错误、风险与可执行改进；不得推测作者身份。

                研究请求：
                %s

                匿名方案 %s：
                %s

                你的评审职责：
                %s
                %s
                """.formatted(
                execution.requestSummary(),
                candidate.alias().value(),
                bounded(candidate.content(), MAX_ANONYMOUS_CONTEXT_CHARACTERS),
                expandRoleTemplate(execution, node),
                ARTIFACT_SCHEMA);
    }

    String revision(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            DecisionPanelCandidateView ownCandidate,
            List<String> anonymousCritiques) {
        var critiqueContext = new StringBuilder();
        for (var index = 0; index < anonymousCritiques.size(); index++) {
            critiqueContext.append("\n[匿名评审 ")
                    .append(index + 1)
                    .append("]\n")
                    .append(anonymousCritiques.get(index))
                    .append('\n');
        }
        return """
                你正在参加 SDB-SCA 对称隔离修正。所有席位同时收到针对自身方案的完整匿名评审集合。
                只修正自己的方案；保留经得住审查的观点，明确修正内容，不得推测评审者身份。

                研究请求：
                %s

                你的匿名方案 %s：
                %s

                针对该方案的匿名评审：
                %s

                席位任务：
                %s
                %s
                """.formatted(
                execution.requestSummary(),
                ownCandidate.alias().value(),
                bounded(ownCandidate.content(), MAX_ANONYMOUS_CONTEXT_CHARACTERS),
                bounded(critiqueContext.toString(), MAX_ANONYMOUS_CONTEXT_CHARACTERS),
                expandRoleTemplate(execution, node),
                proposalSchema(execution));
    }

    String ballot(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            List<DecisionPanelCandidateView> candidates,
            BallotOrientation orientation) {
        var ordered = orientation == BallotOrientation.FORWARD
                ? candidates
                : candidates.reversed();
        var context = new StringBuilder();
        for (var candidate : ordered) {
            context.append("\n[")
                    .append(candidate.alias().value())
                    .append("]\n")
                    .append(candidate.content())
                    .append('\n');
        }
        return """
                你正在参加 SDB-SCA 匿名社会选择投票。候选展示方向为 %s。
                独立比较证据质量、预测可证伪性、风险完整性和研究请求匹配度。
                展示顺序不得作为排序依据；允许真实并列，但不得遗漏任何候选。

                研究请求：
                %s

                匿名修正版候选：
                %s

                你的评估职责：
                %s
                %s
                """.formatted(
                orientation.name(),
                execution.requestSummary(),
                bounded(context.toString(), MAX_ANONYMOUS_CONTEXT_CHARACTERS),
                expandRoleTemplate(execution, node),
                BALLOT_SCHEMA);
    }

    private static String proposalSchema(WorkflowExecutionContext execution) {
        if (execution.marketScope() == null) {
            return ARTIFACT_SCHEMA;
        }
        var scope = execution.marketScope();
        return ARTIFACT_SCHEMA.replace(
                "\"forecast\":null",
                "\"forecast\":{\"direction\":\"UP|DOWN|SIDEWAYS|UNCERTAIN\","
                        + "\"reference_price\":" + scope.marketReferencePrice() + ","
                        + "\"expected_low\":0.0,\"expected_high\":0.0,"
                        + "\"invalidation_price\":0.0,\"confidence\":0.0,"
                        + "\"direction_probabilities\":{\"up\":0.0,\"sideways\":0.0,\"down\":0.0},"
                        + "\"thesis\":\"...\",\"evidence_refs\":[\"...\"]}")
                + "\n预测期限为 " + scope.forecastHorizonSeconds()
                + " 秒，K 线周期为 " + scope.intervalSeconds()
                + " 秒。direction_probabilities 三项必须覆盖全部方向且总和为 1；"
                + "非 UNCERTAIN 时 direction 必须是唯一最高概率方向，confidence 必须等于该方向概率，"
                + "reference_price 必须等于冻结参考价；"
                + "UNCERTAIN 时所有价格字段必须为 null。"
                + """

同时研究价格尚未兑现的前置驱动，区分背景、催化、传导与价格确认。不要新增交易商品，允许引用其他资产和宏观产业观察。证据分析侧重来源和可知时间，看多与看空分别提出条件及反证，市场结构区分观测和仓位代理，风险控制检查期限、费用及重复敞口。
若有可证伪的前瞻假设，将 opportunity 设为以下对象；证据不足、没有独立增量或只是在复述价格变化时保留 null，不得编造。它只进入待验证记录，不构成交易授权。每个步骤必须标记 OBSERVATION（待核对观测）、ESTIMATE（有来源的估计）、PROXY（代理指标）或 INFERENCE（推断）；非推断步骤必须有 evidence_refs，所有引用也须出现在顶层 evidence_refs，不能把推断写成事实。没有可信来源时间或市场一致预期时写入 missing_data，不用 AI 估计冒充公布值。
{"title":"可证伪的假设","causal_chain":[{"kind":"OBSERVATION","statement":"已观察的背景变化","evidence_refs":["来源"]},{"kind":"INFERENCE","statement":"在必要条件成立时可能的下游影响","evidence_refs":[]}],"required_conditions":["仍需满足的必要条件"],"catalyst_window":"待核对的催化和时间窗口","priced_in_observation":"已有定价的依据或未知","alternative_scenario":"替代解释","trigger_condition":"可核验的确认条件","invalidation_condition":"可核验的反证条件","next_check":"下一次应核对的数据及时间","missing_data":["目前缺失的数据"],"horizon_hours":24,"exposure_group":"共同风险事件或敞口"}
causal_chain 为 2 至 8 步，horizon_hours 为 1 至 2160 小时；有效期从首次提出起固定。更多步骤需展示更多不确定性，不承诺固定滞后或必然上涨，不把不同链条的条件概率直接相乘。NAS100.s、QQQUSDT 等相关敞口不能当作独立机会重复计仓。每条推断说明成立条件，触发和反证应能通过未来观测验证。
""";
    }

    private static String expandRoleTemplate(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node) {
        var template = node.userPromptTemplate() == null
                ? "独立分析研究请求并形成可审计结论。"
                : node.userPromptTemplate();
        return template
                .replace("{{request}}", execution.requestSummary())
                .replace("{{research_context}}", bounded(
                        execution.researchContext(), MAX_RESEARCH_CONTEXT_CHARACTERS))
                .replace("{{round}}", "1")
                .replace("{{context}}", "同阶段信息隔离")
                .replace("${request}", execution.requestSummary())
                .replace("${research_context}", bounded(
                        execution.researchContext(), MAX_RESEARCH_CONTEXT_CHARACTERS))
                .replace("${round}", "1")
                .replace("${context}", "同阶段信息隔离");
    }

    private static String bounded(String value, int maximumCharacters) {
        var source = Objects.requireNonNullElse(value, "");
        return source.length() <= maximumCharacters
                ? source
                : source.substring(0, maximumCharacters) + " [truncated]";
    }
}
