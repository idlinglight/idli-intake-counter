package at.idling.idli;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EntryController {

	private final IntakeService intakeService;

	public EntryController(IntakeService intakeService) {
		this.intakeService = intakeService;
	}

	@PostMapping("/entries")
	@ResponseStatus(HttpStatus.CREATED)
	// Entity-qualified method names, deliberately: springdoc derives operationIds
	// from them and collision-suffixes duplicates (create_2, delete_1) in scan
	// order, silently reshuffling the contract whenever a controller is added.
	public EntryDto createEntry(@Valid @RequestBody NewEntryRequest request) {
		return intakeService.log(request);
	}

	@DeleteMapping("/entries/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteEntry(@PathVariable long id) {
		intakeService.delete(id);
	}

}
