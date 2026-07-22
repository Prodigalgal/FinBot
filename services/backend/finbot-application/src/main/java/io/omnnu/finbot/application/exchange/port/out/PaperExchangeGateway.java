package io.omnnu.finbot.application.exchange.port.out;

import io.omnnu.finbot.application.exchange.dto.ExchangeSubmissionResult;
import io.omnnu.finbot.application.exchange.dto.ExecutableOrder;

import java.util.Optional;

public interface PaperExchangeGateway {
    Optional<ExchangeSubmissionResult> findByClientOrderId(ExecutableOrder order);

    ExchangeSubmissionResult submit(ExecutableOrder order);
}
