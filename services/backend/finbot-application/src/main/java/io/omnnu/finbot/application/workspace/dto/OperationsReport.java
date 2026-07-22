package io.omnnu.finbot.application.workspace.dto;

import java.time.Instant;
import java.util.List;

public record OperationsReport(
        Instant fromInclusive,
        Instant toExclusive,
        List<Section> sections,
        Instant generatedAt) {
    public OperationsReport {
        sections = List.copyOf(sections);
    }

    public record Section(
            String code,
            String title,
            List<Metric> metrics,
            List<Entry> entries) {
        public Section {
            metrics = List.copyOf(metrics);
            entries = List.copyOf(entries);
        }
    }

    public record Metric(String label, String value, String unit, String status) {
    }

    public record Entry(
            String referenceId,
            String title,
            String summary,
            String status,
            Instant occurredAt) {
    }
}
