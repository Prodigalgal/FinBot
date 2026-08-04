package io.omnnu.finbot.infrastructure.configuration.client;

import io.omnnu.finbot.application.configuration.dto.ModelCatalogWarning;
import java.util.ArrayList;
import java.util.List;

final class ModelCatalogWarningCollector {
    private static final int MAXIMUM_WARNINGS = 200;

    private final List<ModelCatalogWarning> values = new ArrayList<>();
    private final List<ModelCatalogWarning> criticalValues = new ArrayList<>();
    private int discarded;
    private List<ModelCatalogWarning> result;

    void add(String code, String modelName, String message) {
        ensureOpen();
        if (values.size() < MAXIMUM_WARNINGS - 1) {
            values.add(new ModelCatalogWarning(code, modelName, message));
        } else {
            discarded++;
        }
    }

    void addCritical(String code, String modelName, String message) {
        ensureOpen();
        criticalValues.add(new ModelCatalogWarning(code, modelName, message));
    }

    List<ModelCatalogWarning> result() {
        if (result != null) {
            return result;
        }
        var combined = new ArrayList<>(values);
        while (combined.size() + criticalValues.size() + (discarded > 0 ? 1 : 0) > MAXIMUM_WARNINGS) {
            combined.removeLast();
            discarded++;
        }
        combined.addAll(criticalValues);
        if (discarded > 0) {
            combined.add(new ModelCatalogWarning(
                    "MODEL_CATALOG_WARNINGS_TRUNCATED",
                    null,
                    "另有 " + discarded + " 条模型目录告警未返回"));
        }
        result = List.copyOf(combined);
        return result;
    }

    private void ensureOpen() {
        if (result != null) {
            throw new IllegalStateException("模型目录告警已经完成收集");
        }
    }
}
