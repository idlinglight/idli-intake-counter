package at.idling.idli;

import org.springframework.boot.SpringApplication;

public class TestIdliBackendApplication {

	public static void main(String[] args) {
		// Local-dev parity with the test suite: log in as user/idli-test-password
		// unless a real hash is supplied via IDLI_AUTH_PASSWORD_HASH.
		if (System.getenv("IDLI_AUTH_PASSWORD_HASH") == null
				&& System.getProperty("idli.auth.password-hash") == null) {
			System.setProperty("idli.auth.password-hash", TestAuth.PASSWORD_HASH);
		}
		SpringApplication.from(IdliBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
