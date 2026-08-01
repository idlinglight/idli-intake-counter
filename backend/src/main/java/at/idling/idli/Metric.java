package at.idling.idli;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A user-defined metric (water, energy, …). Amounts are always stored in the
 * metric's canonical unit (ADR-0002); the backend never converts units.
 * Rows are currently seeded by migration only, so this entity is read-only.
 */
@Entity
public class Metric {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	private String canonicalUnit;

	protected Metric() {
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCanonicalUnit() {
		return canonicalUnit;
	}

}
