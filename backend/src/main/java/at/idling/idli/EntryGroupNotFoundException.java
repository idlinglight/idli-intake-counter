package at.idling.idli;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class EntryGroupNotFoundException extends RuntimeException {

	public EntryGroupNotFoundException(UUID groupId) {
		super("no entry group with id: " + groupId);
	}

}
