package at.idling.idli;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** @param quantity in the owning item's basis unit */
public record ExportServingDto(@NotBlank String name, @NotNull @Positive Long quantity) {
}
