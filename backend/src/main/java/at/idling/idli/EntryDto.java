package at.idling.idli;

import java.time.Instant;
import java.util.UUID;

/**
 * @param groupId shared by the entries of one serving-log action; null for ad-hoc entries
 * @param label   snapshotted at log time ("item – serving ×2"); null for ad-hoc entries
 */
public record EntryDto(long id, long metricId, long amount, Instant loggedAt, UUID groupId, String label) {
}
