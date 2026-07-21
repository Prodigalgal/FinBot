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
            assertEquals(58, changeSets.size());
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
            assertEquals("021-exchange-product-controls", changeSets.get(22).getId());
            assertEquals("022-execution-ai-output-contract", changeSets.get(23).getId());
            assertEquals("023-estimated-trading", changeSets.get(24).getId());
            assertEquals("024-feature-parity-control-plane", changeSets.get(25).getId());
            assertEquals("025-workflow-owned-execution-review", changeSets.get(26).getId());
            assertEquals("026-product-catalog-and-execution-isolation", changeSets.get(27).getId());
            assertEquals("027-research-forecast", changeSets.get(28).getId());
            assertEquals("028-single-character-assets", changeSets.get(29).getId());
            assertEquals("029-workflow-fallback-attempts", changeSets.get(30).getId());
            assertEquals("030-segmented-environment-research", changeSets.get(31).getId());
            assertEquals("031-multi-agent-evidence-consensus", changeSets.get(32).getId());
            assertEquals("032-runtime-configuration-control-plane", changeSets.get(33).getId());
            assertEquals("033-information-source-management", changeSets.get(34).getId());
            assertEquals("041-firecrawl-default-disabled", changeSets.get(42).getId());
            assertEquals("042-default-source-catalog-manifest", changeSets.get(43).getId());
            assertEquals("043-source-fetch-redirect-count", changeSets.get(44).getId());
            assertEquals("044-free-structured-source-catalog-v2", changeSets.get(45).getId());
            assertEquals("045-ai-web-search-source", changeSets.get(46).getId());
            assertEquals("046-multi-domain-source-catalog-v3", changeSets.get(47).getId());
            assertEquals("047-searxng-engine-shortcut-routing", changeSets.get(48).getId());
            assertEquals("048-searxng-resilient-engine-routing", changeSets.get(49).getId());
            assertEquals("049-public-searxng-instance-pool", changeSets.get(50).getId());
            assertEquals("050-crawler-header-profiles", changeSets.get(51).getId());
            assertEquals("051-proxy-gateway-engine", changeSets.get(52).getId());
            assertEquals("052-crawler-header-camouflage", changeSets.get(53).getId());
            assertEquals("056-provider-runtime-capacity", changeSets.getLast().getId());
            assertTrue(changeSets.getFirst().getChanges().size() >= 1);
        }
    }
}
