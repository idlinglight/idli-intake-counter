package at.idling.idli;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request to log a serving (ADR-0007): the serving id rides in the path; this
 * body carries only the optional knobs. An empty object is a valid request.
 *
 * @param multiplier scales the serving at log time (0.5 = half, 2 = two);
 *                   defaults to 1. Never stored — it is baked into the
 *                   computed amounts and the label. Two decimals and three
 *                   integer digits keep values sane and labels readable.
 * @param loggedAt   defaults to now (mirrors {@link NewEntryRequest})
 */
public record NewEntryGroupRequest(@Positive @Digits(integer = 3, fraction = 2) BigDecimal multiplier,
		Instant loggedAt) {
}
