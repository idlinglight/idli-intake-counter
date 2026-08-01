package at.idling.idli;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Fixed server url: the SPA consumes the API same-origin, and without this
// springdoc embeds the serving host:port — nondeterministic under test
// (random port), which breaks the committed-contract diff.
@OpenAPIDefinition(servers = { @Server(url = "/") })
@SpringBootApplication
public class IdliBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(IdliBackendApplication.class, args);
	}

}
