package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import java.util.Optional;

/** Solves detected CAPTCHA / WAF challenges for camouflage crawls. */
public interface CrawlerAccessChallengeBypassGateway {
    Optional<CrawlerAccessChallengeBypass> solve(
            CrawlerAccessChallenge challenge,
            CrawlerCaptchaBypassProvider provider);
}
