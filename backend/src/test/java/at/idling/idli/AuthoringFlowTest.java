package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * Exercises catalog authoring (metrics, items, servings) over real HTTP.
 *
 * The DB is shared with the other flow-test classes (cached context) and the
 * import tests wipe it wholesale, so every method here creates exactly what it
 * asserts, prefixes all names with "authoring-" (metric and item names are
 * globally unique), and never relies on rows from other methods or classes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = TestAuth.PASSWORD_HASH_PROPERTY)
@AutoConfigureTestRestTemplate
class AuthoringFlowTest {

	private TestRestTemplate plainTemplate;
	private TestRestTemplate restTemplate;

	@Autowired
	void setRestTemplate(TestRestTemplate restTemplate) {
		this.plainTemplate = restTemplate;
		this.restTemplate = restTemplate.withBasicAuth(TestAuth.USERNAME, TestAuth.PASSWORD);
	}

	@Test
	void authoringRequiresAuthentication() {
		assertThat(plainTemplate.getForEntity("/api/items", String.class).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(plainTemplate
				.postForEntity("/api/metrics", new NewMetricRequest("authoring-anon", "mg"), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void createdMetricAppearsInTheList() {
		ResponseEntity<MetricDto> response = restTemplate.postForEntity("/api/metrics",
				new NewMetricRequest("authoring-caffeine", "mg"), MetricDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		MetricDto created = response.getBody();
		assertThat(created).isNotNull();
		assertThat(created.name()).isEqualTo("authoring-caffeine");
		assertThat(created.canonicalUnit()).isEqualTo("mg");

		List<MetricDto> metrics = restTemplate
				.exchange("/api/metrics", HttpMethod.GET, null, new ParameterizedTypeReference<List<MetricDto>>() {
				}).getBody();
		assertThat(metrics).contains(created);
	}

	@Test
	void duplicateMetricNameIsRejected() {
		createMetric("authoring-dup-metric", "mg");

		ResponseEntity<String> response = restTemplate.postForEntity("/api/metrics",
				new NewMetricRequest("authoring-dup-metric", "g"), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).contains("name already taken: authoring-dup-metric");
	}

	@Test
	void blankMetricFieldsAreRejected() {
		assertThat(restTemplate.postForEntity("/api/metrics", new NewMetricRequest(" ", "mg"), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(restTemplate
				.postForEntity("/api/metrics", new NewMetricRequest("authoring-blank-unit", " "), String.class)
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void itemCreateCarriesLabelDataVerbatim() {
		// The protein-bar user story: label says "per 100 g", values enter as
		// printed — no per-serving arithmetic at authoring time (ADR-0008).
		MetricDto energy = createMetric("authoring-bar-energy", "kJ");
		MetricDto protein = createMetric("authoring-bar-protein", "g");

		ResponseEntity<ItemDto> response = restTemplate.postForEntity("/api/items",
				new ItemRequest("authoring-protein-bar", 100L, "g",
						List.of(new ItemAmountDto(protein.id(), 30L), new ItemAmountDto(energy.id(), 2281L))),
				ItemDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		ItemDto created = response.getBody();
		assertThat(created).isNotNull();
		assertThat(created.name()).isEqualTo("authoring-protein-bar");
		assertThat(created.basisAmount()).isEqualTo(100L);
		assertThat(created.basisUnit()).isEqualTo("g");
		// Amounts come back sorted by metricId regardless of request order.
		assertThat(created.amounts()).containsExactly(new ItemAmountDto(energy.id(), 2281L),
				new ItemAmountDto(protein.id(), 30L));
		assertThat(created.servings()).isEmpty();

		assertThat(items()).contains(created);
	}

	@Test
	void duplicateItemNameIsRejected() {
		MetricDto metric = createMetric("authoring-m6", "kJ");
		createItem("authoring-item-dup", 100L, "g", List.of(new ItemAmountDto(metric.id(), 100L)));

		ResponseEntity<String> response = restTemplate.postForEntity("/api/items",
				new ItemRequest("authoring-item-dup", 1L, "piece", List.of(new ItemAmountDto(metric.id(), 1L))),
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).contains("name already taken: authoring-item-dup");
	}

	@Test
	void invalidItemPayloadsAreRejected() {
		MetricDto metric = createMetric("authoring-m7", "kJ");

		ItemRequest unknownMetric = new ItemRequest("authoring-item-7a", 100L, "g",
				List.of(new ItemAmountDto(999999L, 100L)));
		ResponseEntity<String> unknownResponse = restTemplate.postForEntity("/api/items", unknownMetric,
				String.class);
		assertThat(unknownResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(unknownResponse.getBody()).contains("unknown metric id: 999999");

		ItemRequest duplicateMetric = new ItemRequest("authoring-item-7b", 100L, "g",
				List.of(new ItemAmountDto(metric.id(), 100L), new ItemAmountDto(metric.id(), 200L)));
		ResponseEntity<String> duplicateResponse = restTemplate.postForEntity("/api/items", duplicateMetric,
				String.class);
		assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(duplicateResponse.getBody()).contains("duplicate metric id in amounts");

		ItemRequest emptyAmounts = new ItemRequest("authoring-item-7c", 100L, "g", List.of());
		ItemRequest blankBasisUnit = new ItemRequest("authoring-item-7d", 100L, " ",
				List.of(new ItemAmountDto(metric.id(), 100L)));
		ItemRequest nonPositiveBasis = new ItemRequest("authoring-item-7e", 0L, "g",
				List.of(new ItemAmountDto(metric.id(), 100L)));
		ItemRequest nonPositiveAmount = new ItemRequest("authoring-item-7f", 100L, "g",
				List.of(new ItemAmountDto(metric.id(), 0L)));
		for (ItemRequest request : new ItemRequest[] { emptyAmounts, blankBasisUnit, nonPositiveBasis,
				nonPositiveAmount }) {
			assertThat(restTemplate.postForEntity("/api/items", request, String.class).getStatusCode())
					.as("item %s", request.name()).isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

	@Test
	void itemReplaceUpdatesNameBasisAndAmountsButKeepsServings() {
		MetricDto first = createMetric("authoring-m8a", "kJ");
		MetricDto second = createMetric("authoring-m8b", "g");
		ItemDto item = createItem("authoring-item-replace", 100L, "g",
				List.of(new ItemAmountDto(first.id(), 100L)));
		ServingDto serving = addServing(item.id(), "authoring-half-8", 25L);

		ResponseEntity<ItemDto> response = restTemplate.exchange("/api/items/" + item.id(), HttpMethod.PUT,
				new HttpEntity<>(new ItemRequest("authoring-item-replaced", 50L, "mL",
						List.of(new ItemAmountDto(first.id(), 200L), new ItemAmountDto(second.id(), 5L)))),
				ItemDto.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		ItemDto replaced = response.getBody();
		assertThat(replaced).isNotNull();
		assertThat(replaced.name()).isEqualTo("authoring-item-replaced");
		assertThat(replaced.basisAmount()).isEqualTo(50L);
		assertThat(replaced.basisUnit()).isEqualTo("mL");
		assertThat(replaced.amounts()).containsExactly(new ItemAmountDto(first.id(), 200L),
				new ItemAmountDto(second.id(), 5L));
		assertThat(replaced.servings()).containsExactly(serving);

		// Shrinking the composition replaces, not merges.
		ItemDto shrunk = restTemplate.exchange("/api/items/" + item.id(), HttpMethod.PUT,
				new HttpEntity<>(new ItemRequest("authoring-item-replaced", 50L, "mL",
						List.of(new ItemAmountDto(second.id(), 7L)))),
				ItemDto.class).getBody();
		assertThat(shrunk).isNotNull();
		assertThat(shrunk.amounts()).containsExactly(new ItemAmountDto(second.id(), 7L));
	}

	@Test
	void itemRenameToTakenNameIsRejected() {
		MetricDto metric = createMetric("authoring-m9", "kJ");
		createItem("authoring-item-9a", 100L, "g", List.of(new ItemAmountDto(metric.id(), 100L)));
		ItemDto other = createItem("authoring-item-9b", 100L, "g", List.of(new ItemAmountDto(metric.id(), 100L)));

		ResponseEntity<String> response = restTemplate.exchange("/api/items/" + other.id(), HttpMethod.PUT,
				new HttpEntity<>(new ItemRequest("authoring-item-9a", 100L, "g",
						List.of(new ItemAmountDto(metric.id(), 100L)))),
				String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// Keeping one's own name is not a conflict.
		ResponseEntity<ItemDto> keepOwnName = restTemplate.exchange("/api/items/" + other.id(), HttpMethod.PUT,
				new HttpEntity<>(new ItemRequest("authoring-item-9b", 100L, "g",
						List.of(new ItemAmountDto(metric.id(), 100L)))),
				ItemDto.class);
		assertThat(keepOwnName.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void itemDeleteCascadesToAmountsAndServings() {
		MetricDto metric = createMetric("authoring-m10", "kJ");
		ItemDto item = createItem("authoring-item-del", 100L, "g", List.of(new ItemAmountDto(metric.id(), 100L)));
		addServing(item.id(), "authoring-whole-10", 50L);

		assertThat(restTemplate.exchange("/api/items/" + item.id(), HttpMethod.DELETE, null, Void.class)
				.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(items()).noneMatch(candidate -> candidate.id() == item.id());
		// Honest 404 on a repeat delete — the row is already gone.
		assertThat(restTemplate.exchange("/api/items/" + item.id(), HttpMethod.DELETE, null, String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void servingLifecycle() {
		MetricDto metric = createMetric("authoring-m11", "kJ");
		ItemDto item = createItem("authoring-item-servings", 100L, "g",
				List.of(new ItemAmountDto(metric.id(), 100L)));

		ServingDto whole = addServing(item.id(), "authoring-whole-11", 50L);
		ServingDto second = addServing(item.id(), "authoring-second-11", 25L);

		ResponseEntity<ServingDto> renamed = restTemplate.exchange("/api/servings/" + whole.id(), HttpMethod.PUT,
				new HttpEntity<>(new ServingRequest("authoring-renamed-11", 40L)), ServingDto.class);
		assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(renamed.getBody()).isEqualTo(new ServingDto(whole.id(), "authoring-renamed-11", 40L));

		ItemDto reloaded = items().stream().filter(candidate -> candidate.id() == item.id()).findFirst()
				.orElseThrow();
		assertThat(reloaded.servings()).containsExactly(renamed.getBody(), second);

		assertThat(restTemplate.exchange("/api/servings/" + second.id(), HttpMethod.DELETE, null, Void.class)
				.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(restTemplate.exchange("/api/servings/" + second.id(), HttpMethod.DELETE, null, String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void duplicateServingNameIsRejectedOnlyWithinTheItem() {
		MetricDto metric = createMetric("authoring-m12", "kJ");
		ItemDto item = createItem("authoring-item-12a", 100L, "g", List.of(new ItemAmountDto(metric.id(), 100L)));
		ItemDto other = createItem("authoring-item-12b", 100L, "g", List.of(new ItemAmountDto(metric.id(), 100L)));
		addServing(item.id(), "authoring-s12", 50L);

		ResponseEntity<String> duplicate = restTemplate.postForEntity("/api/items/" + item.id() + "/servings",
				new ServingRequest("authoring-s12", 25L), String.class);
		assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// The same name under a different item is fine — uniqueness is per item.
		assertThat(restTemplate.postForEntity("/api/items/" + other.id() + "/servings",
				new ServingRequest("authoring-s12", 25L), ServingDto.class).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void servingValidationAndUnknownIds() {
		MetricDto metric = createMetric("authoring-m13", "kJ");
		ItemDto item = createItem("authoring-item-13", 100L, "g", List.of(new ItemAmountDto(metric.id(), 100L)));

		assertThat(restTemplate.postForEntity("/api/items/" + item.id() + "/servings",
				new ServingRequest(" ", 50L), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(restTemplate.postForEntity("/api/items/" + item.id() + "/servings",
				new ServingRequest("authoring-s13", 0L), String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(restTemplate.postForEntity("/api/items/999999/servings",
				new ServingRequest("authoring-s13", 50L), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(restTemplate.exchange("/api/servings/999999", HttpMethod.PUT,
				new HttpEntity<>(new ServingRequest("authoring-s13", 50L)), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	private MetricDto createMetric(String name, String canonicalUnit) {
		ResponseEntity<MetricDto> response = restTemplate.postForEntity("/api/metrics",
				new NewMetricRequest(name, canonicalUnit), MetricDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private ItemDto createItem(String name, Long basisAmount, String basisUnit, List<ItemAmountDto> amounts) {
		ResponseEntity<ItemDto> response = restTemplate.postForEntity("/api/items",
				new ItemRequest(name, basisAmount, basisUnit, amounts), ItemDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private ServingDto addServing(long itemId, String name, Long quantity) {
		ResponseEntity<ServingDto> response = restTemplate.postForEntity("/api/items/" + itemId + "/servings",
				new ServingRequest(name, quantity), ServingDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	private List<ItemDto> items() {
		ResponseEntity<List<ItemDto>> response = restTemplate.exchange("/api/items", HttpMethod.GET, null,
				new ParameterizedTypeReference<List<ItemDto>>() {
				});
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

}
