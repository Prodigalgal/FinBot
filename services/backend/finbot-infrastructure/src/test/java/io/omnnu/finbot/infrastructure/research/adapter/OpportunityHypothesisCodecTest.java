package io.omnnu.finbot.infrastructure.research.adapter;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.infrastructure.workflow.adapter.JacksonStructuredAiOutputParser;
import org.junit.jupiter.api.Test;

class OpportunityHypothesisCodecTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void preservesLegacyNullAndValidatesEvidenceKindsAndHorizon() throws Exception {
        assertNull(OpportunityHypothesisCodec.decode(json.nullNode()));
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(validJson());
        assertEquals(24, OpportunityHypothesisCodec.decode(node).horizonHours());
        node.put("horizon_hours", 0);
        assertThrows(IllegalArgumentException.class, () -> OpportunityHypothesisCodec.decode(node));
        node.put("horizon_hours", 24);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("causal_chain").get(0)).put("kind", "FACT");
        assertThrows(IllegalArgumentException.class, () -> OpportunityHypothesisCodec.decode(node));
    }
    @Test void rejectsUnknownFieldsMissingConditionsAndUnsupportedEvidenceReferences() throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(validJson());
        node.put("auto_execute", true);
        assertThrows(IllegalArgumentException.class, () -> OpportunityHypothesisCodec.decode(node));
        var artifact = json.createObjectNode().put("summary", "test").put("argument", "test").put("confidence", 0.5);
        artifact.putArray("claims"); artifact.putArray("evidence_refs"); artifact.putArray("challenges"); artifact.putArray("revision_notes");
        artifact.set("opportunity", json.readTree(validJson()));
        var parser = new JacksonStructuredAiOutputParser(json);
        assertThrows(IllegalArgumentException.class, () -> parser.parseProposal(artifact.toString()));
        artifact.withArray("evidence_refs").add("official_1");
        assertNotNull(parser.parseProposal(artifact.toString()));
        assertThrows(IllegalArgumentException.class, () -> parser.parseCritique(artifact.toString()));
    }
    static String validJson() {
        return """
                {"title":"前瞻测试","causal_chain":[{"kind":"OBSERVATION","statement":"公开变化","evidence_refs":["official_1"]},
                {"kind":"INFERENCE","statement":"条件成立时可能传导","evidence_refs":[]}],"required_conditions":["条件"],
                "catalyst_window":"下一次公布","priced_in_observation":"未知","alternative_scenario":"替代解释",
                "trigger_condition":"触发条件","invalidation_condition":"反证条件","next_check":"核对下次数据",
                "missing_data":["一致预期"],"horizon_hours":24,"exposure_group":"event_test"}
                """;
    }
}
