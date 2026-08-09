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

import java.util.UUID;

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

	// Logging a serving creates a GROUP of entries (one per composition
	// metric), so it lives beside the entry endpoints, not the catalog ones.
	@PostMapping("/servings/{id}/entries")
	@ResponseStatus(HttpStatus.CREATED)
	public EntryGroupDto createEntryGroup(@PathVariable long id, @Valid @RequestBody NewEntryGroupRequest request) {
		return intakeService.logServing(id, request);
	}

	// The ad-hoc sibling of the serving endpoint: same snapshot semantics, but
	// the quantity is typed at log time instead of coming from an authored
	// serving (issue #15).
	@PostMapping("/items/{id}/entries")
	@ResponseStatus(HttpStatus.CREATED)
	public EntryGroupDto createItemEntryGroup(@PathVariable long id, @Valid @RequestBody NewItemEntryRequest request) {
		return intakeService.logItem(id, request);
	}

	// UUID path variable: distinct from the numeric /entries/{id} space, and a
	// malformed id is a 400 before the handler runs.
	@DeleteMapping("/entry-groups/{groupId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteEntryGroup(@PathVariable UUID groupId) {
		intakeService.deleteGroup(groupId);
	}

}
