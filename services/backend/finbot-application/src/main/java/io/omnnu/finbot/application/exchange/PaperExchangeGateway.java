package io.omnnu.finbot.application.exchange;

import java.util.Optional;

public interface PaperExchangeGateway {
    Optional<ExchangeSubmissionResult> findByClientOrderId(ExecutableOrder order);

    ExchangeSubmissionResult submit(ExecutableOrder order);
}
