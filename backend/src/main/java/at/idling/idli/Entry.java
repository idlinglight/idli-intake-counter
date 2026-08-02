package at.idling.idli;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

/**
 * A single logged intake: an amount of a metric (in its canonical unit,
 * ADR-0002) at a point in time. Entries created together by one serving-log
 * action share a group id and a snapshotted label (ADR-0007 amendment) — never
 * a reference into the catalog. Ad-hoc entries carry neither.
 */
@Entity
public class Entry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long metricId;

	private long amount;

	private Instant loggedAt;

	private UUID groupId;

	private String label;

	protected Entry() {
	}

	public Entry(Long metricId, long amount, Instant loggedAt) {
		this(metricId, amount, loggedAt, null, null);
	}

	public Entry(Long metricId, long amount, Instant loggedAt, UUID groupId, String label) {
		this.metricId = metricId;
		this.amount = amount;
		this.loggedAt = loggedAt;
		this.groupId = groupId;
		this.label = label;
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

	public UUID getGroupId() {
		return groupId;
	}

	public String getLabel() {
		return label;
	}

}
