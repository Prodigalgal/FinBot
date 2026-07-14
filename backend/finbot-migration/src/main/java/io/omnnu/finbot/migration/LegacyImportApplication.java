package io.omnnu.finbot.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public final class LegacyImportApplication {
    private LegacyImportApplication() {}

    public static void main(String[] arguments) throws Exception {
        var objectMapper = new ObjectMapper();
        var configuration = ImportConfiguration.from(arguments, System.getenv());
        var result = new LegacyImporter(configuration, objectMapper).execute();
        System.out.println(objectMapper.writeValueAsString(Map.of(
                "importId", result.importId(),
                "status", result.status(),
                "sourceSha256", result.sourceSha256(),
                "tableCount", result.tableCount(),
                "sourceRowCount", result.sourceRowCount(),
                "archivedRowCount", result.archivedRowCount(),
                "transformedRowCount", result.transformedRowCount(),
                "alreadyCompleted", result.alreadyCompleted())));
    }
}
