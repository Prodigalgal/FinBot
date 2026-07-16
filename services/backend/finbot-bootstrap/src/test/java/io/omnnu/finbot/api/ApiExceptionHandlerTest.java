package io.omnnu.finbot.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.omnnu.finbot.application.network.ProxyGatewayUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {
    @Test
    void exposesProxyGatewayFailureAsBadGatewayProblemDetail() {
        var problem = new ApiExceptionHandler().handleProxyGatewayUnavailable(
                new ProxyGatewayUnavailableException(new IllegalStateException("sensitive upstream")));

        assertEquals(HttpStatus.BAD_GATEWAY.value(), problem.getStatus());
        assertEquals("PROXY_GATEWAY_UNAVAILABLE", problem.getProperties().get("code"));
        assertEquals(
                "代理网关当前无可用出口，请查看网络诊断中的节点健康状态",
                problem.getDetail());
    }
}
