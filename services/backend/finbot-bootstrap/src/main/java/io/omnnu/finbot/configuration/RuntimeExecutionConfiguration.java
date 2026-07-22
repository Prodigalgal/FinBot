package io.omnnu.finbot.configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuntimeExecutionConfiguration {
    @Bean(name = "workflowVirtualThreadExecutor", destroyMethod = "close")
    ExecutorService workflowVirtualThreadExecutor() {
        var factory = Thread.ofVirtual().name("finbot-workflow-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    @Bean(name = "sseHeartbeatScheduler", destroyMethod = "close")
    ScheduledExecutorService sseHeartbeatScheduler() {
        var factory = Thread.ofPlatform().daemon(true).name("finbot-sse-heartbeat").factory();
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    @Bean(name = "workerLeaseScheduler", destroyMethod = "close")
    ScheduledExecutorService workerLeaseScheduler() {
        var factory = Thread.ofPlatform().daemon(true).name("finbot-worker-lease").factory();
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    @Bean(name = "workerControlScheduler", destroyMethod = "close")
    ScheduledExecutorService workerControlScheduler() {
        var factory = Thread.ofPlatform().daemon(true).name("finbot-worker-control-", 0).factory();
        return Executors.newScheduledThreadPool(2, factory);
    }

    @Bean(name = "quantHttpClient")
    HttpClient quantHttpClient(@Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return httpClient(executor, Duration.ofSeconds(10), HttpClient.Version.HTTP_1_1);
    }

    @Bean(name = "aiHttpClient")
    HttpClient aiHttpClient(@Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return httpClient(executor, Duration.ofSeconds(10), HttpClient.Version.HTTP_2);
    }

    @Bean(name = "searxngHttpClient")
    HttpClient searxngHttpClient(@Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return httpClient(executor, Duration.ofSeconds(5), HttpClient.Version.HTTP_1_1);
    }

    private static HttpClient httpClient(
            Executor executor,
            Duration connectTimeout,
            HttpClient.Version version) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(version)
                .build();
    }
}
