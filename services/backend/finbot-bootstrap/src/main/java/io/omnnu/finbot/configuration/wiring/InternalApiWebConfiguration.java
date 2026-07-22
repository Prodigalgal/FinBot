package io.omnnu.finbot.configuration.wiring;

import io.omnnu.finbot.security.filter.InternalServiceAuthenticationInterceptor;
import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class InternalApiWebConfiguration implements WebMvcConfigurer {
    private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

    public InternalApiWebConfiguration(
            InternalServiceAuthenticationInterceptor authenticationInterceptor) {
        this.authenticationInterceptor = Objects.requireNonNull(
                authenticationInterceptor,
                "authenticationInterceptor");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor).addPathPatterns("/internal/**");
    }
}
