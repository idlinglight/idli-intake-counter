package at.idling.idli;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IntakeService {

	private final MetricRepository metricRepository;
	private final EntryRepository entryRepository;
	private final ServingRepository servingRepository;
	private final ItemRepository itemRepository;
	private final ItemAmountRepository itemAmountRepository;
	private final ZoneId zone;

	public IntakeService(MetricRepository metricRepository, EntryRepository entryRepository,
			ServingRepository servingRepository, ItemRepository itemRepository,
			ItemAmountRepository itemAmountRepository, ZoneId zone) {
		this.metricRepository = metricRepository;
		this.entryRepository = entryRepository;
		this.servingRepository = servingRepository;
		this.itemRepository = itemRepository;
		this.itemAmountRepository = itemAmountRepository;
		this.zone = zone;
	}

	public List<MetricDto> metrics() {
		return metricRepository.findAll().stream()
				.sorted(Comparator.comparing(Metric::getId))
				.map(metric -> new MetricDto(metric.getId(), metric.getName(), metric.getCanonicalUnit()))
				.toList();
	}

	public EntryDto log(NewEntryRequest request) {
		if (!metricRepository.existsById(request.metricId())) {
			throw new UnknownMetricException(request.metricId());
		}
		Instant loggedAt = request.loggedAt() != null ? request.loggedAt() : Instant.now();
		Entry saved = entryRepository.save(new Entry(request.metricId(), request.amount(), loggedAt));
		return toDto(saved);
	}

	/**
	 * The snapshot gesture of ADR-0007: computes the serving's per-metric
	 * amounts from the item's composition and copies them into plain entries
	 * sharing a fresh group id and a label composed here — no catalog
	 * references, so later renames/deletes never touch what was logged.
	 */
	// REPEATABLE_READ for the same reason as AuthoringService.items(): serving,
	// item and amounts must come from one snapshot, or a racing import could
	// pair a serving with a foreign item's composition.
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public EntryGroupDto logServing(long servingId, NewEntryGroupRequest request) {
		Serving serving = servingRepository.findById(servingId)
				.orElseThrow(() -> new ServingNotFoundException(servingId));
		Item item = itemRepository.findById(serving.getItemId())
				.orElseThrow(() -> new IllegalStateException(
						"serving " + servingId + " references missing item id " + serving.getItemId()));
		List<ItemAmount> amounts = composition(item);
		BigDecimal multiplier = request.multiplier() != null ? request.multiplier() : BigDecimal.ONE;
		Instant loggedAt = request.loggedAt() != null ? request.loggedAt() : Instant.now();
		return snapshotGroup(item, amounts, serving.getQuantity(), multiplier, label(item, serving, multiplier),
				loggedAt);
	}

	/**
	 * The ad-hoc sibling of {@link #logServing} (issue #15): the quantity is a
	 * log-time measurement in the item's basis unit (a weighed portion), not an
	 * authored serving — the label snapshots the quantity itself
	 * ("pasta – 137 g"). No multiplier: the free-form quantity is already the
	 * precise knob, and a second multiplicative input invites silent mistakes.
	 */
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public EntryGroupDto logItem(long itemId, NewItemEntryRequest request) {
		Item item = itemRepository.findById(itemId)
				.orElseThrow(() -> new ItemNotFoundException(itemId));
		List<ItemAmount> amounts = composition(item);
		Instant loggedAt = request.loggedAt() != null ? request.loggedAt() : Instant.now();
		String label = item.getName() + " – " + request.quantity() + " " + item.getBasisUnit();
		return snapshotGroup(item, amounts, request.quantity(), BigDecimal.ONE, label, loggedAt);
	}

	private List<ItemAmount> composition(Item item) {
		List<ItemAmount> amounts = itemAmountRepository.findByItemId(item.getId()).stream()
				.sorted(Comparator.comparing(ItemAmount::getMetricId))
				.toList();
		// Belt: authoring and import both refuse composition-less items.
		if (amounts.isEmpty()) {
			throw new InvalidEntryGroupException("item '" + item.getName() + "' has no composition; nothing to log");
		}
		return amounts;
	}

	private EntryGroupDto snapshotGroup(Item item, List<ItemAmount> amounts, long quantity, BigDecimal multiplier,
			String label, Instant loggedAt) {
		UUID groupId = UUID.randomUUID();
		// Compute everything before writing anything: a rejection must be a
		// no-op, never a partial group.
		List<Entry> entries = amounts.stream()
				.map(amount -> new Entry(amount.getMetricId(),
						computedAmount(amount, quantity, multiplier, item, label), loggedAt, groupId, label))
				.toList();
		return new EntryGroupDto(groupId, label, loggedAt,
				entryRepository.saveAll(entries).stream().map(this::toDto).toList());
	}

	public void delete(long entryId) {
		if (entryRepository.deleteEntryById(entryId) == 0) {
			throw new EntryNotFoundException(entryId);
		}
	}

	public void deleteGroup(UUID groupId) {
		if (entryRepository.deleteEntryGroupById(groupId) == 0) {
			throw new EntryGroupNotFoundException(groupId);
		}
	}

	public DayViewDto today() {
		return day(LocalDate.now(zone));
	}

	public DayViewDto day(LocalDate date) {
		Instant from = date.atStartOfDay(zone).toInstant();
		Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<Entry> entries = entryRepository
				.findByLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDescIdDesc(from, to);

		Map<Long, Long> totalByMetricId = entries.stream()
				.collect(Collectors.groupingBy(Entry::getMetricId, Collectors.summingLong(Entry::getAmount)));
		List<TotalDto> totals = metricRepository.findAllById(totalByMetricId.keySet()).stream()
				.sorted(Comparator.comparing(Metric::getId))
				.map(metric -> new TotalDto(metric.getId(), metric.getName(), metric.getCanonicalUnit(),
						totalByMetricId.get(metric.getId())))
				.toList();

		return new DayViewDto(date, entries.stream().map(this::toDto).toList(), totals);
	}

	/** composition × quantity × multiplier ÷ basis, rounded half-up to the canonical integer. */
	private long computedAmount(ItemAmount amount, long quantity, BigDecimal multiplier, Item item, String label) {
		long computed;
		try {
			computed = BigDecimal.valueOf(amount.getAmount())
					.multiply(BigDecimal.valueOf(quantity))
					.multiply(multiplier)
					.divide(BigDecimal.valueOf(item.getBasisAmount()), 0, RoundingMode.HALF_UP)
					.longValueExact();
		} catch (ArithmeticException e) {
			// Catalog amounts are only checked @Positive, so absurd values can
			// overflow long here — same contract as rounding to 0: loud 400,
			// not a 500 (ADR-0007: a rejection must be a no-op).
			throw new InvalidEntryGroupException(
					"logging '" + label + "' would overflow a metric amount; refusing");
		}
		if (computed == 0) {
			// entry.amount has check (amount > 0); silently dropping the metric
			// instead would log less than the user believes (ADR-0007: loud).
			String metricName = metricRepository.findById(amount.getMetricId())
					.map(Metric::getName)
					.orElse("#" + amount.getMetricId());
			throw new InvalidEntryGroupException(
					"logging '" + label + "' would round metric '" + metricName + "' to 0; refusing a silent no-op");
		}
		return computed;
	}

	private static String label(Item item, Serving serving, BigDecimal multiplier) {
		String base = item.getName() + " – " + serving.getName();
		if (multiplier.compareTo(BigDecimal.ONE) == 0) {
			return base;
		}
		return base + " ×" + multiplier.stripTrailingZeros().toPlainString();
	}

	private EntryDto toDto(Entry entry) {
		return new EntryDto(entry.getId(), entry.getMetricId(), entry.getAmount(), entry.getLoggedAt(),
				entry.getGroupId(), entry.getLabel());
	}

}
