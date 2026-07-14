package io.omnnu.finbot.migration;

record ImportResult(
        String importId,
        String status,
        String sourceSha256,
        int tableCount,
        long sourceRowCount,
        long archivedRowCount,
        long transformedRowCount,
        boolean alreadyCompleted) {}
