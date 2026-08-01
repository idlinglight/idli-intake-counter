package at.idling.idli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

	public record HelloResponse(String message, String gitSha) {
	}

	private final String gitSha;

	public HelloController(@Value("${GIT_SHA:dev}") String gitSha) {
		this.gitSha = gitSha;
	}

	@GetMapping("/hello")
	public HelloResponse hello() {
		return new HelloResponse("idli", gitSha);
	}

}
