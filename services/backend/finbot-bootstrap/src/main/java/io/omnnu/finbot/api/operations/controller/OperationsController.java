package io.omnnu.finbot.api.operations.controller;

import io.omnnu.finbot.api.operations.dto.UpdateScheduleRequest;

import io.omnnu.finbot.application.operations.dto.OperationsOverview;
import io.omnnu.finbot.application.operations.port.out.OperationsRepository;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/operations")
public final class OperationsController {
    private final OperationsRepository repository;
    private final Clock clock;

    public OperationsController(OperationsRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public OperationsOverview overview() {
        return repository.overview(clock.instant());
    }

    @PutMapping("/schedules/{scheduleId}")
    public OperationsOverview.Schedule updateSchedule(
            @PathVariable String scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request) {
        return repository.updateSchedule(
                scheduleId,
                request.enabled(),
                request.intervalSeconds(),
                request.expectedVersion(),
                clock.instant());
    }
}
