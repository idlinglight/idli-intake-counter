package at.idling.idli;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = TestAuth.PASSWORD_HASH_PROPERTY)
class IdliBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
