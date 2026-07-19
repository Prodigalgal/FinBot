package io.omnnu.finbot.application.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrawlerAccessChallengeDetectorTest {
    private final CrawlerAccessChallengeDetector detector = new CrawlerAccessChallengeDetector();

    @Test
    void detectsCloudflareTurnstile() {
        var html = """
                <html><body>Just a moment...
                <div class="cf-turnstile" data-sitekey="1x00000000000000000000AA"></div>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        var challenge = detector.detect(
                URI.create("https://example.com/"),
                403,
                "text/html",
                html,
                Map.of("cf-ray", "abc", "server", "cloudflare")).orElseThrow();
        assertEquals(CrawlerAccessChallenge.Kind.CLOUDFLARE_TURNSTILE, challenge.kind());
        assertEquals("1x00000000000000000000AA", challenge.siteKey());
        assertEquals("CHALLENGE_CLOUDFLARE_TURNSTILE", challenge.errorCodeSuffix());
        assertTrue(challenge.safeMessage("HTML").contains("Turnstile"));
    }

    @Test
    void detectsCloudflareManagedWithoutTurnstileWidget() {
        var challenge = detector.detect(
                URI.create("https://news.example/"),
                503,
                "text/html",
                "<html>Just a moment...</html>".getBytes(StandardCharsets.UTF_8),
                Map.of("cf-ray", "xyz", "server", "cloudflare")).orElseThrow();
        assertEquals(CrawlerAccessChallenge.Kind.CLOUDFLARE_MANAGED, challenge.kind());
        assertEquals("CHALLENGE_CLOUDFLARE_MANAGED", challenge.errorCodeSuffix());
    }

    @Test
    void detectsRecaptchaSiteKey() {
        var html = "<div class=\"g-recaptcha\" data-sitekey=\"6LeTestKey\"></div>"
                .getBytes(StandardCharsets.UTF_8);
        var challenge = detector.detect(
                URI.create("https://example.com/login"),
                403,
                "text/html",
                html,
                Map.of()).orElseThrow();
        assertEquals(CrawlerAccessChallenge.Kind.RECAPTCHA_V2, challenge.kind());
        assertTrue(challenge.siteKeyOptional().isPresent());
    }

    @Test
    void detectsAnubisProofOfWork() {
        var challenge = detector.detect(
                URI.create("https://searx.example/search"),
                403,
                "text/html",
                "<html>Anubis challenge proof-of-work</html>".getBytes(StandardCharsets.UTF_8),
                Map.of()).orElseThrow();
        assertEquals(CrawlerAccessChallenge.Kind.ANUBIS, challenge.kind());
        assertEquals("CHALLENGE_ANUBIS", challenge.errorCodeSuffix());
    }

    @Test
    void detectsDataDomeAndPerimeterX() {
        var datadome = detector.detect(
                URI.create("https://shop.example/"),
                403,
                "text/html",
                "geo.captcha-delivery.com".getBytes(StandardCharsets.UTF_8),
                Map.of("set-cookie", "datadome=abc")).orElseThrow();
        assertEquals(CrawlerAccessChallenge.Kind.DATADOME, datadome.kind());

        var perimeter = detector.detect(
                URI.create("https://shop.example/"),
                403,
                "text/html",
                "px-captcha human challenge".getBytes(StandardCharsets.UTF_8),
                Map.of()).orElseThrow();
        assertEquals(CrawlerAccessChallenge.Kind.PERIMETERX, perimeter.kind());
    }

    @Test
    void classifiesPlainForbiddenAsUnknownBlock() {
        var challenge = detector.detect(
                URI.create("https://api.example/item"),
                403,
                "text/plain",
                "blocked".getBytes(StandardCharsets.UTF_8),
                Map.of()).orElseThrow();
        assertEquals(CrawlerAccessChallenge.Kind.UNKNOWN_BLOCK, challenge.kind());
        assertEquals("ACCESS_BLOCKED", challenge.errorCodeSuffix());
    }

    @Test
    void classifiesHttp429AsRateLimited() {
        var challenge = detector.detect(
                URI.create("https://api.example/"),
                429,
                "application/json",
                "{\"error\":\"too many requests\"}".getBytes(StandardCharsets.UTF_8),
                Map.of()).orElseThrow();
        assertEquals(CrawlerAccessChallenge.Kind.RATE_LIMITED, challenge.kind());
        assertEquals("RATE_LIMITED", challenge.errorCodeSuffix());
    }

    @Test
    void leavesOrdinaryHttp404Unclassified() {
        assertTrue(detector.detect(
                URI.create("https://api.example/missing"),
                404,
                "text/plain",
                "not found".getBytes(StandardCharsets.UTF_8),
                Map.of()).isEmpty());
    }
}
