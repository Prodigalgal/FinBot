package io.omnnu.finbot.api.controller;

import io.omnnu.finbot.application.identity.exception.AuthenticationRejectedException;
import io.omnnu.finbot.application.identity.exception.AdminApiTokenConflictException;
import io.omnnu.finbot.application.configuration.exception.ConfigurationConflictException;
import io.omnnu.finbot.application.catalog.exception.CatalogConflictException;
import io.omnnu.finbot.application.catalog.exception.CatalogNotFoundException;
import io.omnnu.finbot.application.exchange.exception.ExchangeAccountNotFoundException;
import io.omnnu.finbot.application.ingestion.exception.IngestionConflictException;
import io.omnnu.finbot.application.network.exception.NetworkDiagnosticConflictException;
import io.omnnu.finbot.application.network.exception.ProxyGatewayUnavailableException;
import org.springframework.dao.DataIntegrityViolationException;
import io.omnnu.finbot.application.operations.exception.TaskNotFoundException;
import io.omnnu.finbot.application.workflow.exception.WorkflowManagementConflictException;
import io.omnnu.finbot.application.workflow.exception.WorkflowIdempotencyConflictException;
import io.omnnu.finbot.application.trading.exception.TradeAutomationConfigurationConflictException;
import io.omnnu.finbot.application.operations.exception.ScheduleConfigurationConflictException;
import io.omnnu.finbot.application.workflow.exception.WorkflowNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler({
        CatalogConflictException.class,
        IngestionConflictException.class,
        NetworkDiagnosticConflictException.class,
        WorkflowManagementConflictException.class,
        WorkflowIdempotencyConflictException.class,
        TradeAutomationConfigurationConflictException.class,
        ScheduleConfigurationConflictException.class,
        AdminApiTokenConflictException.class,
        DataIntegrityViolationException.class
    })
    ProblemDetail handleCatalogConflict(RuntimeException exception) {
        var detail = exception instanceof DataIntegrityViolationException
                ? "请求与现有唯一约束或关联数据冲突"
                : exception.getMessage();
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle("Resource conflict");
        problem.setProperty("code", "RESOURCE_CONFLICT");
        return problem;
    }

    @ExceptionHandler({
        CatalogNotFoundException.class,
        ExchangeAccountNotFoundException.class,
        TaskNotFoundException.class,
        WorkflowNotFoundException.class
    })
    ProblemDetail handleResourceNotFound(RuntimeException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Resource not found");
        problem.setProperty("code", "RESOURCE_NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(ConfigurationConflictException.class)
    ProblemDetail handleConfigurationConflict(ConfigurationConflictException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Configuration conflict");
        problem.setProperty("code", "CONFIGURATION_CONFLICT");
        return problem;
    }

    @ExceptionHandler(AuthenticationRejectedException.class)
    ProblemDetail handleAuthenticationRejected(AuthenticationRejectedException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setTitle("Authentication rejected");
        problem.setProperty("code", "AUTHENTICATION_REJECTED");
        return problem;
    }

    @ExceptionHandler(ProxyGatewayUnavailableException.class)
    ProblemDetail handleProxyGatewayUnavailable(ProxyGatewayUnavailableException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                "代理网关当前无可用出口，请查看网络诊断中的节点健康状态");
        problem.setTitle("Proxy gateway unavailable");
        problem.setProperty("code", "PROXY_GATEWAY_UNAVAILABLE");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidArgument(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }
}
