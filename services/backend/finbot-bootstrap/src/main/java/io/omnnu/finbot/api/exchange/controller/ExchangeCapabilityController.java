package io.omnnu.finbot.api.exchange.controller;

import io.omnnu.finbot.application.exchange.dto.ExchangeCapability;
import io.omnnu.finbot.application.exchange.port.out.ExchangeCapabilityQuery;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/exchanges/capabilities")
public final class ExchangeCapabilityController {
    private final ExchangeCapabilityQuery capabilities;

    public ExchangeCapabilityController(ExchangeCapabilityQuery capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    @GetMapping
    public List<ExchangeCapability> list() {
        return capabilities.list();
    }
}
