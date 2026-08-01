package at.idling.idli;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class DayController {

	private final IntakeService intakeService;

	public DayController(IntakeService intakeService) {
		this.intakeService = intakeService;
	}

	// The backend is the single clock: clients ask for "today" instead of
	// computing a local date that may disagree with the configured zone.
	@GetMapping("/days/today")
	public DayViewDto today() {
		return intakeService.today();
	}

	@GetMapping("/days/{date}")
	public DayViewDto day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return intakeService.day(date);
	}

}
