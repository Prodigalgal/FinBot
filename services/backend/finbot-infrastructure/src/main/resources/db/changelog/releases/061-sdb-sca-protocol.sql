--liquibase formatted sql

--changeset codex:061-sdb-sca-workflow-configuration splitStatements:true endDelimiter:;
ALTER TABLE debate_session RENAME COLUMN chair_node_id TO decision_node_id;

ALTER TABLE workflow_definition_version
    ADD COLUMN debate_protocol VARCHAR(24) NOT NULL DEFAULT 'LEGACY_CHAIR_V1',
    ADD COLUMN debate_minimum_participant_seats SMALLINT NOT NULL DEFAULT 2,
    ADD COLUMN debate_minimum_quorum_roles SMALLINT NOT NULL DEFAULT 2,
    ADD COLUMN debate_stage_timeout_seconds INTEGER NOT NULL DEFAULT 600,
    ADD COLUMN debate_critique_assignment VARCHAR(24) NOT NULL DEFAULT 'FULL_MATRIX';

ALTER TABLE workflow_definition_version
    ADD CONSTRAINT ck_workflow_debate_protocol CHECK (
        debate_protocol IN ('LEGACY_CHAIR_V1', 'SDB_SCA_V1')
    ),
    ADD CONSTRAINT ck_workflow_debate_participants CHECK (
        debate_minimum_participant_seats BETWEEN 2 AND 32
        AND debate_minimum_quorum_roles BETWEEN 2 AND debate_minimum_participant_seats
    ),
    ADD CONSTRAINT ck_workflow_debate_timeout CHECK (
        debate_stage_timeout_seconds BETWEEN 30 AND 7200
    ),
    ADD CONSTRAINT ck_workflow_debate_assignment CHECK (
        debate_critique_assignment IN ('FULL_MATRIX', 'BALANCED_INCOMPLETE')
    );

ALTER TABLE workflow_node_definition
    ADD COLUMN logical_role_key VARCHAR(80);

ALTER TABLE workflow_node_definition DROP CONSTRAINT ck_workflow_node_type;
ALTER TABLE workflow_node_definition ADD CONSTRAINT ck_workflow_node_type CHECK (node_type IN (
    'INPUT', 'ROUTER', 'DETERMINISTIC', 'COLLECTOR', 'CLEANER', 'AI_CLEANER',
    'COMPRESSOR', 'COMPRESSION_VALIDATOR', 'AGENT', 'GATE', 'QUANT', 'RISK',
    'SUBFLOW', 'HUMAN_REVIEW', 'AGGREGATOR', 'CHAIR', 'SOCIAL_CHOICE',
    'EXECUTION_REVIEW', 'OUTPUT'
));

ALTER TABLE workflow_node_definition DROP CONSTRAINT ck_workflow_node_output;
ALTER TABLE workflow_node_definition ADD CONSTRAINT ck_workflow_node_output CHECK (
    output_contract IS NULL OR output_contract IN (
        'TEXT', 'RESEARCH_FINDINGS', 'DEBATE_ARGUMENT', 'RISK_ASSESSMENT',
        'CHAIR_VERDICT', 'CONSENSUS_RESULT', 'TRADE_DECISIONS', 'EXECUTION_VERDICT'
    )
);

ALTER TABLE workflow_node_definition
    ADD CONSTRAINT ck_workflow_node_logical_role_key CHECK (
        logical_role_key IS NULL OR logical_role_key ~ '^[a-z][a-z0-9_-]{1,79}$'
    );

ALTER TABLE agent_message DROP CONSTRAINT ck_agent_message_type;
ALTER TABLE agent_message ADD CONSTRAINT ck_agent_message_type CHECK (
    message_type IN ('ARGUMENT', 'CHALLENGE', 'REVISION', 'CHAIR_VERDICT', 'CONSENSUS_RESULT')
);
ALTER TABLE agent_message DROP CONSTRAINT ck_agent_message_forecast;
ALTER TABLE agent_message ADD CONSTRAINT ck_agent_message_forecast CHECK (
    forecast IS NULL OR (
        message_type IN ('CHAIR_VERDICT', 'CONSENSUS_RESULT')
        AND jsonb_typeof(forecast) = 'object'
    )
);

--rollback ALTER TABLE workflow_node_definition DROP COLUMN IF EXISTS logical_role_key;
--rollback ALTER TABLE agent_message DROP CONSTRAINT IF EXISTS ck_agent_message_forecast;
--rollback ALTER TABLE agent_message ADD CONSTRAINT ck_agent_message_forecast CHECK (forecast IS NULL OR (message_type = 'CHAIR_VERDICT' AND jsonb_typeof(forecast) = 'object'));
--rollback ALTER TABLE agent_message DROP CONSTRAINT IF EXISTS ck_agent_message_type;
--rollback ALTER TABLE agent_message ADD CONSTRAINT ck_agent_message_type CHECK (message_type IN ('ARGUMENT', 'CHALLENGE', 'REVISION', 'CHAIR_VERDICT'));
--rollback ALTER TABLE debate_session RENAME COLUMN decision_node_id TO chair_node_id;
--rollback ALTER TABLE workflow_definition_version DROP COLUMN IF EXISTS debate_critique_assignment;
--rollback ALTER TABLE workflow_definition_version DROP COLUMN IF EXISTS debate_stage_timeout_seconds;
--rollback ALTER TABLE workflow_definition_version DROP COLUMN IF EXISTS debate_minimum_quorum_roles;
--rollback ALTER TABLE workflow_definition_version DROP COLUMN IF EXISTS debate_minimum_participant_seats;
--rollback ALTER TABLE workflow_definition_version DROP COLUMN IF EXISTS debate_protocol;

--changeset codex:061-sdb-sca-ledger splitStatements:true endDelimiter:;
CREATE TABLE debate_protocol_phase (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phase_id VARCHAR(80) NOT NULL UNIQUE,
    debate_id VARCHAR(80) NOT NULL REFERENCES debate_session (debate_id) ON DELETE CASCADE,
    protocol VARCHAR(24) NOT NULL,
    generation INTEGER NOT NULL,
    phase_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    required_tasks INTEGER NOT NULL,
    completed_tasks INTEGER NOT NULL DEFAULT 0,
    deadline TIMESTAMPTZ NOT NULL,
    opened_at TIMESTAMPTZ,
    revealed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_debate_protocol_phase UNIQUE (debate_id, generation, phase_type),
    CONSTRAINT ck_debate_protocol_phase_id CHECK (phase_id ~ '^phase_[a-z0-9_-]{4,73}$'),
    CONSTRAINT ck_debate_protocol_phase_protocol CHECK (protocol = 'SDB_SCA_V1'),
    CONSTRAINT ck_debate_protocol_phase_generation CHECK (generation > 0),
    CONSTRAINT ck_debate_protocol_phase_type CHECK (
        phase_type IN ('PROPOSAL', 'CRITIQUE', 'REVISION', 'BALLOT', 'AGGREGATION')
    ),
    CONSTRAINT ck_debate_protocol_phase_status CHECK (
        status IN ('PENDING', 'OPEN', 'REVEALING', 'REVEALED', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_debate_protocol_phase_tasks CHECK (
        required_tasks > 0 AND completed_tasks BETWEEN 0 AND required_tasks
    ),
    CONSTRAINT ck_debate_protocol_phase_timestamps CHECK (
        (revealed_at IS NULL OR opened_at IS NOT NULL)
        AND (completed_at IS NULL OR revealed_at IS NOT NULL)
    ),
    CONSTRAINT ck_debate_protocol_phase_version CHECK (version >= 0)
);

CREATE INDEX ix_debate_protocol_phase_progress
    ON debate_protocol_phase (debate_id, generation, status, phase_type);

CREATE TABLE debate_protocol_task (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id VARCHAR(80) NOT NULL UNIQUE,
    phase_id VARCHAR(80) NOT NULL REFERENCES debate_protocol_phase (phase_id) ON DELETE CASCADE,
    actor_node_id VARCHAR(80) NOT NULL,
    logical_role_key VARCHAR(80) NOT NULL,
    target_candidate_id VARCHAR(80),
    task_variant VARCHAR(16) NOT NULL DEFAULT 'PRIMARY',
    input_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt SMALLINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_debate_protocol_task_assignment UNIQUE (
        phase_id, actor_node_id, target_candidate_id, task_variant
    ),
    CONSTRAINT ck_debate_protocol_task_id CHECK (task_id ~ '^debate_task_[a-z0-9_-]{4,66}$'),
    CONSTRAINT ck_debate_protocol_task_role CHECK (logical_role_key ~ '^[a-z][a-z0-9_-]{1,79}$'),
    CONSTRAINT ck_debate_protocol_task_hash CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_debate_protocol_task_variant CHECK (
        task_variant IN ('PRIMARY', 'FORWARD', 'REVERSED')
    ),
    CONSTRAINT ck_debate_protocol_task_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
    ),
    CONSTRAINT ck_debate_protocol_task_attempt CHECK (attempt BETWEEN 0 AND 5),
    CONSTRAINT ck_debate_protocol_task_lease CHECK (
        (status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR status <> 'CLAIMED'
    ),
    CONSTRAINT ck_debate_protocol_task_completed CHECK (
        completed_at IS NULL OR status IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
    )
);

CREATE INDEX ix_debate_protocol_task_claim
    ON debate_protocol_task (phase_id, status, lease_expires_at, id);

CREATE UNIQUE INDEX uq_debate_protocol_task_null_safe_assignment
    ON debate_protocol_task (
        phase_id, actor_node_id, COALESCE(target_candidate_id, ''), task_variant
    );

CREATE TABLE debate_protocol_artifact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    artifact_id VARCHAR(80) NOT NULL UNIQUE,
    task_id VARCHAR(80) NOT NULL UNIQUE REFERENCES debate_protocol_task (task_id) ON DELETE CASCADE,
    phase_id VARCHAR(80) NOT NULL REFERENCES debate_protocol_phase (phase_id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'SEALED',
    content_hash CHAR(64) NOT NULL,
    content JSONB NOT NULL,
    sealed_at TIMESTAMPTZ NOT NULL,
    revealed_at TIMESTAMPTZ,
    CONSTRAINT ck_debate_protocol_artifact_id CHECK (
        artifact_id ~ '^debate_artifact_[a-z0-9_-]{4,62}$'
    ),
    CONSTRAINT ck_debate_protocol_artifact_status CHECK (
        status IN ('SEALED', 'REVEALED', 'REJECTED')
    ),
    CONSTRAINT ck_debate_protocol_artifact_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_debate_protocol_artifact_reveal CHECK (
        (status = 'REVEALED' AND revealed_at IS NOT NULL)
        OR (status <> 'REVEALED' AND revealed_at IS NULL)
    )
);

CREATE INDEX ix_debate_protocol_artifact_visibility
    ON debate_protocol_artifact (phase_id, status, id);

CREATE TABLE debate_candidate (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    candidate_id VARCHAR(80) NOT NULL UNIQUE,
    debate_id VARCHAR(80) NOT NULL REFERENCES debate_session (debate_id) ON DELETE CASCADE,
    origin_node_id VARCHAR(80) NOT NULL,
    logical_role_key VARCHAR(80) NOT NULL,
    anonymous_alias VARCHAR(32) NOT NULL,
    proposal_artifact_id VARCHAR(80) NOT NULL REFERENCES debate_protocol_artifact (artifact_id),
    revision_artifact_id VARCHAR(80) REFERENCES debate_protocol_artifact (artifact_id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_debate_candidate_origin UNIQUE (debate_id, origin_node_id),
    CONSTRAINT uq_debate_candidate_alias UNIQUE (debate_id, anonymous_alias),
    CONSTRAINT ck_debate_candidate_id CHECK (candidate_id ~ '^candidate_[a-z0-9_-]{4,69}$'),
    CONSTRAINT ck_debate_candidate_role CHECK (logical_role_key ~ '^[a-z][a-z0-9_-]{1,79}$'),
    CONSTRAINT ck_debate_candidate_alias CHECK (anonymous_alias ~ '^candidate_[a-z0-9]{2,22}$')
);

ALTER TABLE debate_protocol_task
    ADD CONSTRAINT fk_debate_protocol_task_target_candidate
    FOREIGN KEY (target_candidate_id) REFERENCES debate_candidate (candidate_id);

--rollback DROP TABLE IF EXISTS debate_candidate;
--rollback DROP TABLE IF EXISTS debate_protocol_artifact;
--rollback DROP TABLE IF EXISTS debate_protocol_task;
--rollback DROP TABLE IF EXISTS debate_protocol_phase;

--changeset codex:061-sdb-sca-consensus-ledger splitStatements:true endDelimiter:;
CREATE TABLE consensus_ballot (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ballot_id VARCHAR(80) NOT NULL UNIQUE,
    debate_id VARCHAR(80) NOT NULL REFERENCES debate_session (debate_id) ON DELETE CASCADE,
    phase_id VARCHAR(80) NOT NULL REFERENCES debate_protocol_phase (phase_id) ON DELETE CASCADE,
    actor_node_id VARCHAR(80) NOT NULL,
    logical_role_key VARCHAR(80) NOT NULL,
    orientation VARCHAR(16) NOT NULL,
    preference_tiers JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_consensus_ballot_actor UNIQUE (debate_id, actor_node_id, orientation),
    CONSTRAINT ck_consensus_ballot_id CHECK (ballot_id ~ '^ballot_[a-z0-9_-]{4,72}$'),
    CONSTRAINT ck_consensus_ballot_role CHECK (logical_role_key ~ '^[a-z][a-z0-9_-]{1,79}$'),
    CONSTRAINT ck_consensus_ballot_orientation CHECK (orientation IN ('FORWARD', 'REVERSED')),
    CONSTRAINT ck_consensus_ballot_tiers CHECK (jsonb_typeof(preference_tiers) = 'array'),
    CONSTRAINT ck_consensus_ballot_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_consensus_ballot_debate_role
    ON consensus_ballot (debate_id, logical_role_key, orientation, id);

CREATE TABLE consensus_decision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decision_id VARCHAR(80) NOT NULL UNIQUE,
    debate_id VARCHAR(80) NOT NULL UNIQUE REFERENCES debate_session (debate_id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    winner_candidate_id VARCHAR(80) REFERENCES debate_candidate (candidate_id),
    quorum_roles INTEGER NOT NULL,
    undefeated_candidates JSONB NOT NULL,
    pairwise_matrix JSONB NOT NULL,
    strongest_paths JSONB NOT NULL,
    ranking JSONB NOT NULL,
    forecast JSONB,
    explanation TEXT,
    decision_hash CHAR(64) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_consensus_decision_id CHECK (decision_id ~ '^decision_[a-z0-9_-]{4,70}$'),
    CONSTRAINT ck_consensus_decision_status CHECK (status IN (
        'SELECTED', 'TIED', 'LOW_QUORUM', 'ORDER_SENSITIVE',
        'NO_VALID_BALLOTS', 'NO_STRICT_WINNER'
    )),
    CONSTRAINT ck_consensus_decision_winner CHECK (
        (status = 'SELECTED' AND winner_candidate_id IS NOT NULL)
        OR (status <> 'SELECTED' AND winner_candidate_id IS NULL)
    ),
    CONSTRAINT ck_consensus_decision_quorum CHECK (quorum_roles >= 0),
    CONSTRAINT ck_consensus_decision_json CHECK (
        jsonb_typeof(undefeated_candidates) = 'array'
        AND jsonb_typeof(pairwise_matrix) = 'object'
        AND jsonb_typeof(strongest_paths) = 'object'
        AND jsonb_typeof(ranking) = 'array'
        AND (forecast IS NULL OR jsonb_typeof(forecast) = 'object')
    ),
    CONSTRAINT ck_consensus_decision_hash CHECK (decision_hash ~ '^[0-9a-f]{64}$')
);

--rollback DROP TABLE IF EXISTS consensus_decision;
--rollback DROP TABLE IF EXISTS consensus_ballot;
