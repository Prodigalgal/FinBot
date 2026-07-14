package io.omnnu.finbot.migration;

record CanonicalRow(long ordinal, String sourceKey, String json, String sha256) {}
