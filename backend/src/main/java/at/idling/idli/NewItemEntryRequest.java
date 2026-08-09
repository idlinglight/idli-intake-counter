package at.idling.idli;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * Request to log an ad-hoc quantity of an item: the item id rides in the path;
 * the quantity is a log-time measurement in the item's basis unit (a weighed
 * portion), not a catalog reference. Deliberately no multiplier — the free-form
 * quantity is already the precise knob, and a second multiplicative input
 * invites silent mistakes.
 *
 * @param quantity amount in the item's basis unit; whole numbers, mirroring
 *                 {@link ServingRequest#quantity()}
 * @param loggedAt defaults to now (mirrors {@link NewEntryGroupRequest})
 */
public record NewItemEntryRequest(@NotNull @Positive Long quantity, Instant loggedAt) {
}
