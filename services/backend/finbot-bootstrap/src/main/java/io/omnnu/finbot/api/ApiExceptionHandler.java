package io.omnnu.finbot.api;

import io.omnnu.finbot.application.identity.AuthenticationRejectedException;
import io.omnnu.finbot.application.configuration.ConfigurationConflictException;
import io.omnnu.finbot.application.catalog.CatalogConflictException;
import io.omnnu.finbot.application.catalog.CatalogNotFoundException;
import io.omnnu.finbot.application.exchange.ExchangeAccountNotFoundException;
import io.omnnu.finbot.application.ingestion.IngestionConflictException;
import io.omnnu.finbot.application.network.NetworkDiagnosticConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import io.omnnu.finbot.application.operations.TaskNotFoundException;
import io.omnnu.finbot.application.workflow.WorkflowManagementConflictException;
import io.omnnu.finbot.application.workflow.WorkflowIdempotencyConflictException;
import io.omnnu.finbot.application.trading.TradeAutomationConfigurationConflictException;
import io.omnnu.finbot.application.operations.ScheduleConfigurationConflictException;
import io.omnnu.finbot.application.workflow.WorkflowNotFoundException;
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

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidArgument(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }
}
