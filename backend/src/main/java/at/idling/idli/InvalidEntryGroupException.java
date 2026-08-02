package at.idling.idli;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A serving-log that cannot honestly be written, e.g. an amount rounding to 0. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidEntryGroupException extends RuntimeException {

	public InvalidEntryGroupException(String message) {
		super(message);
	}

}
