package at.idling.idli;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one serving-log action wrote: N entries (one per composition metric,
 * sorted by metricId) sharing the group id, label and timestamp.
 */
public record EntryGroupDto(UUID groupId, String label, Instant loggedAt, List<EntryDto> entries) {
}
