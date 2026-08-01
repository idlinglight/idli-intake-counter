package at.idling.idli;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * @param amount   in the metric's canonical unit (ADR-0002)
 * @param loggedAt optional; defaults to now
 */
public record NewEntryRequest(@NotNull Long metricId, @NotNull @Positive Long amount, Instant loggedAt) {
}
