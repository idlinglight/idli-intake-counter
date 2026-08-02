package at.idling.idli;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class MetricController {

	private final IntakeService intakeService;
	private final AuthoringService authoringService;

	public MetricController(IntakeService intakeService, AuthoringService authoringService) {
		this.intakeService = intakeService;
		this.authoringService = authoringService;
	}

	@GetMapping("/metrics")
	public List<MetricDto> metrics() {
		return intakeService.metrics();
	}

	@PostMapping("/metrics")
	@ResponseStatus(HttpStatus.CREATED)
	public MetricDto createMetric(@Valid @RequestBody NewMetricRequest request) {
		return authoringService.createMetric(request);
	}

}
