package at.idling.idli;

import java.time.Instant;

public record EntryDto(long id, long metricId, long amount, Instant loggedAt) {
}
