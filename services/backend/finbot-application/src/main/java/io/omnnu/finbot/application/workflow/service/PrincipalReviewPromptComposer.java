package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import java.util.List;
import java.util.Objects;

final class PrincipalReviewPromptComposer {
    private static final int MAX_CONTEXT_CHARACTERS = 120_000;
    private static final int MAX_ANONYMOUS_CHARACTERS = 150_000;

    private static final String ARTIFACT_SCHEMA = """

只返回一个 JSON 对象，不使用 Markdown 代码块，不输出隐藏思维链，不提及你的厂商、模型、节点、角色名或其他候选身份。结构必须为：
{"action":"CONFIRM","summary":"...","argument":"...","tightened_confidence":null,"tightened_max_leverage":null,"tightened_stop_distance":null,"audited_claims":["..."],"counterexamples":[],"risk_warnings":[]}
注意：action 必须是 CONFIRM、TIGHTEN 或 REJECT 之一。如 action 为 REJECT，必须在 counterexamples 或 risk_warnings 中提供至少一条不可调和的反例证据。
""";

    private static final String BALLOT_SCHEMA = """

只返回 JSON，不解释排序过程，不输出任何身份信息。preference_tiers 从最优到最差排列，同一层表示并列；每个匿名候选必须且只能出现一次：
{"preference_tiers":[["candidate_x"],["candidate_y","candidate_z"]]}
""";

    String proposal(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            String researchConsensusSummary) {
        return """
                你正在参加 SDB-SCA 主审独立审计面板（PRINCIPAL_REVIEW）。此阶段与其他席位完全隔离。
                你的职责是对上游研究共识进行独立审计、反例检索、市场结构合规性与风险排查。
                你只能 CONFIRM（确认）、TIGHTEN（收紧约束）或 REJECT（驳回），严禁凭空创造新产品、新方向或无事实支撑的假设。

                研究请求：
                %s

                上游研究共识快照：
                %s

                冻结事实集：
                %s

                席位任务：
                %s
                %s
                """.formatted(
                execution.requestSummary(),
                bounded(researchConsensusSummary, MAX_CONTEXT_CHARACTERS),
                bounded(execution.researchContext(), MAX_CONTEXT_CHARACTERS),
                node.userPromptTemplate() == null ? "全面审计上游结论并提出确定性主审决策。" : node.userPromptTemplate(),
                ARTIFACT_SCHEMA);
    }

    String critique(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            DecisionPanelCandidateView targetCandidate) {
        return """
                你正在参加 SDB-SCA 主审双盲交叉评审。候选来源已脱敏。
                仅审阅给定匿名主审审计方案，指出其在证据审计、反例覆盖、约束收紧或驳回理由上的漏洞与合规问题。

                研究请求：
                %s

                匿名主审方案 %s：
                %s

                你的评审职责：
                指出该审计结论是否有理有据、是否忽视关键反例、收紧幅度是否合规。
                %s
                """.formatted(
                execution.requestSummary(),
                targetCandidate.alias().value(),
                bounded(targetCandidate.content(), MAX_ANONYMOUS_CHARACTERS),
                ARTIFACT_SCHEMA);
    }

    String revision(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            DecisionPanelCandidateView ownCandidate,
            List<String> critiques) {
        var critiqueContext = new StringBuilder();
        for (var index = 0; index < critiques.size(); index++) {
            critiqueContext.append("\n[匿名主审评审 ")
                    .append(index + 1)
                    .append("]\n")
                    .append(critiques.get(index))
                    .append('\n');
        }
        return """
                你正在参加 SDB-SCA 主审对称隔离修正。所有席位同时收到针对自身主审意见的完整匿名评审集合。
                只修正自己的方案；审慎评估评审提出的反例与质疑，明确终局主审决策（CONFIRM / TIGHTEN / REJECT）。

                研究请求：
                %s

                你的匿名主审方案 %s：
                %s

                针对该方案的匿名评审：
                %s

                席位任务：
                保持审计独立性，更新修正后的结构化主审决策。
                %s
                """.formatted(
                execution.requestSummary(),
                ownCandidate.alias().value(),
                bounded(ownCandidate.content(), MAX_ANONYMOUS_CHARACTERS),
                bounded(critiqueContext.toString(), MAX_ANONYMOUS_CHARACTERS),
                ARTIFACT_SCHEMA);
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
                你正在参加 SDB-SCA 主审匿名社会选择投票。候选展示方向为 %s。
                独立比较各主审意见的审计深度、反例可信度、风险控制质量与事实一致性。
                展示顺序不得作为排序依据；允许真实并列，但不得遗漏任何候选。

                研究请求：
                %s

                匿名修正版主审候选：
                %s

                你的评估职责：
                对所有匿名主审方案按综合质量排序，投票选出最严格稳健的主审决策。
                %s
                """.formatted(
                orientation.name(),
                execution.requestSummary(),
                context.toString(),
                BALLOT_SCHEMA);
    }

    private static String bounded(String value, int maximumCharacters) {
        var source = Objects.requireNonNullElse(value, "");
        return source.length() <= maximumCharacters
                ? source
                : source.substring(0, maximumCharacters) + " [truncated]";
    }
}
