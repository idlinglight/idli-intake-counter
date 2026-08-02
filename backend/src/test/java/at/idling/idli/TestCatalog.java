package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * Shared catalog-creation helpers for the flow tests (same idea as
 * {@link TestAuth}): one copy of the create-and-assert boilerplate, so a
 * changed request shape or status code is fixed in one place.
 */
final class TestCatalog {

	private TestCatalog() {
	}

	static MetricDto createMetric(TestRestTemplate restTemplate, String name, String canonicalUnit) {
		ResponseEntity<MetricDto> response = restTemplate.postForEntity("/api/metrics",
				new NewMetricRequest(name, canonicalUnit), MetricDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	static ItemDto createItem(TestRestTemplate restTemplate, String name, Long basisAmount, String basisUnit,
			List<ItemAmountDto> amounts) {
		ResponseEntity<ItemDto> response = restTemplate.postForEntity("/api/items",
				new ItemRequest(name, basisAmount, basisUnit, amounts), ItemDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

	static ServingDto addServing(TestRestTemplate restTemplate, long itemId, String name, Long quantity) {
		ResponseEntity<ServingDto> response = restTemplate.postForEntity("/api/items/" + itemId + "/servings",
				new ServingRequest(name, quantity), ServingDto.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		return response.getBody();
	}

}
