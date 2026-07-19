package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallenge;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeBypass;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeBypassGateway;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * CapSolver / 2Captcha / Firecrawl-backed challenge solvers for C3 bypass.
 *
 * <p>API keys resolve from {@link RuntimeSecretStore} with environment fallbacks
 * {@code FINBOT_CAPSOLVER_API_KEY} and {@code FINBOT_TWOCAPTCHA_API_KEY}.
 */
@Component
public final class CompositeCrawlerAccessChallengeBypassGateway
        implements CrawlerAccessChallengeBypassGateway {
    private static final Duration SOLVER_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAXIMUM_POLL_ATTEMPTS = 24;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private final RuntimeSecretStore runtimeSecrets;
    private final RoutedHttpClientFactory httpClients;
    private final ObjectMapper objectMapper;

    public CompositeCrawlerAccessChallengeBypassGateway(
            RuntimeSecretStore runtimeSecrets,
            RoutedHttpClientFactory httpClients,
            ObjectMapper objectMapper) {
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Optional<CrawlerAccessChallengeBypass> solve(
            CrawlerAccessChallenge challenge,
            CrawlerCaptchaBypassProvider provider) {
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(provider, "provider");
        if (!provider.active()) {
            return Optional.empty();
        }
        return switch (provider) {
            case CAPSOLVER -> Optional.of(solveCapSolver(challenge));
            case TWOCAPTCHA -> Optional.of(solveTwoCaptcha(challenge));
            case FIRECRAWL_BROWSER -> Optional.of(solveFirecrawl(challenge));
            case BROWSER_WORKER -> Optional.of(solveBrowserWorker(challenge));
            case NONE -> Optional.empty();
        };
    }

    private CrawlerAccessChallengeBypass solveBrowserWorker(CrawlerAccessChallenge challenge) {
        var baseUrl = runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        "browser_worker",
                        "endpoint",
                        "FINBOT_BROWSER_WORKER_URL")
                .filter(value -> !value.isBlank())
                .orElse("http://finbot-browser-worker:8082");
        var token = runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        "browser_worker",
                        "api_key",
                        "FINBOT_BROWSER_WORKER_TOKEN")
                .or(() -> runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        "browser_worker",
                        "api_key",
                        "FINBOT_JAVA_SERVICE_TOKEN"))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> bypassFailure(
                        "BROWSER_WORKER",
                        "browser worker token is not configured"));
        var body = objectMapper.createObjectNode()
                .put("url", challenge.pageUrl().toString())
                .put("wait_ms", 6_000)
                .put("timeout_ms", 45_000)
                .put("wait_until", "domcontentloaded");
        try {
            var requestBuilder = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/internal/v1/challenge/solve"))
                    .timeout(SOLVER_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8));
            // In-cluster worker is reached without outbound proxy route.
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            var response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw bypassFailure("BROWSER_WORKER", "HTTP " + response.statusCode());
            }
            var payload = objectMapper.readTree(response.body());
            var cookies = new LinkedHashMap<String, String>();
            var cookieNode = payload.path("cookies");
            if (cookieNode.isObject()) {
                cookieNode.properties().forEach(entry ->
                        cookies.put(entry.getKey(), entry.getValue().asText("")));
            }
            if (cookies.isEmpty()) {
                throw bypassFailure("BROWSER_WORKER", "browser worker returned no cookies");
            }
            var headers = new LinkedHashMap<String, String>();
            var userAgent = payload.path("user_agent").asText("");
            if (!userAgent.isBlank()) {
                headers.put("User-Agent", userAgent);
            }
            return new CrawlerAccessChallengeBypass(
                    headers,
                    cookies,
                    "browser-worker-" + payload.path("detail").asText("playwright"));
        } catch (SourceCollectionException exception) {
            throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw bypassFailure("BROWSER_WORKER", exception.getClass().getSimpleName());
        }
    }

    private CrawlerAccessChallengeBypass solveCapSolver(CrawlerAccessChallenge challenge) {
        var apiKey = requireSecret(
                "capsolver",
                "api_key",
                "FINBOT_CAPSOLVER_API_KEY",
                "CapSolver");
        var task = capSolverTask(challenge);
        var createBody = objectMapper.createObjectNode();
        createBody.put("clientKey", apiKey);
        createBody.set("task", task);
        var create = postJson(
                URI.create("https://api.capsolver.com/createTask"),
                createBody,
                "CAPSOLVER");
        if (!"ready".equalsIgnoreCase(create.path("status").asText())
                && create.path("taskId").asText("").isBlank()) {
            throw bypassFailure("CAPSOLVER", "createTask failed: " + create.path("errorDescription").asText("unknown"));
        }
        if ("ready".equalsIgnoreCase(create.path("status").asText())) {
            return fromCapSolverSolution(create.path("solution"), "capsolver-ready");
        }
        var taskId = create.path("taskId").asText();
        for (var attempt = 0; attempt < MAXIMUM_POLL_ATTEMPTS; attempt++) {
            sleep(POLL_INTERVAL);
            var pollBody = objectMapper.createObjectNode()
                    .put("clientKey", apiKey)
                    .put("taskId", taskId);
            var poll = postJson(
                    URI.create("https://api.capsolver.com/getTaskResult"),
                    pollBody,
                    "CAPSOLVER");
            var status = poll.path("status").asText("");
            if ("ready".equalsIgnoreCase(status)) {
                return fromCapSolverSolution(poll.path("solution"), "capsolver-" + taskId);
            }
            if ("failed".equalsIgnoreCase(status)
                    || poll.path("errorId").asInt(0) != 0) {
                throw bypassFailure("CAPSOLVER", poll.path("errorDescription").asText("solver failed"));
            }
        }
        throw bypassFailure("CAPSOLVER", "solver timed out waiting for task " + taskId);
    }

    private ObjectNode capSolverTask(CrawlerAccessChallenge challenge) {
        var task = objectMapper.createObjectNode();
        task.put("websiteURL", challenge.pageUrl().toString());
        switch (challenge.kind()) {
            case CLOUDFLARE_TURNSTILE, CLOUDFLARE_MANAGED -> {
                task.put("type", "AntiTurnstileTaskProxyLess");
                challenge.siteKeyOptional().ifPresent(key -> task.put("websiteKey", key));
            }
            case RECAPTCHA_V2 -> {
                task.put("type", "ReCaptchaV2TaskProxyLess");
                task.put("websiteKey", challenge.siteKeyOptional().orElseThrow(() ->
                        bypassFailure("CAPSOLVER", "reCAPTCHA site key missing")));
            }
            case HCAPTCHA -> {
                task.put("type", "HCaptchaTaskProxyLess");
                task.put("websiteKey", challenge.siteKeyOptional().orElseThrow(() ->
                        bypassFailure("CAPSOLVER", "hCaptcha site key missing")));
            }
            case ANUBIS, DATADOME, PERIMETERX, GENERIC_JS_CHALLENGE, RATE_LIMITED, UNKNOWN_BLOCK -> {
                task.put("type", "AntiCloudflareTask");
                task.put("html", challenge.challengeHtmlSnippet());
            }
        }
        return task;
    }

    private CrawlerAccessChallengeBypass fromCapSolverSolution(JsonNode solution, String detail) {
        var headers = new LinkedHashMap<String, String>();
        var cookies = new LinkedHashMap<String, String>();
        var token = text(solution, "token", "gRecaptchaResponse", "cf-turnstile-response");
        if (!token.isBlank()) {
            headers.put("cf-turnstile-response", token);
            headers.put("g-recaptcha-response", token);
            headers.put("h-captcha-response", token);
        }
        var userAgent = text(solution, "userAgent");
        if (!userAgent.isBlank()) {
            headers.put("User-Agent", userAgent);
        }
        var cookieNode = solution.path("cookies");
        if (cookieNode.isObject()) {
            cookieNode.properties().forEach(entry ->
                    cookies.put(entry.getKey(), entry.getValue().asText("")));
        }
        var cookieHeader = text(solution, "cookie");
        if (!cookieHeader.isBlank()) {
            for (var part : cookieHeader.split(";")) {
                var separator = part.indexOf('=');
                if (separator > 0) {
                    cookies.put(part.substring(0, separator).strip(), part.substring(separator + 1).strip());
                }
            }
        }
        return new CrawlerAccessChallengeBypass(headers, cookies, detail);
    }

    private CrawlerAccessChallengeBypass solveTwoCaptcha(CrawlerAccessChallenge challenge) {
        var apiKey = requireSecret(
                "twocaptcha",
                "api_key",
                "FINBOT_TWOCAPTCHA_API_KEY",
                "2Captcha");
        var method = twoCaptchaMethod(challenge);
        var createUri = URI.create("https://2captcha.com/in.php?key=" + url(apiKey)
                + "&method=" + url(method)
                + "&pageurl=" + url(challenge.pageUrl().toString())
                + siteKeyQuery(challenge)
                + "&json=1");
        var create = getJson(createUri, "TWOCAPTCHA");
        if (create.path("status").asInt(0) != 1) {
            throw bypassFailure("TWOCAPTCHA", create.path("request").asText("create failed"));
        }
        var requestId = create.path("request").asText();
        for (var attempt = 0; attempt < MAXIMUM_POLL_ATTEMPTS; attempt++) {
            sleep(POLL_INTERVAL);
            var pollUri = URI.create("https://2captcha.com/res.php?key=" + url(apiKey)
                    + "&action=get&id=" + url(requestId) + "&json=1");
            var poll = getJson(pollUri, "TWOCAPTCHA");
            if (poll.path("status").asInt(0) == 1) {
                var token = poll.path("request").asText();
                return new CrawlerAccessChallengeBypass(
                        Map.of(
                                "cf-turnstile-response", token,
                                "g-recaptcha-response", token,
                                "h-captcha-response", token),
                        Map.of(),
                        "twocaptcha-" + requestId);
            }
            var request = poll.path("request").asText("");
            if (!"CAPCHA_NOT_READY".equalsIgnoreCase(request) && !"CAPTCHA_NOT_READY".equalsIgnoreCase(request)) {
                throw bypassFailure("TWOCAPTCHA", request.isBlank() ? "solver failed" : request);
            }
        }
        throw bypassFailure("TWOCAPTCHA", "solver timed out waiting for " + requestId);
    }

    private static String twoCaptchaMethod(CrawlerAccessChallenge challenge) {
        return switch (challenge.kind()) {
            case CLOUDFLARE_TURNSTILE, CLOUDFLARE_MANAGED -> "turnstile";
            case RECAPTCHA_V2 -> "userrecaptcha";
            case HCAPTCHA -> "hcaptcha";
            case ANUBIS, DATADOME, PERIMETERX, GENERIC_JS_CHALLENGE, RATE_LIMITED, UNKNOWN_BLOCK ->
                    "turnstile";
        };
    }

    private static String siteKeyQuery(CrawlerAccessChallenge challenge) {
        return challenge.siteKeyOptional()
                .map(key -> "&googlekey=" + url(key) + "&sitekey=" + url(key))
                .orElse("");
    }

    private CrawlerAccessChallengeBypass solveFirecrawl(CrawlerAccessChallenge challenge) {
        var endpoint = runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        "firecrawl_bypass",
                        "endpoint",
                        "FINBOT_FIRECRAWL_BYPASS_URL")
                .or(() -> runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        "firecrawl",
                        "endpoint",
                        "FINBOT_FIRECRAWL_BASE_URL"))
                .orElseThrow(() -> bypassFailure(
                        "FIRECRAWL_BROWSER",
                        "Firecrawl bypass endpoint is not configured"));
        var apiKey = runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        "firecrawl",
                        "api_key",
                        "FINBOT_FIRECRAWL_API_KEY")
                .orElse("");
        var body = objectMapper.createObjectNode()
                .put("url", challenge.pageUrl().toString())
                .put("onlyMainContent", false)
                .put("waitFor", 8_000)
                .put("timeout", 90_000);
        body.putArray("formats").add("html").add("rawHtml");
        var requestBuilder = HttpRequest.newBuilder(URI.create(trimSlash(endpoint) + "/v1/scrape"))
                .timeout(SOLVER_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            var route = httpClients.route(OutboundRoute.FIRECRAWL);
            var response = httpClients.clientForRequest(route).send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw bypassFailure("FIRECRAWL_BROWSER", "HTTP " + response.statusCode());
            }
            var payload = objectMapper.readTree(response.body());
            var cookies = new LinkedHashMap<String, String>();
            var cookieNode = payload.path("data").path("cookies");
            if (cookieNode.isArray()) {
                for (var cookie : cookieNode) {
                    var name = cookie.path("name").asText("");
                    var value = cookie.path("value").asText("");
                    if (!name.isBlank()) {
                        cookies.put(name, value);
                    }
                }
            }
            if (cookies.isEmpty()) {
                throw bypassFailure("FIRECRAWL_BROWSER", "browser scrape returned no challenge cookies");
            }
            return new CrawlerAccessChallengeBypass(Map.of(), cookies, "firecrawl-browser");
        } catch (SourceCollectionException exception) {
            throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw bypassFailure("FIRECRAWL_BROWSER", exception.getClass().getSimpleName());
        }
    }

    private String requireSecret(String targetId, String secretName, String env, String label) {
        return runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        targetId,
                        secretName,
                        env)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> bypassFailure(label.toUpperCase(), label + " API key is not configured"));
    }

    private JsonNode postJson(URI target, ObjectNode body, String provider) {
        try {
            var request = HttpRequest.newBuilder(target)
                    .timeout(SOLVER_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8))
                    .build();
            var route = httpClients.route(OutboundRoute.PUBLIC_DATA);
            var response = httpClients.clientForRequest(route).send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw bypassFailure(provider, "HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (SourceCollectionException exception) {
            throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw bypassFailure(provider, exception.getClass().getSimpleName());
        }
    }

    private JsonNode getJson(URI target, String provider) {
        try {
            var request = HttpRequest.newBuilder(target)
                    .timeout(SOLVER_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            var route = httpClients.route(OutboundRoute.PUBLIC_DATA);
            var response = httpClients.clientForRequest(route).send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw bypassFailure(provider, "HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (SourceCollectionException exception) {
            throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw bypassFailure(provider, exception.getClass().getSimpleName());
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode solver payload", exception);
        }
    }

    private static String text(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String url(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw bypassFailure("BYPASS", "interrupted");
        }
    }

    private static SourceCollectionException bypassFailure(String provider, String detail) {
        return new SourceCollectionException(
                "CRAWLER_CAPTCHA_BYPASS_FAILED",
                provider + " bypass failed: " + detail,
                true);
    }
}
