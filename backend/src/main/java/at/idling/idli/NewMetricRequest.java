package at.idling.idli;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to create a metric. The canonical unit is fixed at creation
 * (ADR-0002) — there is deliberately no rename/re-unit endpoint; repairs go
 * through export-edit-import (ADR-0004).
 */
public record NewMetricRequest(@NotBlank String name, @NotBlank String canonicalUnit) {
}
