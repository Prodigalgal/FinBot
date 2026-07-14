package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageId;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class WorkflowPromptComposer {
    private static final int MAX_CONTEXT_CHARACTERS = 150_000;
    private static final String AGENT_SCHEMA = """

只返回一个 JSON 对象，不要使用 Markdown 代码块，也不要输出隐藏思维链。argument 应提供可审计、简洁的证据推理。结构必须为：
{"summary":"...","argument":"...","confidence":0.0,"claims":[{"statement":"...","evidence_refs":["..."]}],"evidence_refs":["..."],"challenges":["..."],"revision_notes":["..."]}
""";
    private static final String CHAIR_SCHEMA = """

只返回一个 JSON 对象，不要使用 Markdown 代码块，也不要输出隐藏思维链。结构必须为：
{"debate_summary":["..."],"major_disagreements":["..."],"missing_evidence":["..."],"verdicts":[{"summary":"...","evidence_refs":["..."]}],"confidence":0.0,"evidence_refs":["..."]}
""";

    PromptMaterial composeAgent(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            int roundIndex,
            List<AgentMessage> messages) {
        var selected = selectAgentContext(execution.definitionVersion(), node, roundIndex, messages);
        var context = renderContext(selected, node.contextMode());
        var prompt = expandTemplate(
                node.userPromptTemplate(),
                execution.requestSummary(),
                execution.researchContext(),
                roundIndex,
                context);
        return new PromptMaterial(prompt + AGENT_SCHEMA, messageIds(selected));
    }

    PromptMaterial composeChair(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition chair,
            List<AgentMessage> messages) {
        var selected = messages.stream()
                .filter(message -> message.roundIndex() > 0)
                .sorted(messageOrder())
                .toList();
        selected = takeLatest(selected, chair.contextMaximumMessages());
        var context = renderContext(selected, WorkflowContextMode.UPSTREAM);
        var prompt = expandTemplate(
                chair.userPromptTemplate(),
                execution.requestSummary(),
                execution.researchContext(),
                execution.definitionVersion().defaultDebateRounds(),
                context);
        return new PromptMaterial(prompt + CHAIR_SCHEMA, messageIds(selected));
    }

    private static List<AgentMessage> selectAgentContext(
            WorkflowDefinitionVersion version,
            WorkflowNodeDefinition node,
            int roundIndex,
            List<AgentMessage> messages) {
        if (node.contextMode() == WorkflowContextMode.NONE || node.contextMaximumMessages() == 0) {
            return List.of();
        }
        var incomingNodeIds = version.edges().stream()
                .filter(edge -> !edge.loopEdge())
                .filter(edge -> edge.targetNodeId().equals(node.nodeId()))
                .filter(edge -> edge.contextMode() != WorkflowEdgeContextMode.EXCLUDE)
                .map(edge -> edge.sourceNodeId())
                .collect(Collectors.toUnmodifiableSet());

        var selected = new LinkedHashMap<AgentMessageId, AgentMessage>();
        messages.stream()
                .filter(message -> message.roundIndex() == roundIndex)
                .filter(message -> incomingNodeIds.contains(message.nodeId()))
                .sorted(messageOrder())
                .forEach(message -> selected.put(message.messageId(), message));

        if (node.contextHistoryRounds() > 0 && roundIndex > 1) {
            var firstHistoryRound = Math.max(1, roundIndex - node.contextHistoryRounds());
            var history = messages.stream()
                    .filter(message -> message.roundIndex() >= firstHistoryRound)
                    .filter(message -> message.roundIndex() < roundIndex)
                    .filter(message -> historyVisible(node, incomingNodeIds, message))
                    .sorted(messageOrder())
                    .toList();
            if (node.contextMode() == WorkflowContextMode.LATEST) {
                history = latestPerNode(history);
            }
            history.forEach(message -> selected.put(message.messageId(), message));
        }
        return takeLatest(
                selected.values().stream().sorted(messageOrder()).toList(),
                node.contextMaximumMessages());
    }

    private static boolean historyVisible(
            WorkflowNodeDefinition node,
            Set<WorkflowNodeId> incomingNodeIds,
            AgentMessage message) {
        return switch (node.contextMode()) {
            case UPSTREAM, SELECTED -> incomingNodeIds.contains(message.nodeId())
                    || node.nodeId().equals(message.nodeId());
            case LATEST, CLAIMS_ONLY, SUMMARY -> true;
            case NONE -> false;
        };
    }

    private static List<AgentMessage> latestPerNode(List<AgentMessage> messages) {
        var latest = new LinkedHashMap<WorkflowNodeId, AgentMessage>();
        messages.forEach(message -> latest.merge(
                message.nodeId(),
                message,
                (first, second) -> messageOrder().compare(first, second) < 0 ? second : first));
        return latest.values().stream().sorted(messageOrder()).toList();
    }

    private static List<AgentMessage> takeLatest(List<AgentMessage> messages, int maximumMessages) {
        if (maximumMessages <= 0 || messages.isEmpty()) {
            return List.of();
        }
        var first = Math.max(0, messages.size() - maximumMessages);
        return List.copyOf(messages.subList(first, messages.size()));
    }

    private static String renderContext(
            List<AgentMessage> messages,
            WorkflowContextMode contextMode) {
        if (messages.isEmpty()) {
            return "无可用上游观点。";
        }
        var result = new StringBuilder(Math.min(MAX_CONTEXT_CHARACTERS, messages.size() * 2_000));
        for (var message : messages) {
            var block = renderMessage(message, contextMode);
            if (result.length() + block.length() > MAX_CONTEXT_CHARACTERS) {
                var remaining = MAX_CONTEXT_CHARACTERS - result.length();
                if (remaining > 0) {
                    result.append(block, 0, Math.min(remaining, block.length()));
                }
                break;
            }
            result.append(block);
        }
        return result.toString();
    }

    private static String renderMessage(
            AgentMessage message,
            WorkflowContextMode contextMode) {
        var content = message.content();
        var result = new StringBuilder()
                .append("\n[message_id=").append(message.messageId().value())
                .append(", role=").append(message.roleName())
                .append(", round=").append(message.roundIndex())
                .append(", status=").append(message.status()).append("]\n")
                .append("summary: ").append(content.summary()).append('\n');
        if (contextMode == WorkflowContextMode.CLAIMS_ONLY) {
            content.claims().forEach(claim -> result
                    .append("claim: ").append(claim.statement())
                    .append(" evidence=").append(claim.evidenceReferences()).append('\n'));
            return result.toString();
        }
        if (contextMode == WorkflowContextMode.SUMMARY) {
            return result.toString();
        }
        result.append("argument: ").append(content.argument()).append('\n');
        if (!content.evidenceReferences().isEmpty()) {
            result.append("evidence_refs: ").append(content.evidenceReferences()).append('\n');
        }
        if (!content.challenges().isEmpty()) {
            result.append("challenges: ").append(content.challenges()).append('\n');
        }
        if (!content.revisionNotes().isEmpty()) {
            result.append("revision_notes: ").append(content.revisionNotes()).append('\n');
        }
        return result.toString();
    }

    private static String expandTemplate(
            String template,
            String requestSummary,
            String researchContext,
            int roundIndex,
            String context) {
        var source = template == null ? "分析研究请求并形成可审计结论。" : template;
        var expanded = source
                .replace("{{request}}", requestSummary)
                .replace("{{research_context}}", researchContext)
                .replace("{{round}}", Integer.toString(roundIndex))
                .replace("{{context}}", context)
                .replace("${request}", requestSummary)
                .replace("${research_context}", researchContext)
                .replace("${round}", Integer.toString(roundIndex))
                .replace("${context}", context);
        var usedPlaceholders = !expanded.equals(source);
        if (usedPlaceholders) {
            return expanded;
        }
        return expanded
                + "\n\n研究请求：\n" + requestSummary
                + "\n\n采集、清洗后的证据包：\n" + boundedResearchContext(researchContext)
                + "\n\n当前轮次：" + roundIndex
                + "\n\n传入观点：\n" + context;
    }

    private static String boundedResearchContext(String researchContext) {
        var maximum = 80_000;
        if (researchContext.length() <= maximum) {
            return researchContext;
        }
        return researchContext.substring(0, maximum) + " [truncated]";
    }

    private static List<AgentMessageId> messageIds(List<AgentMessage> messages) {
        var ids = new LinkedHashSet<AgentMessageId>();
        messages.forEach(message -> ids.add(message.messageId()));
        return List.copyOf(ids);
    }

    private static Comparator<AgentMessage> messageOrder() {
        return Comparator.comparingInt(AgentMessage::roundIndex)
                .thenComparingInt(AgentMessage::turnIndex)
                .thenComparing(message -> message.messageId().value());
    }

    record PromptMaterial(String userPrompt, List<AgentMessageId> repliesTo) {
        PromptMaterial {
            repliesTo = List.copyOf(repliesTo);
        }
    }
}
