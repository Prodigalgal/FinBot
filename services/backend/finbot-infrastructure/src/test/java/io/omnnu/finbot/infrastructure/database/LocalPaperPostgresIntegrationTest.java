package io.omnnu.finbot.infrastructure.database;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.paper.dto.LocalPaperCandidate;
import io.omnnu.finbot.application.paper.exception.LocalPaperConflictException;
import io.omnnu.finbot.domain.paper.*;
import io.omnnu.finbot.infrastructure.paper.persistence.JdbcLocalPaperStore;
import io.omnnu.finbot.infrastructure.research.persistence.JdbcHypothesisStore;
import io.omnnu.finbot.application.research.exception.HypothesisConflictException;
import io.omnnu.finbot.domain.research.HypothesisStatus;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LocalPaperPostgresIntegrationTest {
    private static final PostgresTestDatabase DATABASE = new PostgresTestDatabase("postgres:18-alpine", "finbot", "finbot", "finbot-test");
    private static final String SCHEMA = "paper_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:10Z");
    private static DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcLocalPaperStore store;

    @BeforeAll static void createIsolatedSchema() throws Exception {
        DATABASE.start();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var statement = connection.createStatement()) { statement.execute("CREATE SCHEMA " + SCHEMA); }
        var url = DATABASE.getJdbcUrl() + (DATABASE.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        dataSource = new DriverManagerDataSource(url, DATABASE.getUsername(), DATABASE.getPassword());
        try (var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(),
                new JdbcConnection(dataSource.getConnection()))) { liquibase.update(); }
    }
    @AfterAll static void removeOnlyTheOwnedTestSchema() throws Exception {
        if (dataSource != null && SCHEMA.matches("paper_test_[a-f0-9]{32}")) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
        DATABASE.stop();
    }
    @BeforeEach void resetLedger() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM local_paper_event");
        jdbc.update("DELETE FROM local_paper_trade");
        jdbc.update("UPDATE local_paper_account SET cash_balance=10000,fees_usdt=0,funding_usdt=0,orders_enabled=true,version=0,accept_projections_after=?", Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("UPDATE system_setting SET value_text='true' WHERE setting_key='execution.paper.enabled'");
        jdbc.update("UPDATE watchlist_item SET preferred_instrument_id='instrument_bybit_btcusdt',research_mode='RESEARCH' WHERE watchlist_id='watchlist_admin_default' AND product_id='product_crypto_btc_usdt'");
        store = new JdbcLocalPaperStore(JdbcClient.create(dataSource), new ObjectMapper().findAndRegisterModules(), new DataSourceTransactionManager(dataSource));
    }
    @Test void reservesOnceAndAtomicallyReconcilesBalanceFeesAndTerminalState() {
        var candidate = candidate();
        var pending = pending(candidate);
        assertTrue(store.reserve(candidate, pending, NOW));
        assertFalse(store.reserve(candidate, pending, NOW));
        assertEquals(1, store.account().pendingCount());
        assertTrue(store.account().reservedMargin().signum() > 0);
        var engine = new PaperMatchingEngine();
        var entry = engine.evaluate(pending, observation(NOW.plusSeconds(1), "99", "100"), NOW.plusSeconds(1));
        store.apply(0, entry, NOW.plusSeconds(1));
        var exit = engine.evaluate(entry.trade(), observation(NOW.plusSeconds(2), "111", "112"), NOW.plusSeconds(2));
        store.apply(entry.trade().version(), exit, NOW.plusSeconds(2));
        assertEquals(PaperTrade.Status.CLOSED, store.find(pending.tradeId()).orElseThrow().status());
        assertEquals(0, store.account().openCount());
        assertEquals(0, store.account().reservedMargin().signum());
        var cash = new BigDecimal("10000").add(entry.cashDeltaUsdt()).add(exit.cashDeltaUsdt());
        assertEquals(0, cash.compareTo(store.account().cashBalance()));
        assertEquals(0, exit.trade().feesUsdt().compareTo(store.account().feesUsdt()));
        assertEquals(3, store.events(pending.tradeId(), 100).size());
        assertThrows(LocalPaperConflictException.class, () -> store.apply(0, entry, NOW.plusSeconds(3)));
        assertEquals(0, cash.compareTo(store.account().cashBalance()));
    }
    @Test void duplicateEventRollsBackAllMoneyAndVersionChanges() {
        var candidate = candidate();
        var pending = pending(candidate);
        store.reserve(candidate, pending, NOW);
        var entry = new PaperMatchingEngine().evaluate(pending, observation(NOW.plusSeconds(1), "99", "100"), NOW.plusSeconds(1));
        store.apply(0, entry, NOW.plusSeconds(1));
        var unchanged = new PaperMatchingEngine().evaluate(entry.trade(), observation(NOW.plusSeconds(2), "100", "101"), NOW.plusSeconds(2));
        var duplicated = new PaperMatchingResult(unchanged.trade(), entry.events());
        var cash = store.account().cashBalance();
        assertThrows(LocalPaperConflictException.class, () -> store.apply(1, duplicated, NOW.plusSeconds(2)));
        assertEquals(1, store.find(pending.tradeId()).orElseThrow().version());
        assertEquals(0, cash.compareTo(store.account().cashBalance()));
        assertEquals(2, store.events(pending.tradeId(), 100).size());
    }
    @Test void concurrentReservationsCannotExposeTheSameInstrumentTwice() throws Exception {
        var first = candidate(); var second = candidate();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> store.reserve(first, pending(first), NOW));
            var b = executor.submit(() -> store.reserve(second, pending(second), NOW));
            assertNotEquals(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, store.active(100).size());
        assertEquals(1, store.account().pendingCount());
    }
    @Test void pauseVersionAndResumeCutoffBlockHistoricalPlansAndUnselectedMappings() {
        var candidate = candidate();
        var paused = store.setOrdersEnabled(false, 0, NOW);
        assertFalse(store.reserve(candidate, pending(candidate), NOW));
        assertThrows(LocalPaperConflictException.class, () -> store.setOrdersEnabled(true, 0, NOW));
        store.setOrdersEnabled(true, paused.version(), NOW.plusSeconds(1));
        assertFalse(store.reserve(candidate, pending(candidate), NOW.plusSeconds(1)));
        store.setOrdersEnabled(true, 2, NOW);
        jdbc.update("UPDATE local_paper_account SET accept_projections_after=?", Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("UPDATE watchlist_item SET research_mode='MONITOR' WHERE watchlist_id='watchlist_admin_default' AND product_id='product_crypto_btc_usdt'");
        assertFalse(store.reserve(candidate, pending(candidate), NOW));
    }
    @Test void unavailableMarketRetainsCashReservationAndAllMatchingCursors() {
        var candidate = candidate(); var pending = pending(candidate);
        store.reserve(candidate, pending, NOW);
        var before = store.account();
        store.marketUnavailable(pending.tradeId(), 0, NOW.plusSeconds(1));
        var unavailable = store.find(pending.tradeId()).orElseThrow();
        assertEquals(PaperTrade.Health.MARKET_UNAVAILABLE, unavailable.healthCode());
        assertEquals(pending.candleCursor(), unavailable.candleCursor());
        assertEquals(0, before.cashBalance().compareTo(store.account().cashBalance()));
        assertEquals(0, before.reservedMargin().compareTo(store.account().reservedMargin()));
    }

    @Test void hypothesisCaptureRespectsTheBlindBarrierAndKeepsOriginalProbabilityAndExpiryAcrossRevisions() {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var run = "run_" + suffix; var debate = "debate_" + suffix; var candidate = "candidate_" + suffix;
        jdbc.update("""
                INSERT INTO workflow_run(run_id,idempotency_key,workflow_type,status,trigger_type,request_summary,accepted_at,created_at,updated_at)
                VALUES (?,?,'INSTANT_RESEARCH','COMPLETED','MANUAL','forward fixture',?,?,?)
                """, run, "forward:" + suffix, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO research_market_scope(workflow_run_id,instrument_id,exchange,symbol,interval_seconds,forecast_horizon_seconds,market_reference_price,captured_at,environment)
                VALUES (?,'instrument_bybit_btcusdt','BYBIT','BTCUSDT',3600,86400,100,?,'LIVE')
                """, run, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO debate_session(debate_id,run_id,status,configured_rounds,decision_node_id,started_at,panel_key,panel_purpose)
                VALUES (?,?,'RUNNING',1,'node_test_chair',?,'research','RESEARCH')
                """, debate, run, Timestamp.from(NOW));
        var proposal = hypothesisArtifact(debate, "PROPOSAL", "首次假设", 1, NOW.plusSeconds(5));
        jdbc.update("""
                INSERT INTO debate_candidate(candidate_id,debate_id,origin_node_id,logical_role_key,anonymous_alias,proposal_artifact_id,created_at)
                VALUES (?,?,'node_test_agent','evidence','candidate_ab',?,?)
                """, candidate, debate, proposal, Timestamp.from(NOW.plusSeconds(5)));
        var hypotheses = new JdbcHypothesisStore(JdbcClient.create(dataSource), new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(dataSource));
        assertEquals(0, hypotheses.captureAndExpire(NOW.plusSeconds(20), 100));
        jdbc.update("UPDATE debate_protocol_artifact SET status='REVEALED',revealed_at=? WHERE artifact_id=?", Timestamp.from(NOW.plusSeconds(10)), proposal);
        assertEquals(1, hypotheses.captureAndExpire(NOW.plusSeconds(20), 100));
        assertEquals(0, hypotheses.captureAndExpire(NOW.plusSeconds(20), 100));
        var first = hypotheses.recent(100).getFirst();
        assertEquals(NOW.plusSeconds(5), first.firstSeenAt());
        assertEquals(NOW, first.informationCutoff());
        assertEquals(NOW.plusSeconds(3605), first.expiresAt());
        assertEquals(HypothesisStatus.PENDING_VALIDATION, first.status());
        assertThrows(HypothesisConflictException.class, () -> hypotheses.transition(first.hypothesisId(), 0,
                HypothesisStatus.CONFIRMED, "cannot skip verification", NOW.plusSeconds(21)));
        hypotheses.transition(first.hypothesisId(), 0, HypothesisStatus.WATCHING, "核对来源", NOW.plusSeconds(21));
        assertThrows(HypothesisConflictException.class, () -> hypotheses.transition(first.hypothesisId(), 0,
                HypothesisStatus.REFUTED, "stale version", NOW.plusSeconds(22)));
        var revision = hypothesisArtifact(debate, "REVISION", "修订假设", 48, NOW.plusSeconds(30));
        jdbc.update("UPDATE debate_protocol_artifact SET status='REVEALED',revealed_at=? WHERE artifact_id=?", Timestamp.from(NOW.plusSeconds(35)), revision);
        jdbc.update("UPDATE debate_candidate SET revision_artifact_id=? WHERE candidate_id=?", revision, candidate);
        assertEquals(1, hypotheses.captureAndExpire(NOW.plusSeconds(40), 100));
        var revised = hypotheses.recent(100).getFirst();
        assertEquals("首次假设", revised.initialHypothesis().title());
        assertEquals("修订假设", revised.hypothesis().title());
        assertEquals(first.initialForecastJson(), revised.initialForecastJson());
        assertEquals(first.expiresAt(), revised.expiresAt());
        assertEquals(HypothesisStatus.PENDING_VALIDATION, revised.status());
        assertEquals(3, hypotheses.history(first.hypothesisId()).size());
        hypotheses.transition(first.hypothesisId(), 2, HypothesisStatus.WATCHING, "重新核对", NOW.plusSeconds(41));
        hypotheses.transition(first.hypothesisId(), 3, HypothesisStatus.CONFIRMED, "条件已满足", NOW.plusSeconds(45));
        hypotheses.captureAndExpire(NOW.plusSeconds(3610), 100);
        assertEquals(HypothesisStatus.EXPIRED, hypotheses.recent(100).getFirst().status());
        assertThrows(HypothesisConflictException.class, () -> hypotheses.transition(first.hypothesisId(), 5,
                HypothesisStatus.COMPLETED, "no retrospective success", NOW.plusSeconds(3611)));
    }

    private String hypothesisArtifact(String debate, String phaseType, String title, int horizon, Instant sealedAt) {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var phase = "phase_" + suffix; var task = "debate_task_" + suffix; var artifact = "debate_artifact_" + suffix;
        jdbc.update("""
                INSERT INTO debate_protocol_phase(phase_id,debate_id,protocol,generation,phase_type,status,required_tasks,deadline,opened_at)
                VALUES (?,?,'SDB_SCA_V1',1,?,'OPEN',1,?,?)
                """, phase, debate, phaseType, Timestamp.from(NOW.plusSeconds(300)), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO debate_protocol_task(task_id,phase_id,actor_node_id,logical_role_key,input_hash,status,created_at)
                VALUES (?,?,'node_test_agent','evidence',?,'COMPLETED',?)
                """, task, phase, "0".repeat(64), Timestamp.from(NOW));
        var body = """
                {"opportunity":{"title":"%s","causal_chain":[{"kind":"OBSERVATION","statement":"已公布变化","evidence_refs":["source_1"]},
                {"kind":"INFERENCE","statement":"条件成立时影响下游","evidence_refs":[]}],"required_conditions":["仍需观察"],
                "catalyst_window":"未来公布","priced_in_observation":"未知","alternative_scenario":"其他解释","trigger_condition":"数据确认",
                "invalidation_condition":"反证出现","next_check":"下次公布","missing_data":["市场一致预期"],"horizon_hours":%d,"exposure_group":"event_test"},
                "forecast":{"direction":"UP","direction_probabilities":{"up":0.7,"sideways":0.2,"down":0.1}}}
                """.formatted(title, horizon);
        jdbc.update("""
                INSERT INTO debate_protocol_artifact(artifact_id,task_id,phase_id,status,content_hash,content,sealed_at)
                VALUES (?,?,?,'SEALED',?,cast(? as jsonb),?)
                """, artifact, task, phase, "0".repeat(64), body, Timestamp.from(sealedAt));
        return artifact;
    }

    private LocalPaperCandidate candidate() {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO workflow_run(run_id,idempotency_key,workflow_type,status,trigger_type,request_summary,accepted_at,created_at,updated_at)
                VALUES (?,?,'INSTANT_RESEARCH','COMPLETED','MANUAL','ledger fixture',?,?,?)
                """, "run_" + suffix, "fixture:" + suffix, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO trade_automation_run(automation_run_id,workflow_run_id,status,started_at) VALUES (?,?,'ESTIMATED',?)",
                "automation_" + suffix, "run_" + suffix, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO trade_decision(decision_id,workflow_run_id,symbol,decision_kind,action,confidence,entry_reference,target_price,invalidation_price,rationale,created_at)
                VALUES (?,?,'BTCUSDT','DIRECTIONAL','BUY',0.8,100,110,95,'["fixture"]'::jsonb,?)
                """, "decision_" + suffix, "run_" + suffix, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO trade_proposal(proposal_id,decision_id,symbol,action,status,entry_reference,target_price,invalidation_price,created_at)
                VALUES (?,?,'BTCUSDT','BUY','GENERATED',100,110,95,?)
                """, "proposal_" + suffix, "decision_" + suffix, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO estimated_trade_projection(projection_id,automation_run_id,workflow_run_id,proposal_id,instrument_id,exchange,symbol,side,policy_version,
                  entry_reference,market_price,target_price,stop_price,quantity,contract_size,notional_usdt,leverage,initial_margin_usdt,
                  estimated_entry_cost_usdt,estimated_target_exit_cost_usdt,estimated_stop_exit_cost_usdt,estimated_profit_usdt,estimated_loss_usdt,risk_reward_ratio,calculated_at)
                VALUES (?,?,?,?,'instrument_bybit_btcusdt','BYBIT','BTCUSDT','BUY','paper-default-v1',100,100,110,95,1,1,100,2,50,
                  0.1,0.1,0.1,9,6,1.5,?)
                """, "projection_" + suffix, "automation_" + suffix, "run_" + suffix, "proposal_" + suffix, Timestamp.from(NOW));
        return store.candidates(NOW, 100).stream().filter(candidate -> candidate.projectionId().equals("projection_" + suffix)).findFirst().orElseThrow();
    }
    private static PaperTrade pending(LocalPaperCandidate candidate) {
        return new PaperTrade("paper_" + candidate.projectionId().substring(11), candidate.projectionId(), candidate.instrumentId(), candidate.symbol(), candidate.terms(),
                PaperTrade.Status.PENDING_ENTRY, NOW, null, null, null, null, NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), NOW,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaperTrade.Health.READY, 0);
    }
    private static PaperMarketObservation observation(Instant at, String bid, String ask) {
        return new PaperMarketObservation(new PaperMarketObservation.Quote("BTCUSDT", new BigDecimal(bid), new BigDecimal(ask),
                BigDecimal.TEN, BigDecimal.TEN, new BigDecimal(bid), at, at, "TEST_FIXTURE"), List.of(), List.of(), at);
    }
}
