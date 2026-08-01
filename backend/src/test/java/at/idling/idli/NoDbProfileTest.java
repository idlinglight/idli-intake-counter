package at.idling.idli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * The walking-skeleton deploy runs with the nodb profile until a database
 * exists in the cluster. Boots the app WITHOUT Testcontainers to prove
 * startup succeeds with no datasource — which also guards the Boot 4 FQCNs
 * in the profile's autoconfigure excludes, since Spring silently ignores
 * excludes that name nonexistent classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("nodb")
class NoDbProfileTest {

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void startsAndServesHelloWithoutDatabase() {
		var response = restTemplate.getForEntity("/api/hello", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("idli");
	}

}
