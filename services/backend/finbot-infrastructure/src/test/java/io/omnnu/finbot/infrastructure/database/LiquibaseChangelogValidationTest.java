package io.omnnu.finbot.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

class LiquibaseChangelogValidationTest {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Test
    void parsesAndValidatesPostgresChangelogOffline() throws Exception {
        var resourceAccessor = new ClassLoaderResourceAccessor();
        var database = DatabaseFactory.getInstance().openDatabase(
                "offline:postgresql?outputLiquibaseSql=all",
                null,
                null,
                null,
                resourceAccessor);
        try (var liquibase = new Liquibase(CHANGELOG, resourceAccessor, database)) {
            liquibase.validate();
            var changeSets = liquibase.getDatabaseChangeLog().getChangeSets();
            assertEquals(13, changeSets.size());
            assertEquals("001-foundation", changeSets.getFirst().getId());
            assertEquals("002-platform-foundation", changeSets.get(1).getId());
            assertEquals("003-background-operations", changeSets.get(2).getId());
            assertEquals("004-ai-workflow", changeSets.get(3).getId());
            assertEquals("004-workflow-immutability-function", changeSets.get(4).getId());
            assertEquals("004-workflow-default-seed", changeSets.get(5).getId());
            assertEquals("005-ingestion-research", changeSets.get(6).getId());
            assertEquals("006-market-quant", changeSets.get(7).getId());
            assertEquals("007-risk-execution", changeSets.get(8).getId());
            assertEquals("008-workflow-idempotency", changeSets.get(9).getId());
            assertEquals("009-legacy-archive", changeSets.get(10).getId());
            assertEquals("010-exchange-route-recovery", changeSets.get(11).getId());
            assertEquals("011-research-task-mode", changeSets.getLast().getId());
            assertTrue(changeSets.getFirst().getChanges().size() >= 1);
        }
    }
}
