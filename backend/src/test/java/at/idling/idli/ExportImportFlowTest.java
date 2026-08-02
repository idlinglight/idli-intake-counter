package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Exercises the ADR-0004 recovery gesture over real HTTP: export must emit a
 * file that import can restore verbatim, and import must be all-or-nothing.
 *
 * The DB is shared with the other flow-test classes (cached context), so
 * every test here leaves the water metric in place, and entries land on
 * 2026-04-04 — a day no other test class asserts on.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = TestAuth.PASSWORD_HASH_PROPERTY)
@AutoConfigureTestRestTemplate
class ExportImportFlowTest {

	private static final ExportMetricDto WATER = new ExportMetricDto("water", "mL");

	private TestRestTemplate plainTemplate;
	private TestRestTemplate restTemplate;

	@Autowired
	void setRestTemplate(TestRestTemplate restTemplate) {
		this.plainTemplate = restTemplate;
		this.restTemplate = restTemplate.withBasicAuth(TestAuth.USERNAME, TestAuth.PASSWORD);
	}

	@Test
	void exportAndImportRequireAuthentication() {
		assertThat(plainTemplate.getForEntity("/api/export", String.class).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(plainTemplate.postForEntity("/api/import?mode=replace", validFile(), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void exportIsAVersionedAttachmentContainingTheData() {
		importReplacing(validFile());

		ResponseEntity<ExportDto> response = restTemplate.getForEntity("/api/export", ExportDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		ContentDisposition disposition = response.getHeaders().getContentDisposition();
		assertThat(disposition.getType()).isEqualTo("attachment");
		assertThat(disposition.getFilename()).matches("idli-export-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.json");

		ExportDto export = response.getBody();
		assertThat(export).isNotNull();
		assertThat(export.formatVersion()).isEqualTo(3);
		assertThat(export.exportedAt()).isNotNull();
		assertThat(export.metrics()).contains(WATER);
		assertThat(export.items()).isNotNull();
		assertThat(export.entries()).contains(entry("water", 250L, Instant.parse("2026-04-04T08:00:00Z")));
	}

	@Test
	void importReplacesEverythingAndRoundTrips() {
		// Deliberately a formatVersion 2 file: the previous build's backups
		// must keep restoring (ADR-0007). Amounts are listed sorted by metric
		// name and servings in creation order — the orders export emits — so
		// re-export equality can be exact.
		ExportDto file = new ExportDto(2, Instant.parse("2026-04-04T20:00:00Z"),
				List.of(WATER, new ExportMetricDto("energy", "kJ"), new ExportMetricDto("protein", "g")),
				List.of(new ExportItemDto("protein bar", 100L, "g",
						List.of(new ExportItemAmountDto("energy", 2281L), new ExportItemAmountDto("protein", 30L)),
						List.of(new ExportServingDto("whole bar", 50L), new ExportServingDto("half bar", 25L)))),
				List.of(entry("water", 300L, Instant.parse("2026-04-04T07:00:00Z")),
						entry("energy", 1500L, Instant.parse("2026-04-04T12:00:00Z"))));

		ResponseEntity<ImportSummaryDto> response = restTemplate.postForEntity("/api/import?mode=replace",
				file, ImportSummaryDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(new ImportSummaryDto(3, 1, 2));

		// The re-export IS the round-trip proof: same metrics, items, entries.
		ExportDto reExported = export();
		assertThat(reExported.metrics()).isEqualTo(file.metrics());
		assertThat(reExported.items()).isEqualTo(file.items());
		assertThat(reExported.entries()).isEqualTo(file.entries());
	}

	@Test
	void formatVersion3RoundTripsGroupedEntries() {
		UUID group = UUID.randomUUID();
		// Grouped pair first (shared loggedAt; file order = save order = the
		// id order the re-export sorts by), ad-hoc entry after.
		ExportDto file = new ExportDto(3, Instant.parse("2026-04-04T20:00:00Z"),
				List.of(WATER, new ExportMetricDto("energy", "kJ")), List.of(),
				List.of(new ExportEntryDto("energy", 1141L, Instant.parse("2026-04-04T09:00:00Z"),
								"protein bar – half bar ×2", group),
						new ExportEntryDto("water", 100L, Instant.parse("2026-04-04T09:00:00Z"),
								"protein bar – half bar ×2", group),
						entry("water", 250L, Instant.parse("2026-04-04T10:00:00Z"))));

		ResponseEntity<ImportSummaryDto> response = restTemplate.postForEntity("/api/import?mode=replace",
				file, ImportSummaryDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(new ImportSummaryDto(2, 0, 3));

		ExportDto reExported = export();
		assertThat(reExported.entries()).isEqualTo(file.entries());
	}

	@Test
	void importedEntriesFollowRenumberedMetricIds() {
		importReplacing(validFile());

		List<MetricDto> metrics = restTemplate
				.exchange("/api/metrics", HttpMethod.GET, null, new ParameterizedTypeReference<List<MetricDto>>() {
				})
				.getBody();
		assertThat(metrics).hasSize(1);
		MetricDto water = metrics.get(0);
		assertThat(water.name()).isEqualTo("water");

		// The day view joins entries to metrics by id — if import had kept the
		// file's implicit ids instead of the freshly generated ones, this join
		// would come back empty.
		DayViewDto day = restTemplate.getForEntity("/api/days/2026-04-04", DayViewDto.class).getBody();
		assertThat(day).isNotNull();
		assertThat(day.totals()).containsExactly(new TotalDto(water.id(), "water", "mL", 250));
	}

	@Test
	void formatVersion1FilesAreStillAccepted() {
		// The backup taken the day before this build shipped must restore —
		// otherwise the recovery story has a gap exactly at upgrade time
		// (ADR-0007). Version 1 files have no items key at all.
		ResponseEntity<ImportSummaryDto> response = restTemplate.postForEntity("/api/import?mode=replace",
				v1File(), ImportSummaryDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(new ImportSummaryDto(1, 0, 1));

		ExportDto reExported = export();
		assertThat(reExported.formatVersion()).isEqualTo(3);
		assertThat(reExported.metrics()).isEqualTo(List.of(WATER));
		assertThat(reExported.items()).isEmpty();
		assertThat(reExported.entries())
				.isEqualTo(List.of(entry("water", 250L, Instant.parse("2026-04-04T08:00:00Z"))));
	}

	@Test
	void formatVersion1ImportReplacesItemsToo() {
		// A restore is a restore: the file IS the database, and a v1 file
		// holds no items (ADR-0007).
		importReplacing(new ExportDto(3, null, List.of(WATER),
				List.of(new ExportItemDto("doomed item", 100L, "g",
						List.of(new ExportItemAmountDto("water", 100L)), List.of())),
				List.of()));
		assertThat(export().items()).hasSize(1);

		importReplacing(v1File());

		assertThat(export().items()).isEmpty();
	}

	@Test
	void formatVersion1FilesCarryingItemsAreRejected() {
		ExportDto before = export();

		ExportDto file = new ExportDto(1, null, List.of(WATER),
				List.of(new ExportItemDto("smuggled", 100L, "g",
						List.of(new ExportItemAmountDto("water", 100L)), List.of())),
				List.of());
		ResponseEntity<String> response = restTemplate.postForEntity("/api/import?mode=replace", file,
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("formatVersion 1 files cannot carry items");
		assertUnchanged(before);
	}

	@Test
	void olderFilesCarryingEntryLabelsAreRejected() {
		ExportDto before = export();

		ExportEntryDto labeled = new ExportEntryDto("water", 100L, Instant.parse("2026-04-04T09:00:00Z"),
				"smuggled label", UUID.randomUUID());
		ExportDto v1 = new ExportDto(1, null, List.of(WATER), null, List.of(labeled));
		ExportDto v2 = new ExportDto(2, null, List.of(WATER), List.of(), List.of(labeled));

		for (ExportDto file : new ExportDto[] { v1, v2 }) {
			ResponseEntity<String> response = restTemplate.postForEntity("/api/import?mode=replace", file,
					String.class);
			assertThat(response.getStatusCode()).as("formatVersion = %s", file.formatVersion())
					.isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody()).contains("cannot carry entry labels or groups");
		}
		assertUnchanged(before);
	}

	@Test
	void entryLabelAndGroupMustComeTogetherAndNonBlank() {
		ExportDto before = export();

		ExportEntryDto labelOnly = new ExportEntryDto("water", 100L, Instant.parse("2026-04-04T09:00:00Z"),
				"label without group", null);
		ExportEntryDto groupOnly = new ExportEntryDto("water", 100L, Instant.parse("2026-04-04T09:00:00Z"),
				null, UUID.randomUUID());
		ExportEntryDto blankLabel = new ExportEntryDto("water", 100L, Instant.parse("2026-04-04T09:00:00Z"),
				" ", UUID.randomUUID());

		for (ExportEntryDto broken : new ExportEntryDto[] { labelOnly, groupOnly, blankLabel }) {
			ExportDto file = new ExportDto(3, null, List.of(WATER), List.of(), List.of(broken));
			assertThat(restTemplate.postForEntity("/api/import?mode=replace", file, String.class)
					.getStatusCode()).as("label = %s, group = %s", broken.label(), broken.group())
					.isEqualTo(HttpStatus.BAD_REQUEST);
		}
		assertUnchanged(before);
	}

	@Test
	void groupMembersMustAgreeOnLabelAndLoggedAt() {
		ExportDto before = export();

		UUID group = UUID.randomUUID();
		ExportEntryDto member = new ExportEntryDto("water", 100L, Instant.parse("2026-04-04T09:00:00Z"),
				"one action", group);
		ExportEntryDto otherLabel = new ExportEntryDto("water", 100L, Instant.parse("2026-04-04T09:00:00Z"),
				"another action", group);
		ExportEntryDto otherTime = new ExportEntryDto("water", 100L, Instant.parse("2026-04-05T09:00:00Z"),
				"one action", group);

		for (ExportEntryDto broken : new ExportEntryDto[] { otherLabel, otherTime }) {
			ExportDto file = new ExportDto(3, null, List.of(WATER), List.of(), List.of(member, broken));
			ResponseEntity<String> response = restTemplate.postForEntity("/api/import?mode=replace", file,
					String.class);
			assertThat(response.getStatusCode()).as("label = %s, loggedAt = %s", broken.label(), broken.loggedAt())
					.isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody()).contains("disagree on label or loggedAt");
		}
		assertUnchanged(before);
	}

	@Test
	void formatVersion2FilesRequireTheItemsList() {
		ExportDto before = export();

		for (int version : new int[] { 2, 3 }) {
			ResponseEntity<String> response = restTemplate.postForEntity("/api/import?mode=replace",
					new ExportDto(version, null, List.of(WATER), null, List.of()), String.class);

			assertThat(response.getStatusCode()).as("formatVersion = %s", version)
					.isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody()).contains("formatVersion " + version + " requires an items list");
		}
		assertUnchanged(before);
	}

	@Test
	void importWithoutExplicitReplaceModeIsRejected() {
		ExportDto before = export();

		assertThat(restTemplate.postForEntity("/api/import", validFile(), String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(restTemplate.postForEntity("/api/import?mode=merge", validFile(), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		assertUnchanged(before);
	}

	@Test
	void unsupportedFormatVersionIsRejectedWithTheReason() {
		ExportDto before = export();

		for (int version : new int[] { 0, 4 }) {
			ResponseEntity<String> response = restTemplate.postForEntity("/api/import?mode=replace",
					new ExportDto(version, null, List.of(WATER), List.of(), List.of()), String.class);
			assertThat(response.getStatusCode()).as("formatVersion = %s", version)
					.isEqualTo(HttpStatus.BAD_REQUEST);
			// include-message=always: on a failing restore the reason IS the
			// diagnosis — a bare 400 would leave the user guessing.
			assertThat(response.getBody()).contains("unsupported formatVersion " + version);
		}
		assertThat(restTemplate.postForEntity("/api/import?mode=replace",
				new ExportDto(null, null, List.of(WATER), List.of(), List.of()), String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);

		assertUnchanged(before);
	}

	@Test
	void emptyMetricsAreRejectedEvenWhenWellFormed() {
		importReplacing(validFile());
		ExportDto before = export();

		// Well-formed, passes every other validation — but applying it would
		// leave a database no import-free gesture can log anything in.
		ExportDto file = new ExportDto(3, null, List.of(), List.of(), List.of());
		assertThat(restTemplate.postForEntity("/api/import?mode=replace", file, String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);

		assertUnchanged(before);
	}

	@Test
	void importIsAtomicWhenAnEntryReferencesAnUnknownMetric() {
		importReplacing(validFile());
		ExportDto before = export();

		ExportDto file = new ExportDto(3, null, List.of(WATER), List.of(),
				List.of(entry("water", 100L, Instant.parse("2026-04-04T09:00:00Z")),
						entry("caffeine", 80L, Instant.parse("2026-04-04T09:30:00Z"))));
		ResponseEntity<String> response = restTemplate.postForEntity("/api/import?mode=replace", file,
				String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("unknown metric: caffeine");

		// Nothing was deleted on the way to the rejection.
		assertUnchanged(before);
	}

	@Test
	void importIsAtomicWhenAnItemIsInvalid() {
		importReplacing(validFile());
		ExportDto before = export();

		ExportDto unknownMetric = new ExportDto(3, null, List.of(WATER),
				List.of(new ExportItemDto("bar", 100L, "g",
						List.of(new ExportItemAmountDto("caffeine", 80L)), List.of())),
				List.of());
		ResponseEntity<String> unknownResponse = restTemplate.postForEntity("/api/import?mode=replace",
				unknownMetric, String.class);
		assertThat(unknownResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(unknownResponse.getBody()).contains("item 'bar' references unknown metric: caffeine");

		ExportDto duplicateItems = new ExportDto(3, null, List.of(WATER),
				List.of(itemOf("twin"), itemOf("twin")), List.of());
		assertThat(restTemplate.postForEntity("/api/import?mode=replace", duplicateItems, String.class)
				.getBody()).contains("duplicate item name: twin");

		ExportDto duplicateAmountMetric = new ExportDto(3, null, List.of(WATER),
				List.of(new ExportItemDto("bar", 100L, "g",
						List.of(new ExportItemAmountDto("water", 100L), new ExportItemAmountDto("water", 200L)),
						List.of())),
				List.of());
		assertThat(restTemplate.postForEntity("/api/import?mode=replace", duplicateAmountMetric, String.class)
				.getBody()).contains("item 'bar' lists metric more than once: water");

		ExportDto duplicateServings = new ExportDto(3, null, List.of(WATER),
				List.of(new ExportItemDto("bar", 100L, "g", List.of(new ExportItemAmountDto("water", 100L)),
						List.of(new ExportServingDto("glass", 250L), new ExportServingDto("glass", 100L)))),
				List.of());
		assertThat(restTemplate.postForEntity("/api/import?mode=replace", duplicateServings, String.class)
				.getBody()).contains("item 'bar' has duplicate serving name: glass");

		assertUnchanged(before);
	}

	@Test
	void duplicateMetricNamesAreRejected() {
		ExportDto file = new ExportDto(3, null, List.of(WATER, new ExportMetricDto("water", "L")), List.of(),
				List.of());

		assertThat(restTemplate.postForEntity("/api/import?mode=replace", file, String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedFilesAreRejectedByValidation() {
		ExportDto blankMetricName = new ExportDto(3, null, List.of(new ExportMetricDto(" ", "mL")), List.of(),
				List.of());
		ExportDto nonPositiveAmount = new ExportDto(3, null, List.of(WATER), List.of(),
				List.of(entry("water", 0L, Instant.parse("2026-04-04T09:00:00Z"))));
		ExportDto missingLoggedAt = new ExportDto(3, null, List.of(WATER), List.of(),
				List.of(entry("water", 100L, null)));
		ExportDto missingLists = new ExportDto(3, null, null, null, null);
		ExportDto blankItemName = new ExportDto(3, null, List.of(WATER), List.of(itemOf(" ")), List.of());
		ExportDto emptyItemAmounts = new ExportDto(3, null, List.of(WATER),
				List.of(new ExportItemDto("bar", 100L, "g", List.of(), List.of())), List.of());
		ExportDto nonPositiveServing = new ExportDto(3, null, List.of(WATER),
				List.of(new ExportItemDto("bar", 100L, "g", List.of(new ExportItemAmountDto("water", 100L)),
						List.of(new ExportServingDto("glass", 0L)))),
				List.of());

		for (ExportDto file : new ExportDto[] { blankMetricName, nonPositiveAmount, missingLoggedAt,
				missingLists, blankItemName, emptyItemAmounts, nonPositiveServing }) {
			assertThat(restTemplate.postForEntity("/api/import?mode=replace", file, String.class)
					.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

	/** A minimal valid file; always contains the water metric (shared DB, see class comment). */
	private ExportDto validFile() {
		return new ExportDto(3, Instant.parse("2026-04-04T20:00:00Z"), List.of(WATER), List.of(),
				List.of(entry("water", 250L, Instant.parse("2026-04-04T08:00:00Z"))));
	}

	/** What a real pre-items backup looks like: formatVersion 1, no items key. */
	private ExportDto v1File() {
		return new ExportDto(1, Instant.parse("2026-04-04T20:00:00Z"), List.of(WATER), null,
				List.of(entry("water", 250L, Instant.parse("2026-04-04T08:00:00Z"))));
	}

	/** An ad-hoc entry: no label, no group (the formatVersion ≤ 2 shape). */
	private static ExportEntryDto entry(String metric, Long amount, Instant loggedAt) {
		return new ExportEntryDto(metric, amount, loggedAt, null, null);
	}

	/** A minimal well-formed item referencing only the water metric. */
	private static ExportItemDto itemOf(String name) {
		return new ExportItemDto(name, 100L, "g", List.of(new ExportItemAmountDto("water", 100L)), List.of());
	}

	private void importReplacing(ExportDto file) {
		ResponseEntity<ImportSummaryDto> response = restTemplate.postForEntity("/api/import?mode=replace",
				file, ImportSummaryDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	private ExportDto export() {
		ResponseEntity<ExportDto> response = restTemplate.getForEntity("/api/export", ExportDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private void assertUnchanged(ExportDto before) {
		ExportDto after = export();
		assertThat(after.metrics()).isEqualTo(before.metrics());
		assertThat(after.items()).isEqualTo(before.items());
		assertThat(after.entries()).isEqualTo(before.entries());
	}

}
