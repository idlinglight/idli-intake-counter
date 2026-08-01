package at.idling.idli;

import org.springframework.boot.SpringApplication;

public class TestIdliBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(IdliBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
