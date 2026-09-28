package io.omnnu.finbot.domain.debate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DebateArtifactTest {
    @Test
    void contentHashMatchesSha256OfExactUtf8Content() {
        assertEquals(
                "5041bf1f713df204784353e82f6a4a535931cb64f1f4b4a5aeaffcb720918b22",
                DebateArtifact.contentHashFor("{\"x\":1}"));
    }
}
