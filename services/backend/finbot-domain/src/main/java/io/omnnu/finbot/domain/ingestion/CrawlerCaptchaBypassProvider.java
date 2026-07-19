package io.omnnu.finbot.domain.ingestion;

import java.util.Locale;

/** External or channel-backed CAPTCHA / WAF challenge solvers. */
public enum CrawlerCaptchaBypassProvider {
    NONE,
    CAPSOLVER,
    TWOCAPTCHA,
    FIRECRAWL_BROWSER,
    /** First-party Playwright browser worker (C2). */
    BROWSER_WORKER;

    public static CrawlerCaptchaBypassProvider from(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return CrawlerCaptchaBypassProvider.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    public boolean active() {
        return this != NONE;
    }
}
