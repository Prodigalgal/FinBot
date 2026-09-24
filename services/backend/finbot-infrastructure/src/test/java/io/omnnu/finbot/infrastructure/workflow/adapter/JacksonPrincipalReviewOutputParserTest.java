package io.omnnu.finbot.infrastructure.workflow.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.debate.PrincipalReviewAction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JacksonPrincipalReviewOutputParserTest {
    private final JacksonPrincipalReviewOutputParser parser =
            new JacksonPrincipalReviewOutputParser(new ObjectMapper());

    @Test
    void parsesConfirmDecision() {
        var json = """
                {
                  "action": "CONFIRM",
                  "summary": "Audited evidence and verified risk metrics",
                  "argument": "All claims are supported by on-chain and orderbook facts.",
                  "tightened_confidence": null,
                  "tightened_max_leverage": null,
                  "tightened_stop_distance": null,
                  "audited_claims": ["claim 1 is true", "claim 2 verified"],
                  "counterexamples": [],
                  "risk_warnings": ["Volatility spike risk around 14:00 UTC"]
                }
                """;
        var parsed = parser.parseProposal(json);
        assertNotNull(parsed);
        assertEquals(PrincipalReviewAction.CONFIRM, parsed.decision().action());
        assertEquals("Audited evidence and verified risk metrics", parsed.decision().summary());
        assertEquals("All claims are supported by on-chain and orderbook facts.", parsed.decision().auditRationale());
        assertNull(parsed.decision().tightenedConfidence());
        assertEquals(2, parsed.decision().auditedClaims().size());
        assertEquals(1, parsed.decision().riskWarnings().size());
    }

    @Test
    void parsesTightenDecision() {
        var json = """
                {
                  "action": "TIGHTEN",
                  "summary": "Monotonically tighten leverage and confidence bounds",
                  "argument": "Orderbook depth is thin; leverage should be reduced to 3x and stop tightened.",
                  "tightened_confidence": 0.65,
                  "tightened_max_leverage": 3,
                  "tightened_stop_distance": 0.015,
                  "audited_claims": ["Liquidity depth is below 500k"],
                  "counterexamples": [],
                  "risk_warnings": ["Thin book slippage risk"]
                }
                """;
        var parsed = parser.parseRevision(json);
        assertNotNull(parsed);
        assertEquals(PrincipalReviewAction.TIGHTEN, parsed.decision().action());
        assertEquals(new BigDecimal("0.65"), parsed.decision().tightenedConfidence());
        assertEquals(new BigDecimal("3"), parsed.decision().tightenedMaxLeverage());
        assertEquals(new BigDecimal("0.015"), parsed.decision().tightenedStopDistance());
    }

    @Test
    void parsesRejectDecisionWithCounterexamples() {
        var json = """
                {
                  "action": "REJECT",
                  "summary": "Irreconcilable fact conflict detected",
                  "argument": "Funding rate contradicts macro thesis and exchange liquidation cascade is ongoing.",
                  "tightened_confidence": null,
                  "tightened_max_leverage": null,
                  "tightened_stop_distance": null,
                  "audited_claims": [],
                  "counterexamples": ["Funding rate flipped negative 20 minutes ago"],
                  "risk_warnings": ["High liquidation cascade risk"]
                }
                """;
        var parsed = parser.parseProposal(json);
        assertNotNull(parsed);
        assertEquals(PrincipalReviewAction.REJECT, parsed.decision().action());
        assertEquals(1, parsed.decision().counterexamples().size());
    }

    @Test
    void rejectsInvalidActionOrMissingCounterexampleOnReject() {
        var invalidAction = """
                {
                  "action": "PROCEED",
                  "summary": "Invalid action",
                  "argument": "Rationale"
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> parser.parseProposal(invalidAction));

        var rejectWithoutEvidence = """
                {
                  "action": "REJECT",
                  "summary": "Rejected without reason",
                  "argument": "I just feel like rejecting",
                  "counterexamples": [],
                  "risk_warnings": []
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> parser.parseProposal(rejectWithoutEvidence));
    }

    @Test
    void parsesBallotTiers() {
        var json = """
                {
                  "preference_tiers": [
                    ["cand_1"],
                    ["cand_2", "cand_3"]
                  ]
                }
                """;
        var ballot = parser.parseBallot(
                json,
                new LogicalRoleKey("risk_auditor"),
                BallotOrientation.FORWARD,
                List.of(
                        new AnonymousCandidateId("cand_1"),
                        new AnonymousCandidateId("cand_2"),
                        new AnonymousCandidateId("cand_3")));
        assertNotNull(ballot);
        assertEquals(2, ballot.preference().preferenceTiers().size());
        assertEquals(1, ballot.preference().preferenceTiers().get(0).size());
        assertEquals(2, ballot.preference().preferenceTiers().get(1).size());
    }
}
