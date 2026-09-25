package io.omnnu.finbot.infrastructure.chat.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.chat.dto.AnalysisChatSession;
import io.omnnu.finbot.application.chat.dto.AnalysisChatTurn;
import io.omnnu.finbot.application.chat.exception.AnalysisChatConflictException;
import io.omnnu.finbot.application.chat.exception.AnalysisChatNotFoundException;
import io.omnnu.finbot.application.chat.port.out.AnalysisChatStore;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcAnalysisChatStore implements AnalysisChatStore {
    private static final String TURN_SELECT = """
            select turn.turn_id, turn.chat_id, turn.turn_number, turn.request_key, turn.user_message,
                   turn.workflow_prompt, turn.workflow_run_id, turn.task_id,
                   run.status as workflow_status, task.status as task_status,
                   answer.summary as answer_summary, answer.argument as answer,
                   turn.created_at
            from analysis_chat_turn turn
            left join workflow_run run on run.run_id = turn.workflow_run_id
            left join background_task task on task.task_id = turn.task_id
            left join lateral (
                select message.summary, message.argument
                from agent_message message
                join debate_session panel on panel.debate_id = message.debate_id
                where message.run_id = turn.workflow_run_id
                  and panel.panel_purpose = 'RESEARCH'
                  and message.status = 'COMPLETED'
                  and message.message_type in ('CONSENSUS_RESULT', 'CHAIR_VERDICT')
                order by case when message.message_type = 'CONSENSUS_RESULT' then 0 else 1 end,
                         message.created_at desc, message.id desc
                limit 1
            ) answer on true
            """;

    private final JdbcClient jdbcClient;

    public JdbcAnalysisChatStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public AnalysisChatSession create(AnalysisChatSession session) {
        jdbcClient.sql("""
                insert into analysis_chat_session (
                    chat_id, title, workflow_version_id, created_at, updated_at
                ) values (:chatId, :title, :workflowVersionId, :createdAt, :updatedAt)
                """)
                .param("chatId", session.chatId())
                .param("title", session.title())
                .param("workflowVersionId", session.workflowVersionId())
                .param("createdAt", timestamp(session.createdAt()))
                .param("updatedAt", timestamp(session.updatedAt()))
                .update();
        return session;
    }

    @Override
    public List<AnalysisChatSession> list(String beforeChatId, String search, int limit) {
        return jdbcClient.sql("""
                select chat.chat_id, chat.title, chat.workflow_version_id,
                       chat.created_at, chat.updated_at
                from analysis_chat_session chat
                where position(lower(:search) in lower(chat.title)) > 0
                  and (:beforeChatId = '' or exists (
                      select 1 from analysis_chat_session cursor
                      where cursor.chat_id = :beforeChatId
                        and (chat.updated_at, chat.id) < (cursor.updated_at, cursor.id)
                  ))
                order by chat.updated_at desc, chat.id desc
                limit :limit
                """)
                .param("beforeChatId", beforeChatId == null ? "" : beforeChatId)
                .param("search", search)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> session(resultSet))
                .list();
    }

    @Override
    public Optional<AnalysisChatSession> find(String chatId) {
        return jdbcClient.sql("""
                select chat_id, title, workflow_version_id, created_at, updated_at
                from analysis_chat_session where chat_id = :chatId
                """)
                .param("chatId", chatId)
                .query((resultSet, rowNumber) -> session(resultSet))
                .optional();
    }

    @Override
    public List<AnalysisChatTurn> turns(String chatId, Integer beforeTurnNumber, int limit) {
        var rows = jdbcClient.sql(TURN_SELECT + """
                where turn.chat_id = :chatId and turn.turn_number < :beforeTurnNumber
                order by turn.turn_number desc
                limit :limit
                """)
                .param("chatId", chatId)
                .param("beforeTurnNumber", beforeTurnNumber == null ? Integer.MAX_VALUE : beforeTurnNumber)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> turn(resultSet))
                .list();
        var chronological = new ArrayList<>(rows);
        java.util.Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    @Override
    @Transactional
    public AnalysisChatTurn reserve(
            String chatId,
            String turnId,
            String requestKey,
            String userMessage,
            String workflowPrompt,
            Instant createdAt) {
        var locked = jdbcClient.sql("""
                select chat_id from analysis_chat_session where chat_id = :chatId for update
                """)
                .param("chatId", chatId)
                .query(String.class)
                .optional();
        if (locked.isEmpty()) {
            throw new AnalysisChatNotFoundException(chatId);
        }
        var existing = jdbcClient.sql(TURN_SELECT + """
                where turn.chat_id = :chatId and turn.request_key = :requestKey
                """)
                .param("chatId", chatId)
                .param("requestKey", requestKey)
                .query((resultSet, rowNumber) -> turn(resultSet))
                .optional();
        if (existing.isPresent()) {
            var saved = existing.orElseThrow();
            if (!saved.userMessage().equals(userMessage)) {
                throw new AnalysisChatConflictException("同一请求键不能用于不同的聊天消息");
            }
            return saved;
        }
        var last = jdbcClient.sql(TURN_SELECT + """
                where turn.chat_id = :chatId order by turn.turn_number desc limit 1
                """)
                .param("chatId", chatId)
                .query((resultSet, rowNumber) -> turn(resultSet))
                .optional();
        if (last.isPresent() && !last.orElseThrow().finished()) {
            if (last.orElseThrow().workflowRunId() == null
                    && last.orElseThrow().userMessage().equals(userMessage)) {
                return last.orElseThrow();
            }
            throw new AnalysisChatConflictException("上一条分析尚未结束，请等待完成后继续提问");
        }
        var nextNumber = last.map(saved -> saved.turnNumber() + 1).orElse(1);
        jdbcClient.sql("""
                insert into analysis_chat_turn (
                    turn_id, chat_id, turn_number, request_key, user_message,
                    workflow_prompt, created_at
                ) values (
                    :turnId, :chatId, :turnNumber, :requestKey, :userMessage,
                    :workflowPrompt, :createdAt
                )
                """)
                .param("turnId", turnId)
                .param("chatId", chatId)
                .param("turnNumber", nextNumber)
                .param("requestKey", requestKey)
                .param("userMessage", userMessage)
                .param("workflowPrompt", workflowPrompt)
                .param("createdAt", timestamp(createdAt))
                .update();
        var title = userMessage.replaceAll("\\s+", " ").strip();
        if (nextNumber == 1) {
            jdbcClient.sql("""
                    update analysis_chat_session set title = :title, updated_at = :updatedAt
                    where chat_id = :chatId
                    """)
                    .param("title", title.substring(0, Math.min(title.length(), 80)))
                    .param("updatedAt", timestamp(createdAt))
                    .param("chatId", chatId)
                    .update();
        } else {
            jdbcClient.sql("""
                    update analysis_chat_session set updated_at = :updatedAt where chat_id = :chatId
                    """)
                    .param("updatedAt", timestamp(createdAt))
                    .param("chatId", chatId)
                    .update();
        }
        return turnById(turnId);
    }

    @Override
    @Transactional
    public AnalysisChatTurn attach(String turnId, String workflowRunId, String taskId, Instant acceptedAt) {
        var changed = jdbcClient.sql("""
                update analysis_chat_turn
                set workflow_run_id = :workflowRunId, task_id = :taskId, accepted_at = :acceptedAt
                where turn_id = :turnId
                  and (workflow_run_id is null or workflow_run_id = :workflowRunId)
                  and (task_id is null or task_id = :taskId)
                """)
                .param("workflowRunId", workflowRunId)
                .param("taskId", taskId)
                .param("acceptedAt", timestamp(acceptedAt))
                .param("turnId", turnId)
                .update();
        if (changed != 1) {
            throw new AnalysisChatConflictException("聊天消息已关联到其他工作流运行");
        }
        return turnById(turnId);
    }

    private AnalysisChatTurn turnById(String turnId) {
        return jdbcClient.sql(TURN_SELECT + "where turn.turn_id = :turnId")
                .param("turnId", turnId)
                .query((resultSet, rowNumber) -> turn(resultSet))
                .single();
    }

    private static AnalysisChatSession session(ResultSet resultSet) throws SQLException {
        return new AnalysisChatSession(
                resultSet.getString("chat_id"),
                resultSet.getString("title"),
                resultSet.getString("workflow_version_id"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static AnalysisChatTurn turn(ResultSet resultSet) throws SQLException {
        var workflowStatus = resultSet.getString("workflow_status");
        var taskStatus = resultSet.getString("task_status");
        return new AnalysisChatTurn(
                resultSet.getString("turn_id"),
                resultSet.getString("chat_id"),
                resultSet.getInt("turn_number"),
                resultSet.getString("request_key"),
                resultSet.getString("user_message"),
                resultSet.getString("workflow_prompt"),
                resultSet.getString("workflow_run_id"),
                resultSet.getString("task_id"),
                workflowStatus == null ? null : WorkflowRunStatus.valueOf(workflowStatus),
                taskStatus == null ? null : BackgroundTaskStatus.valueOf(taskStatus),
                resultSet.getString("answer_summary"),
                resultSet.getString("answer"),
                instant(resultSet, "created_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Objects.requireNonNull(resultSet.getObject(column, OffsetDateTime.class), column).toInstant();
    }
}
