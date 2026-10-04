package io.omnnu.finbot.infrastructure.research.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.research.dto.HypothesisView;
import io.omnnu.finbot.application.research.dto.HypothesisRevision;
import io.omnnu.finbot.application.research.exception.HypothesisConflictException;
import io.omnnu.finbot.application.research.exception.HypothesisNotFoundException;
import io.omnnu.finbot.application.research.port.out.HypothesisStore;
import io.omnnu.finbot.domain.research.HypothesisStatus;
import io.omnnu.finbot.domain.research.OpportunityHypothesis;
import io.omnnu.finbot.infrastructure.research.adapter.OpportunityHypothesisCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public final class JdbcHypothesisStore implements HypothesisStore {
    private static final System.Logger LOG = System.getLogger(JdbcHypothesisStore.class.getName());
    private static final String VIEW = """
            select hypothesis.*, coalesce(decision.status,'PENDING') as consensus_status,
                   coalesce(decision.winner_candidate_id = hypothesis.candidate_id,false) as selected
            from research_hypothesis hypothesis
            join debate_candidate candidate on candidate.candidate_id = hypothesis.candidate_id
            left join consensus_decision decision on decision.debate_id = candidate.debate_id
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public JdbcHypothesisStore(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override public int captureAndExpire(Instant now, int limit) {
        var sources = jdbc.sql("""
                select candidate.candidate_id, session.run_id, scope.instrument_id, scope.symbol,scope.captured_at,
                    source.artifact_id,source.content::text as content,source.sealed_at
                from debate_candidate candidate
                join debate_session session on session.debate_id = candidate.debate_id and session.panel_purpose = 'RESEARCH'
                join research_market_scope scope on scope.workflow_run_id = session.run_id and scope.environment = 'LIVE'
                join debate_protocol_artifact source on source.artifact_id in (candidate.proposal_artifact_id,candidate.revision_artifact_id)
                where source.status = 'REVEALED' and jsonb_typeof(source.content->'opportunity') = 'object'
                  and source.sealed_at <= :now and scope.captured_at <= source.sealed_at
                  and not exists(select 1 from research_hypothesis_revision revision where revision.source_artifact_id = source.artifact_id)
                  and (exists(select 1 from research_hypothesis previous where previous.candidate_id = candidate.candidate_id)
                    or exists(select 1 from watchlist_item selected join watchlist list on list.watchlist_id = selected.watchlist_id
                      where list.owner_id = 'admin' and list.is_default and selected.research_mode in ('RESEARCH','PINNED')
                        and selected.preferred_instrument_id = scope.instrument_id))
                order by source.sealed_at,source.artifact_id limit :limit
                """).param("now", Timestamp.from(now)).param("limit", limit)
                .query((rs, row) -> new Source(rs.getString("candidate_id"), rs.getString("run_id"), rs.getString("instrument_id"),
                        rs.getString("symbol"), rs.getTimestamp("captured_at").toInstant(), rs.getString("artifact_id"),
                        rs.getString("content"), rs.getTimestamp("sealed_at").toInstant())).list();
        var count = 0;
        for (var source : sources) {
            io.omnnu.finbot.application.operations.service.TaskCancellationContext.throwIfCancelled();
            var body = decodeSource(source.content());
            if (Boolean.TRUE.equals(transactions.execute(transaction -> capture(source, body, now)))) count++;
        }
        var expired = jdbc.sql("""
                select hypothesis_id,version from research_hypothesis
                where expires_at <= :now and status not in ('COMPLETED','REFUTED','EXPIRED')
                order by expires_at,hypothesis_id limit :limit
                """).param("now", Timestamp.from(now)).param("limit", limit)
                .query((rs, row) -> new Expired(rs.getString("hypothesis_id"), rs.getLong("version"))).list();
        for (var due : expired) {
            try { transition(due.id(), due.version(), HypothesisStatus.EXPIRED, "首次登记的有效期已到，不自动延长", now); }
            catch (HypothesisConflictException conflict) { LOG.log(System.Logger.Level.DEBUG, "Hypothesis changed during expiry: {0}", due.id()); }
        }
        return count;
    }

    private boolean capture(Source source, DecodedSource decoded, Instant now) {
        var id = hypothesisId(source.candidateId());
        var encoded = encode(decoded.hypothesis());
        var inserted = jdbc.sql("""
                insert into research_hypothesis(hypothesis_id,candidate_id,workflow_run_id,instrument_id,symbol,source_artifact_id,
                    initial_hypothesis,hypothesis,initial_forecast,status,first_seen_at,information_cutoff,recorded_at,expires_at,version)
                values(:id,:candidate,:run,:instrument,:symbol,:source,cast(:body as jsonb),cast(:body as jsonb),cast(:forecast as jsonb),
                    'PENDING_VALIDATION',:firstSeen,:cutoff,:now,:expires,0) on conflict(candidate_id) do nothing
                """).param("id", id).param("candidate", source.candidateId()).param("run", source.runId())
                .param("instrument", source.instrumentId()).param("symbol", source.symbol()).param("source", source.artifactId())
                .param("body", encoded).param("forecast", decoded.forecast(), java.sql.Types.VARCHAR)
                .param("firstSeen", Timestamp.from(source.sealedAt())).param("cutoff", Timestamp.from(source.cutoff()))
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(source.sealedAt().plusSeconds(decoded.hypothesis().horizonHours() * 3600L))).update();
        var previous = jdbc.sql("select version,status from research_hypothesis where hypothesis_id = :id for update")
                .param("id", id).query((rs, row) -> new State(rs.getLong("version"), HypothesisStatus.valueOf(rs.getString("status")))).single();
        if (jdbc.sql("select exists(select 1 from research_hypothesis_revision where source_artifact_id = :source)")
                .param("source", source.artifactId()).query(Boolean.class).single()) return false;
        var version = inserted == 1 ? 0 : previous.version() + 1;
        var status = inserted == 1 || !previous.status().terminal() ? HypothesisStatus.PENDING_VALIDATION : previous.status();
        if (inserted == 0) {
            jdbc.sql("update research_hypothesis set hypothesis = cast(:body as jsonb),version = :version,status = :status where hypothesis_id = :id")
                    .param("body", encoded).param("version", version).param("status", status.name()).param("id", id).update();
        }
        append(id, version, status, inserted == 1 ? "AI 首次提议，证据与条件待验证" : "AI 修订：保留首次内容、概率和有效期，重新核对条件",
                source.artifactId(), encoded, source.sealedAt());
        return true;
    }

    @Override public List<HypothesisView> recent(int limit) {
        return jdbc.sql(VIEW + " order by hypothesis.first_seen_at desc,hypothesis.hypothesis_id limit :limit")
                .param("limit", limit).query((rs, row) -> view(rs)).list();
    }
    @Override public List<HypothesisRevision> history(String hypothesisId) {
        required(hypothesisId);
        return jdbc.sql("""
                select revision.* from research_hypothesis_revision revision
                join research_hypothesis root on root.hypothesis_id = revision.hypothesis_id
                where revision.hypothesis_id = :id and (revision.version = 0 or revision.version > root.version - 100)
                order by revision.version
                """).param("id", hypothesisId).query((rs, row) -> new HypothesisRevision(rs.getLong("version"),
                        HypothesisStatus.valueOf(rs.getString("status")), rs.getString("reason"), rs.getString("source_artifact_id"),
                        rs.getString("hypothesis") == null ? null : decodeBody(rs.getString("hypothesis")),
                        rs.getTimestamp("occurred_at").toInstant())).list();
    }
    @Override public HypothesisView transition(String hypothesisId, long expectedVersion, HypothesisStatus status, String reason, Instant now) {
        return Objects.requireNonNull(transactions.execute(transaction -> {
            var previous = jdbc.sql("select version,status,expires_at from research_hypothesis where hypothesis_id = :id for update")
                    .param("id", hypothesisId).query((rs, row) -> new TransitionState(rs.getLong("version"),
                            HypothesisStatus.valueOf(rs.getString("status")), rs.getTimestamp("expires_at").toInstant())).optional()
                    .orElseThrow(() -> new HypothesisNotFoundException("前瞻假设不存在"));
            if (previous.version() != expectedVersion) throw new HypothesisConflictException("假设已变化，请刷新后重试");
            if (!previous.status().allows(status)) throw new HypothesisConflictException("当前假设状态不能推进至目标状态");
            if (!previous.expiresAt().isAfter(now) && status != HypothesisStatus.EXPIRED)
                throw new HypothesisConflictException("假设已到期，不能在事后确认");
            jdbc.sql("update research_hypothesis set status = :status,version = version + 1 where hypothesis_id = :id and version = :version")
                    .param("status", status.name()).param("id", hypothesisId).param("version", expectedVersion).update();
            append(hypothesisId, expectedVersion + 1, status, reason, null, null, now);
            return required(hypothesisId);
        }));
    }
    private HypothesisView required(String id) {
        return jdbc.sql(VIEW + " where hypothesis.hypothesis_id = :id").param("id", id).query((rs, row) -> view(rs)).optional()
                .orElseThrow(() -> new HypothesisNotFoundException("前瞻假设不存在"));
    }
    private void append(String id, long version, HypothesisStatus status, String reason, String artifact, String body, Instant at) {
        jdbc.sql("""
                insert into research_hypothesis_revision(hypothesis_id,version,status,reason,source_artifact_id,hypothesis,occurred_at)
                values(:id,:version,:status,:reason,:source,cast(:body as jsonb),:at)
                """).param("id", id).param("version", version).param("status", status.name()).param("reason", reason)
                .param("source", artifact, java.sql.Types.VARCHAR).param("body", body, java.sql.Types.VARCHAR)
                .param("at", Timestamp.from(at)).update();
    }
    private HypothesisView view(ResultSet rs) throws SQLException {
        return new HypothesisView(rs.getString("hypothesis_id"), rs.getString("workflow_run_id"), rs.getString("instrument_id"),
                rs.getString("symbol"), rs.getString("source_artifact_id"), decodeBody(rs.getString("initial_hypothesis")),
                decodeBody(rs.getString("hypothesis")), rs.getString("initial_forecast"), HypothesisStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("information_cutoff").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), rs.getLong("version"),
                rs.getString("consensus_status"), rs.getBoolean("selected"));
    }
    private DecodedSource decodeSource(String content) {
        try {
            var root = json.readTree(content);
            var forecast = root.path("forecast");
            return new DecodedSource(Objects.requireNonNull(OpportunityHypothesisCodec.decode(root.path("opportunity"))),
                    forecast.isMissingNode() || forecast.isNull() ? null : json.writeValueAsString(forecast));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid stored hypothesis source", exception); }
    }
    private OpportunityHypothesis decodeBody(String body) {
        try { return json.readValue(body, OpportunityHypothesis.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid persisted hypothesis", exception); }
    }
    private String encode(Object body) {
        try { return json.writeValueAsString(body); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to encode hypothesis", exception); }
    }
    private static String hypothesisId(String candidate) {
        try { return "hypothesis_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(candidate.getBytes(StandardCharsets.UTF_8))).substring(0, 32); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private record Source(String candidateId, String runId, String instrumentId, String symbol,
                          Instant cutoff, String artifactId, String content, Instant sealedAt) { }
    private record DecodedSource(OpportunityHypothesis hypothesis, String forecast) { }
    private record State(long version, HypothesisStatus status) { }
    private record Expired(String id, long version) { }
    private record TransitionState(long version, HypothesisStatus status, Instant expiresAt) { }
}
