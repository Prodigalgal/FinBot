package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.ProductId;
import java.util.List;

public record ProductPage(List<ProductSummary> products, ProductId nextCursor) {
    public ProductPage {
        products = List.copyOf(products);
    }
}
