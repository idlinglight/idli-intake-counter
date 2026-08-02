package at.idling.idli;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
public class ExportImportController {

	// Minute granularity: same-day exports get distinct filenames, so copying
	// several into one archive never collides. Colon-free for filesystems.
	private static final DateTimeFormatter EXPORT_FILENAME_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

	// Lowercase constant so wire value, contract enum, and code read the same.
	public enum ImportMode {
		replace
	}

	private final ExportImportService exportImportService;
	private final ZoneId zone;

	public ExportImportController(ExportImportService exportImportService, ZoneId zone) {
		this.exportImportService = exportImportService;
		this.zone = zone;
	}

	// Content-Disposition makes a plain browser hit save a dated file; the
	// SPA reads the filename from the header so both paths name it the same.
	@GetMapping("/export")
	public ResponseEntity<ExportDto> export() {
		ExportDto export = exportImportService.export();
		String filename = "idli-export-"
				+ ZonedDateTime.ofInstant(export.exportedAt(), zone).format(EXPORT_FILENAME_STAMP) + ".json";
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(filename).build().toString())
				.body(export);
	}

	// mode=replace is a required, explicit acknowledgement that import
	// REPLACES the whole database (a restore, not a merge — ADR-0004). As an
	// enum it lives in the machine-checked contract: any other value — or no
	// value — is a 400 before this handler runs, and generated clients can
	// only ever say "replace" knowingly.
	@PostMapping("/import")
	public ImportSummaryDto importData(@RequestParam ImportMode mode, @Valid @RequestBody ExportDto file) {
		return exportImportService.importReplacing(file);
	}

}
