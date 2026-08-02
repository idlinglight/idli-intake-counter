package at.idling.idli;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ItemNotFoundException extends RuntimeException {

	public ItemNotFoundException(long itemId) {
		super("no item with id: " + itemId);
	}

}
