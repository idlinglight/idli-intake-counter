package at.idling.idli;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Create/replace payload for a serving: a name and a quantity in the owning
 * item's basis unit ("whole bar" = 50 g). No metric amounts — those are
 * computed from the item's composition (ADR-0008).
 */
public record ServingRequest(@NotBlank String name, @NotNull @Positive Long quantity) {
}
