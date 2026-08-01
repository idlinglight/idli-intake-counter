package at.idling.idli;

import java.time.LocalDate;
import java.util.List;

/**
 * @param entries the day's entries, ordered by loggedAt descending
 * @param totals  per-metric sums for the day, only for metrics with entries
 */
public record DayViewDto(LocalDate date, List<EntryDto> entries, List<TotalDto> totals) {
}
