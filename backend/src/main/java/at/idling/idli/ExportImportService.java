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
import java.util.stream.Collectors;

/**
 * Full-database export/import — the recovery story of ADR-0004. Export emits
 * everything; import atomically REPLACES everything with the file's content.
 * It is a restore, not a merge.
 */
@Service
public class ExportImportService {

	/** Bump when the file shape changes; import accepts only this version. */
	static final int FORMAT_VERSION = 1;

	private final MetricRepository metricRepository;
	private final EntryRepository entryRepository;

	public ExportImportService(MetricRepository metricRepository, EntryRepository entryRepository) {
		this.metricRepository = metricRepository;
		this.entryRepository = entryRepository;
	}

	// REPEATABLE_READ, deliberately: one snapshot for both reads. Under the
	// default READ_COMMITTED every statement sees its own snapshot, so an
	// import committing between the two findAll()s would renumber the metric
	// ids and this method would emit entries with "metric": null — a backup
	// that fails exactly when it is needed: at restore time.
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ExportDto export() {
		List<Metric> metrics = metricRepository.findAll().stream()
				.sorted(Comparator.comparing(Metric::getId))
				.toList();
		Map<Long, String> nameById = metrics.stream()
				.collect(Collectors.toMap(Metric::getId, Metric::getName));
		// Deterministic order keeps two exports of the same data diffable.
		List<ExportEntryDto> entries = entryRepository.findAll().stream()
				.sorted(Comparator.comparing(Entry::getLoggedAt).thenComparing(Entry::getId))
				.map(entry -> new ExportEntryDto(requireMetricName(nameById, entry), entry.getAmount(),
						entry.getLoggedAt()))
				.toList();
		return new ExportDto(FORMAT_VERSION, Instant.now(),
				metrics.stream().map(metric -> new ExportMetricDto(metric.getName(), metric.getCanonicalUnit()))
						.toList(),
				entries);
	}

	@Transactional
	public ImportSummaryDto importReplacing(ExportDto file) {
		// All validation happens before the first delete; the surrounding
		// transaction is the backstop, not the plan.
		if (file.formatVersion() != FORMAT_VERSION) {
			throw new InvalidImportException("unsupported formatVersion " + file.formatVersion()
					+ "; this build reads formatVersion " + FORMAT_VERSION);
		}
		// Also guarded by @NotEmpty at the controller; kept here so no caller
		// can apply a file that leaves the database metric-less. There is no
		// endpoint that creates metrics, so that state would be unrecoverable
		// from inside the app.
		if (file.metrics().isEmpty()) {
			throw new InvalidImportException(
					"import would leave the database without any metrics; refusing an empty file");
		}
		Set<String> names = new HashSet<>();
		for (ExportMetricDto metric : file.metrics()) {
			if (!names.add(metric.name())) {
				throw new InvalidImportException("duplicate metric name: " + metric.name());
			}
		}
		for (ExportEntryDto entry : file.entries()) {
			if (!names.contains(entry.metric())) {
				throw new InvalidImportException("entry references unknown metric: " + entry.metric());
			}
		}

		// Entries first — they hold the foreign key onto metric.
		entryRepository.deleteAllInBulk();
		metricRepository.deleteAllInBulk();

		Map<String, Long> idByName = new HashMap<>();
		for (ExportMetricDto metric : file.metrics()) {
			idByName.put(metric.name(),
					metricRepository.save(new Metric(metric.name(), metric.canonicalUnit())).getId());
		}
		entryRepository.saveAll(file.entries().stream()
				.map(entry -> new Entry(idByName.get(entry.metric()), entry.amount(), entry.loggedAt()))
				.toList());
		return new ImportSummaryDto(file.metrics().size(), file.entries().size());
	}

	// Belt to the isolation level's braces: a null name would serialize into
	// the file and surface only at restore time. Better no export than a
	// silently corrupt one.
	private static String requireMetricName(Map<Long, String> nameById, Entry entry) {
		String name = nameById.get(entry.getMetricId());
		if (name == null) {
			throw new IllegalStateException(
					"entry " + entry.getId() + " references missing metric id " + entry.getMetricId());
		}
		return name;
	}

}
