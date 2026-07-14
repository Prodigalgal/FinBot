package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExecutionReview(
        ApprovalStatus status,
        List<String> reasons,
        String policyVersion,
        Instant reviewedAt) {

    public ExecutionReview {
        Objects.requireNonNull(status, "status");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons")).stream()
                .map(reason -> DomainText.required(reason, "reason", 1_000))
                .toList();
        policyVersion = DomainText.required(policyVersion, "policyVersion", 80);
        reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt");
    }
}
