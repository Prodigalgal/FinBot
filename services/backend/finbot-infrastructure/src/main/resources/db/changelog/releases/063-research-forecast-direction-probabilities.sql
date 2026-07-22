--liquibase formatted sql

--changeset codex:063-research-forecast-direction-probabilities splitStatements:true endDelimiter:;
ALTER TABLE research_forecast
    ADD COLUMN probability_up NUMERIC(7, 6),
    ADD COLUMN probability_sideways NUMERIC(7, 6),
    ADD COLUMN probability_down NUMERIC(7, 6),
    ADD CONSTRAINT ck_research_forecast_direction_probabilities CHECK (
        (probability_up IS NULL AND probability_sideways IS NULL AND probability_down IS NULL)
        OR (
            probability_up BETWEEN 0 AND 1
            AND probability_sideways BETWEEN 0 AND 1
            AND probability_down BETWEEN 0 AND 1
            AND abs(probability_up + probability_sideways + probability_down - 1) <= 0.0001
        )
    );

--rollback ALTER TABLE research_forecast DROP CONSTRAINT IF EXISTS ck_research_forecast_direction_probabilities;
--rollback ALTER TABLE research_forecast DROP COLUMN IF EXISTS probability_down;
--rollback ALTER TABLE research_forecast DROP COLUMN IF EXISTS probability_sideways;
--rollback ALTER TABLE research_forecast DROP COLUMN IF EXISTS probability_up;
