package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.application.ingestion.dto.CrawlerAccessChallenge;
import io.omnnu.finbot.application.ingestion.dto.CrawlerAccessChallengeBypass;

import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import java.util.Optional;

/** Solves detected CAPTCHA / WAF challenges for camouflage crawls. */
public interface CrawlerAccessChallengeBypassGateway {
    Optional<CrawlerAccessChallengeBypass> solve(
            CrawlerAccessChallenge challenge,
            CrawlerCaptchaBypassProvider provider);
}
