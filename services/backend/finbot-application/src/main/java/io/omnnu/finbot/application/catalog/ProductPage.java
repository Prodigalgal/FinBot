package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.ProductId;
import java.util.List;

public record ProductPage(List<ProductSummary> products, ProductId nextCursor, long totalCount) {
    public ProductPage {
        products = List.copyOf(products);
        if (totalCount < 0) {
            throw new IllegalArgumentException("totalCount must not be negative");
        }
    }
}
