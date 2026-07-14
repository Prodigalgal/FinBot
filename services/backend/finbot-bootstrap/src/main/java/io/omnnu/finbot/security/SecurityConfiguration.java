package io.omnnu.finbot.security;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private final ObjectMapper objectMapper;

    public SecurityConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Bean
    SecurityFilterChain finBotSecurityFilterChain(
            HttpSecurity http,
            SessionAuthenticationFilter sessionAuthenticationFilter) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("XSRF-TOKEN");
        csrfRepository.setHeaderName("X-XSRF-TOKEN");

        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .ignoringRequestMatchers(
                                "/api/v2/auth/challenge",
                                "/api/v2/auth/login",
                                "/internal/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/health", "/health/**", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v2/auth/status", "/api/v2/auth/challenge").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v2/auth/login").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeProblem(
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "Authentication required",
                                "请先完成管理员登录"))
                        .accessDeniedHandler((request, response, exception) -> writeProblem(
                                response,
                                HttpStatus.FORBIDDEN,
                                "Access denied",
                                "请求缺少有效的 CSRF 凭据或权限")))
                .addFilterBefore(sessionAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    private void writeProblem(
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        response.setStatus(status.value());
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
