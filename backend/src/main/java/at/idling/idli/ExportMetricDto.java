package at.idling.idli;

import jakarta.validation.constraints.NotBlank;

public record ExportMetricDto(@NotBlank String name, @NotBlank String canonicalUnit) {
}
