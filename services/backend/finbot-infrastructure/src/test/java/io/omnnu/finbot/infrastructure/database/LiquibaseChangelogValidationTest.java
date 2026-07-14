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
            assertEquals(23, changeSets.size());
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
            assertEquals("011-research-task-mode", changeSets.get(12).getId());
            assertEquals("012-operational-ai-routing", changeSets.get(13).getId());
            assertEquals("013-terminalize-exhausted-workflows", changeSets.get(14).getId());
            assertEquals("014-trade-automation-retry", changeSets.get(15).getId());
            assertEquals("015-operational-ai-timeouts", changeSets.get(16).getId());
            assertEquals("016-configurable-ai-fallback", changeSets.get(17).getId());
            assertEquals("017-maximal-default-workflow", changeSets.get(18).getId());
            assertEquals("018-adjustable-leverage", changeSets.get(19).getId());
            assertEquals("019-x-bybit-tradfi-perpetuals", changeSets.get(20).getId());
            assertEquals("020-workflow-activation", changeSets.get(21).getId());
            assertEquals("021-exchange-product-controls", changeSets.getLast().getId());
            assertTrue(changeSets.getFirst().getChanges().size() >= 1);
        }
    }
}
