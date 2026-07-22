package io.omnnu.finbot.domain.debate;

import java.time.Duration;
import java.util.Objects;

public record DebateProtocolConfiguration(
        DebateProtocol protocol,
        int minimumParticipantSeats,
        int minimumQuorumRoles,
        Duration stageTimeout,
        CritiqueAssignmentPolicy critiqueAssignmentPolicy) {
    public DebateProtocolConfiguration {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(stageTimeout, "stageTimeout");
        Objects.requireNonNull(critiqueAssignmentPolicy, "critiqueAssignmentPolicy");
        if (minimumParticipantSeats < 2 || minimumParticipantSeats > 32) {
            throw new IllegalArgumentException("minimumParticipantSeats must be between 2 and 32");
        }
        if (minimumQuorumRoles < 2 || minimumQuorumRoles > minimumParticipantSeats) {
            throw new IllegalArgumentException("minimumQuorumRoles must be between 2 and minimumParticipantSeats");
        }
        if (stageTimeout.compareTo(Duration.ofSeconds(30)) < 0
                || stageTimeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("stageTimeout must be between 30 seconds and 2 hours");
        }
        if (protocol == DebateProtocol.LEGACY_CHAIR_V1
                && critiqueAssignmentPolicy != CritiqueAssignmentPolicy.FULL_MATRIX) {
            throw new IllegalArgumentException("Legacy debate must use the compatibility assignment policy");
        }
    }

    public static DebateProtocolConfiguration legacy() {
        return new DebateProtocolConfiguration(
                DebateProtocol.LEGACY_CHAIR_V1,
                2,
                2,
                Duration.ofMinutes(10),
                CritiqueAssignmentPolicy.FULL_MATRIX);
    }

    public static DebateProtocolConfiguration sdbScaDefault() {
        return new DebateProtocolConfiguration(
                DebateProtocol.SDB_SCA_V1,
                3,
                3,
                Duration.ofMinutes(15),
                CritiqueAssignmentPolicy.FULL_MATRIX);
    }
}
