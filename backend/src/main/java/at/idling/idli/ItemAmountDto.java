package at.idling.idli;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One metric's amount per basis quantity of an item, in the metric's canonical
 * unit. Dual-use as request and response element (export-DTO precedent).
 */
public record ItemAmountDto(@NotNull Long metricId, @NotNull @Positive Long amount) {
}
