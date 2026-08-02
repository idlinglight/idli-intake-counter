package at.idling.idli;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Full-database export/import — the recovery story of ADR-0004. Export emits
 * everything; import atomically REPLACES everything with the file's content.
 * It is a restore, not a merge: restoring a formatVersion 1 backup therefore
 * yields a database without items (the file IS the database, ADR-0007).
 */
@Service
public class ExportImportService {

	/**
	 * Bump when the file shape changes; export always emits this version.
	 * Import also accepts every older version, normalizing on read (ADR-0007).
	 * History: 1 = metrics + entries; 2 = items; 3 = entry label/group.
	 */
	static final int FORMAT_VERSION = 3;

	private final MetricRepository metricRepository;
	private final ItemRepository itemRepository;
	private final ItemAmountRepository itemAmountRepository;
	private final ServingRepository servingRepository;
	private final EntryRepository entryRepository;

	public ExportImportService(MetricRepository metricRepository, ItemRepository itemRepository,
			ItemAmountRepository itemAmountRepository, ServingRepository servingRepository,
			EntryRepository entryRepository) {
		this.metricRepository = metricRepository;
		this.itemRepository = itemRepository;
		this.itemAmountRepository = itemAmountRepository;
		this.servingRepository = servingRepository;
		this.entryRepository = entryRepository;
	}

	// REPEATABLE_READ, deliberately: one snapshot for all reads. Under the
	// default READ_COMMITTED every statement sees its own snapshot, so an
	// import committing between the findAll()s would renumber the metric
	// ids and this method would emit entries with "metric": null — a backup
	// that fails exactly when it is needed: at restore time.
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ExportDto export() {
		List<Metric> metrics = metricRepository.findAll().stream()
				.sorted(Comparator.comparing(Metric::getId))
				.toList();
		Map<Long, String> nameById = metrics.stream()
				.collect(Collectors.toMap(Metric::getId, Metric::getName));
		Map<Long, List<ItemAmount>> amountsByItemId = itemAmountRepository.findAll().stream()
				.collect(Collectors.groupingBy(ItemAmount::getItemId));
		Map<Long, List<Serving>> servingsByItemId = servingRepository.findAll().stream()
				.collect(Collectors.groupingBy(Serving::getItemId));
		// Deterministic order keeps two exports of the same data diffable.
		// Item amounts sort by metric NAME — the stable key across re-imports —
		// so export → import → export stays byte-identical.
		List<ExportItemDto> items = itemRepository.findAll().stream()
				.sorted(Comparator.comparing(Item::getId))
				.map(item -> new ExportItemDto(item.getName(), item.getBasisAmount(), item.getBasisUnit(),
						amountsByItemId.getOrDefault(item.getId(), List.of()).stream()
								.map(amount -> new ExportItemAmountDto(
										requireMetricName(nameById, amount.getMetricId(),
												"item amount " + amount.getId()),
										amount.getAmount()))
								.sorted(Comparator.comparing(ExportItemAmountDto::metric))
								.toList(),
						servingsByItemId.getOrDefault(item.getId(), List.of()).stream()
								.sorted(Comparator.comparing(Serving::getId))
								.map(serving -> new ExportServingDto(serving.getName(), serving.getQuantity()))
								.toList()))
				.toList();
		List<ExportEntryDto> entries = entryRepository.findAll().stream()
				.sorted(Comparator.comparing(Entry::getLoggedAt).thenComparing(Entry::getId))
				.map(entry -> new ExportEntryDto(
						requireMetricName(nameById, entry.getMetricId(), "entry " + entry.getId()),
						entry.getAmount(), entry.getLoggedAt(), entry.getLabel(), entry.getGroupId()))
				.toList();
		return new ExportDto(FORMAT_VERSION, Instant.now(),
				metrics.stream().map(metric -> new ExportMetricDto(metric.getName(), metric.getCanonicalUnit()))
						.toList(),
				items, entries);
	}

	@Transactional
	public ImportSummaryDto importReplacing(ExportDto file) {
		// All validation happens before the first delete; the surrounding
		// transaction is the backstop, not the plan.
		requireSupportedVersion(file);
		List<ExportItemDto> items = normalizedItems(file);
		requireEntryFieldsMatchVersion(file);
		// Also guarded by @NotEmpty at the controller; kept here so no caller
		// can apply a file that leaves the database metric-less. There is no
		// import-free way to log anything then — metric authoring exists, but
		// an empty file is far more likely a truncated backup than intent.
		if (file.metrics().isEmpty()) {
			throw new InvalidImportException(
					"import would leave the database without any metrics; refusing an empty file");
		}
		Set<String> metricNames = new HashSet<>();
		for (ExportMetricDto metric : file.metrics()) {
			if (!metricNames.add(metric.name())) {
				throw new InvalidImportException("duplicate metric name: " + metric.name());
			}
		}
		Set<String> itemNames = new HashSet<>();
		for (ExportItemDto item : items) {
			if (!itemNames.add(item.name())) {
				throw new InvalidImportException("duplicate item name: " + item.name());
			}
			Set<String> amountMetrics = new HashSet<>();
			for (ExportItemAmountDto amount : item.amounts()) {
				if (!amountMetrics.add(amount.metric())) {
					throw new InvalidImportException(
							"item '" + item.name() + "' lists metric more than once: " + amount.metric());
				}
				if (!metricNames.contains(amount.metric())) {
					throw new InvalidImportException(
							"item '" + item.name() + "' references unknown metric: " + amount.metric());
				}
			}
			Set<String> servingNames = new HashSet<>();
			for (ExportServingDto serving : item.servings()) {
				if (!servingNames.add(serving.name())) {
					throw new InvalidImportException(
							"item '" + item.name() + "' has duplicate serving name: " + serving.name());
				}
			}
		}
		// One serving-log action means one label and one timestamp per group —
		// the write path guarantees it, so import must too, or the UI's
		// one-row-one-atomic-delete rendering silently breaks (a group split
		// across days would delete entries the user never saw).
		Map<UUID, ExportEntryDto> groupRepresentative = new HashMap<>();
		for (ExportEntryDto entry : file.entries()) {
			if (!metricNames.contains(entry.metric())) {
				throw new InvalidImportException("entry references unknown metric: " + entry.metric());
			}
			// Mirrors the DB CHECK (entry_group_label_together): rejecting here
			// is a loud 400 with a reason; the constraint firing mid-import
			// would be a 500.
			if ((entry.label() == null) != (entry.group() == null)) {
				throw new InvalidImportException(
						"entry at " + entry.loggedAt() + " must carry label and group together");
			}
			if (entry.label() != null && entry.label().isBlank()) {
				throw new InvalidImportException("entry at " + entry.loggedAt() + " has a blank label");
			}
			if (entry.group() != null) {
				ExportEntryDto first = groupRepresentative.putIfAbsent(entry.group(), entry);
				if (first != null
						&& (!first.label().equals(entry.label()) || !first.loggedAt().equals(entry.loggedAt()))) {
					throw new InvalidImportException(
							"entries of group " + entry.group() + " disagree on label or loggedAt");
				}
			}
		}

		// Referencing tables first, then their targets.
		servingRepository.deleteAllInBulk();
		itemAmountRepository.deleteAllInBulk();
		itemRepository.deleteAllInBulk();
		entryRepository.deleteAllInBulk();
		metricRepository.deleteAllInBulk();

		Map<String, Long> metricIdByName = new HashMap<>();
		for (ExportMetricDto metric : file.metrics()) {
			metricIdByName.put(metric.name(),
					metricRepository.save(new Metric(metric.name(), metric.canonicalUnit())).getId());
		}
		for (ExportItemDto item : items) {
			Long itemId = itemRepository.save(new Item(item.name(), item.basisAmount(), item.basisUnit())).getId();
			itemAmountRepository.saveAll(item.amounts().stream()
					.map(amount -> new ItemAmount(itemId, metricIdByName.get(amount.metric()), amount.amount()))
					.toList());
			servingRepository.saveAll(item.servings().stream()
					.map(serving -> new Serving(itemId, serving.name(), serving.quantity()))
					.toList());
		}
		entryRepository.saveAll(file.entries().stream()
				.map(entry -> new Entry(metricIdByName.get(entry.metric()), entry.amount(), entry.loggedAt(),
						entry.group(), entry.label()))
				.toList());
		return new ImportSummaryDto(file.metrics().size(), items.size(), file.entries().size());
	}

	// The three helpers below are the one place format history lives
	// (ADR-0007): older versions normalize on read, and a file claiming an
	// older version while carrying newer-version data is refused rather than
	// silently truncated.

	private static void requireSupportedVersion(ExportDto file) {
		int version = file.formatVersion();
		if (version < 1 || version > FORMAT_VERSION) {
			throw new InvalidImportException("unsupported formatVersion " + version
					+ "; this build reads formatVersion 1, 2 and " + FORMAT_VERSION);
		}
	}

	/** Version 1 files carry no items; every later version must carry the list. */
	private static List<ExportItemDto> normalizedItems(ExportDto file) {
		if (file.formatVersion() == 1) {
			if (file.items() != null && !file.items().isEmpty()) {
				throw new InvalidImportException("formatVersion 1 files cannot carry items");
			}
			return List.of();
		}
		if (file.items() == null) {
			throw new InvalidImportException("formatVersion " + file.formatVersion() + " requires an items list");
		}
		return file.items();
	}

	/** Entry label/group arrived with version 3; older files cannot carry them. */
	private static void requireEntryFieldsMatchVersion(ExportDto file) {
		if (file.formatVersion() >= 3) {
			return;
		}
		for (ExportEntryDto entry : file.entries()) {
			if (entry.label() != null || entry.group() != null) {
				throw new InvalidImportException("formatVersion " + file.formatVersion()
						+ " files cannot carry entry labels or groups");
			}
		}
	}

	// Belt to the isolation level's braces: a null name would serialize into
	// the file and surface only at restore time. Better no export than a
	// silently corrupt one.
	private static String requireMetricName(Map<Long, String> nameById, Long metricId, String owner) {
		String name = nameById.get(metricId);
		if (name == null) {
			throw new IllegalStateException(owner + " references missing metric id " + metricId);
		}
		return name;
	}

}
