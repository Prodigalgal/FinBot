package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import java.util.List;
import java.util.Objects;

final class ExecutionPromptComposer {
    private static final int MAX_CONTEXT_CHARACTERS = 120_000;
    private static final int MAX_ANONYMOUS_CHARACTERS = 150_000;

    private static final String EXECUTION_SCHEMA = """

只返回一个纯 JSON 对象，禁止输出 Markdown 代码块，禁止输出隐藏思维链，禁止提及任何厂商、模型或角色标识。字段必须完整：
{"action":"BUY","symbol":"BTCUSDT","confidence":"0.80","entry_reference":"65000.0","target_price":"68000.0","invalidation_price":"63500.0","rationale":["严格控制滑点冲击","止损价位于关键支撑位下方"],"evidence_refs":["ref1"],"summary":"执行方案概述","argument":"执行依据与盘口流动性分析"}
注意：action 必须为 BUY、SELL、WATCH、HOLD 之一；方向性动作必须提供 entry_reference, target_price, invalidation_price；非方向性动作这些价格必须为 null。必须严格遵守主审收紧的置信度与风险边界。
""";

    private static final String BALLOT_SCHEMA = """

只返回纯 JSON，不解释排序过程，不输出任何身份信息。preference_tiers 从最优到最差排列，同一层表示并列；每个匿名候选必须且只能出现一次：
{"preference_tiers":[["candidate_x"],["candidate_y","candidate_z"]]}
""";

    String proposal(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            String principalAuditSummary) {
        return """
                你正在参加 SDB-SCA 执行决策共识面板（EXECUTION）。此阶段与其他执行席位完全隔离。
                你的职责是结合上游已审计的研究共识与主审决议，制定具体的模拟盘交易执行方案。
                你必须重点评估盘口流动性深度、非线性滑点冲击、持仓资金费率成本以及止损安全边际。

                交易标的与研究请求：
                %s

                主审独立审计决议快照：
                %s

                市场与研究上下文：
                %s

                席位任务：
                %s
                %s
                """.formatted(
                execution.requestSummary(),
                bounded(principalAuditSummary, MAX_CONTEXT_CHARACTERS),
                bounded(execution.researchContext(), MAX_CONTEXT_CHARACTERS),
                node.userPromptTemplate() == null ? "制定具备流动性深度保护和严格止损的交易执行方案。" : node.userPromptTemplate(),
                EXECUTION_SCHEMA);
    }

    String critique(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            DecisionPanelCandidateView targetCandidate) {
        return """
                你正在参加 SDB-SCA 执行方案双盲交叉评审。候选来源已脱敏。
                审阅给定匿名执行方案，重点指出其在盘口深度冲击、滑点敏感度、资金费侵蚀、止损距离合理性或主审约束遵从上的潜在缺陷。

                交易标的与请求：
                %s

                匿名执行方案 %s：
                %s

                你的评审职责：
                指出该方案的入场点位、止损保护与滑点风险是否存在漏洞。
                %s
                """.formatted(
                execution.requestSummary(),
                targetCandidate.alias().value(),
                bounded(targetCandidate.content(), MAX_ANONYMOUS_CHARACTERS),
                EXECUTION_SCHEMA);
    }

    String revision(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            DecisionPanelCandidateView ownCandidate,
            List<String> critiques) {
        var critiqueContext = new StringBuilder();
        for (var index = 0; index < critiques.size(); index++) {
            critiqueContext.append("\n[匿名执行方案评审 ")
                    .append(index + 1)
                    .append("]\n")
                    .append(critiques.get(index))
                    .append('\n');
        }
        return """
                你正在参加 SDB-SCA 执行方案对称隔离修正。所有席位同时收到针对自身方案的完整匿名评审集合。
                只修正自己的方案；审慎评估评审提出的滑点风险、资金费与点位质疑，完善最终执行方案。

                交易标的与请求：
                %s

                你的匿名执行方案 %s：
                %s

                收到的匿名交叉评审：
                %s

                席位任务：
                %s
                %s
                """.formatted(
                execution.requestSummary(),
                ownCandidate.alias().value(),
                bounded(ownCandidate.content(), MAX_ANONYMOUS_CHARACTERS),
                bounded(critiqueContext.toString(), MAX_CONTEXT_CHARACTERS),
                node.userPromptTemplate() == null ? "根据交叉评审全面修正执行方案。" : node.userPromptTemplate(),
                EXECUTION_SCHEMA);
    }

    String ballot(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            List<DecisionPanelCandidateView> candidates,
            BallotOrientation orientation) {
        var catalog = new StringBuilder();
        for (var candidate : candidates) {
            catalog.append("\n--- 匿名执行方案 ")
                    .append(candidate.alias().value())
                    .append(" ---\n")
                    .append(candidate.content())
                    .append('\n');
        }
        var orientationInstruction = orientation == BallotOrientation.FORWARD
                ? "请按照【综合执行可行性与风险收益比从优到劣】排序。"
                : "对偶校验模式：请按照【综合执行可行性与风险收益比从劣到优】排序。";

        return """
                你正在参加 SDB-SCA 执行共识偏好排序投票。所有候选方案均已匿名化且顺序已随机打乱。
                评估候选方案的入场时机、滑点控制、止损保护与主审契约符合度，提交严格排序。

                排序方向要求：
                %s

                匿名候选执行方案列表：
                %s
                %s
                """.formatted(
                orientationInstruction,
                bounded(catalog.toString(), MAX_ANONYMOUS_CHARACTERS),
                BALLOT_SCHEMA);
    }

    private static String bounded(String text, int maxCharacters) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxCharacters
                ? text
                : text.substring(0, maxCharacters);
    }
}
