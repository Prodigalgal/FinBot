package io.omnnu.finbot.migration;

record SourceTable(String name, String schemaSql, long rowCount, ImportDisposition disposition) {}
