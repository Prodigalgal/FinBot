package io.omnnu.finbot.application.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.chat.dto.AnalysisChatTurn;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AnalysisChatContextComposerTest {
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");

    @Test
    void includesCompletedConsensusAndSkipsFailedTurns() {
        var prompt = AnalysisChatContextComposer.compose(List.of(
                turn(1, "上一轮问题", "上一轮共识", BackgroundTaskStatus.COMPLETED),
                turn(2, "失败的问题", "未采纳输出", BackgroundTaskStatus.FAILED)),
                "本轮问题");

        assertTrue(prompt.contains("用户：上一轮问题\n研究共识：上一轮共识"));
        assertTrue(prompt.endsWith("当前分析问题：\n本轮问题"));
        assertFalse(prompt.contains("失败的问题"));
        assertFalse(prompt.contains("未采纳输出"));
    }

    @Test
    void keepsLongCurrentQuestionIntactWithinTheWorkflowLimit() {
        var question = "现".repeat(1_995);

        var prompt = AnalysisChatContextComposer.compose(List.of(
                turn(1, "旧问题", "旧答案", BackgroundTaskStatus.COMPLETED)), question);

        assertEquals(question, prompt);
        assertTrue(prompt.length() <= 2_000);
    }

    private static AnalysisChatTurn turn(
            int number,
            String question,
            String answer,
            BackgroundTaskStatus status) {
        return new AnalysisChatTurn(
                "chatturn_test" + number, "chat_test001", number,
                "request_test" + number, question, question,
                "run_test" + number, "task_test" + number,
                null, status, null, answer, NOW);
    }
}
