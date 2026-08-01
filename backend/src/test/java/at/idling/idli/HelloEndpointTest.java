package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HelloEndpointTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void helloReturnsIdli() {
		ResponseEntity<Map<String, String>> response = restTemplate.exchange(
				RequestEntity.method(HttpMethod.GET, URI.create("/api/hello")).build(),
				new ParameterizedTypeReference<Map<String, String>>() {
				});

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().get("message")).isEqualTo("idli");
		assertThat(response.getBody().get("gitSha")).isNotBlank();
	}

	@Test
	void openApiDocsAreServed() throws IOException {
		ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("/api/hello");

		// Exported for the committed contract at api/openapi.json:
		// scripts/update-api-contract.sh copies it, CI diffs against it.
		Files.writeString(Path.of("target/openapi.json"), response.getBody());
	}

}
