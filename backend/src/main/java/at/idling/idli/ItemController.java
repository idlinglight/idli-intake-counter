package at.idling.idli;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ItemController {

	private final AuthoringService authoringService;

	public ItemController(AuthoringService authoringService) {
		this.authoringService = authoringService;
	}

	@GetMapping("/items")
	public List<ItemDto> items() {
		return authoringService.items();
	}

	@PostMapping("/items")
	@ResponseStatus(HttpStatus.CREATED)
	public ItemDto createItem(@Valid @RequestBody ItemRequest request) {
		return authoringService.createItem(request);
	}

	@PutMapping("/items/{id}")
	public ItemDto replaceItem(@PathVariable long id, @Valid @RequestBody ItemRequest request) {
		return authoringService.replaceItem(id, request);
	}

	@DeleteMapping("/items/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteItem(@PathVariable long id) {
		authoringService.deleteItem(id);
	}

	@PostMapping("/items/{id}/servings")
	@ResponseStatus(HttpStatus.CREATED)
	public ServingDto addServing(@PathVariable long id, @Valid @RequestBody ServingRequest request) {
		return authoringService.addServing(id, request);
	}

	@PutMapping("/servings/{id}")
	public ServingDto replaceServing(@PathVariable long id, @Valid @RequestBody ServingRequest request) {
		return authoringService.replaceServing(id, request);
	}

	@DeleteMapping("/servings/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteServing(@PathVariable long id) {
		authoringService.deleteServing(id);
	}

}
