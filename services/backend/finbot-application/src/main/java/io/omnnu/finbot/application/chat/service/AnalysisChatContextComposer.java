package io.omnnu.finbot.application.chat.service;

import io.omnnu.finbot.application.chat.dto.AnalysisChatTurn;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import java.util.List;

final class AnalysisChatContextComposer {
    private static final int MAXIMUM_PROMPT_LENGTH = 2_000;
    private static final int MAXIMUM_PRIOR_ANSWER_LENGTH = 320;

    private AnalysisChatContextComposer() {
    }

    static String compose(List<AnalysisChatTurn> previousTurns, String message) {
        var current = "当前分析问题：\n" + message;
        if (current.length() >= MAXIMUM_PROMPT_LENGTH) {
            return message;
        }
        var context = new StringBuilder();
        for (var index = previousTurns.size() - 1; index >= 0; index--) {
            var turn = previousTurns.get(index);
            if (turn.taskStatus() != BackgroundTaskStatus.COMPLETED
                    || turn.answer() == null || turn.answer().isBlank()) {
                continue;
            }
            var answer = turn.answer().strip();
            if (answer.length() > MAXIMUM_PRIOR_ANSWER_LENGTH) {
                answer = answer.substring(0, MAXIMUM_PRIOR_ANSWER_LENGTH);
            }
            var pair = "用户：" + turn.userMessage() + "\n研究共识：" + answer + "\n";
            if (context.length() + pair.length() + current.length() + 18 > MAXIMUM_PROMPT_LENGTH) {
                continue;
            }
            context.insert(0, pair);
        }
        return context.isEmpty() ? message : "近期会话背景：\n" + context + "\n" + current;
    }
}
