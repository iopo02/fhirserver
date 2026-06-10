package ca.uhn.fhir.jpa.starter.notification;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

@RestController
public class DashboardController {

	private final ResourceLoader resourceLoader;

	public DashboardController(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> getDashboard() {
		try {
			Resource resource = resourceLoader.getResource("classpath:templates/dashboard.html");
			String html = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
			return ResponseEntity.ok(html);
		} catch (Exception e) {
			return ResponseEntity.status(500).body("Error loading dashboard template: " + e.getMessage());
		}
	}
}
