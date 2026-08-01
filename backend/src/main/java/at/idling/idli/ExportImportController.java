package at.idling.idli;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api")
public class ExportImportController {

	private final ExportImportService exportImportService;
	private final ZoneId zone;

	public ExportImportController(ExportImportService exportImportService,
			@Value("${idli.zone:Europe/Vienna}") String zone) {
		this.exportImportService = exportImportService;
		this.zone = ZoneId.of(zone);
	}

	// Content-Disposition makes a plain browser hit save a dated file; the
	// SPA reads the filename from the header so both paths name it the same.
	@GetMapping("/export")
	public ResponseEntity<ExportDto> export() {
		ExportDto export = exportImportService.export();
		String filename = "idli-export-" + LocalDate.ofInstant(export.exportedAt(), zone) + ".json";
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(filename).build().toString())
				.body(export);
	}

	// mode=replace is a required, explicit acknowledgement that import
	// REPLACES the whole database (a restore, not a merge — ADR-0004).
	// Leaving it out is a 400, so nothing destructive happens by default.
	@PostMapping("/import")
	public ImportSummaryDto importData(@RequestParam String mode, @Valid @RequestBody ExportDto file) {
		if (!"replace".equals(mode)) {
			throw new InvalidImportException("unsupported mode '" + mode
					+ "': import replaces the entire database; call with mode=replace to confirm");
		}
		return exportImportService.importReplacing(file);
	}

}
