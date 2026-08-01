package at.idling.idli;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class MetricController {

	private final IntakeService intakeService;

	public MetricController(IntakeService intakeService) {
		this.intakeService = intakeService;
	}

	@GetMapping("/metrics")
	public List<MetricDto> metrics() {
		return intakeService.metrics();
	}

}
