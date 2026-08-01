package at.idling.idli;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

	private final String gitSha;

	public HelloController(@Value("${GIT_SHA:dev}") String gitSha) {
		this.gitSha = gitSha;
	}

	@GetMapping("/hello")
	public Map<String, String> hello() {
		return Map.of("message", "idli", "gitSha", gitSha);
	}

}
