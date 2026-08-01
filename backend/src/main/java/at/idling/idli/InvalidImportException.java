package at.idling.idli;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidImportException extends RuntimeException {

	public InvalidImportException(String message) {
		super(message);
	}

}
