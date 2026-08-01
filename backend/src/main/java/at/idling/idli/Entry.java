package at.idling.idli;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * A single logged intake: an amount of a metric (in its canonical unit,
 * ADR-0002) at a point in time.
 */
@Entity
public class Entry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long metricId;

	private long amount;

	private Instant loggedAt;

	protected Entry() {
	}

	public Entry(Long metricId, long amount, Instant loggedAt) {
		this.metricId = metricId;
		this.amount = amount;
		this.loggedAt = loggedAt;
	}

	public Long getId() {
		return id;
	}

	public Long getMetricId() {
		return metricId;
	}

	public long getAmount() {
		return amount;
	}

	public Instant getLoggedAt() {
		return loggedAt;
	}

}
