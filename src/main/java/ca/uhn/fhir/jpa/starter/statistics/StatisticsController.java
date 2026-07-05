package ca.uhn.fhir.jpa.starter.statistics;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller exposing endpoints for FHIR resource statistics.
 * Provides aggregated counts and groupings for DiagnosticReport and ImagingStudy resources.
 */
@RestController
@RequestMapping(path = "/api/statistics", produces = MediaType.APPLICATION_JSON_VALUE)
public class StatisticsController {

	private final StatisticsService statisticsService;

	public StatisticsController(StatisticsService statisticsService) {
		this.statisticsService = statisticsService;
	}

	/**
	 * GET /api/statistics/diagnostic-report/by-status
	 * Returns count of DiagnosticReport resources grouped by status.
	 *
	 * @return StatisticsDTO with status distribution
	 */
	@GetMapping("/diagnostic-report/by-status")
	public StatisticsDTO getDiagnosticReportByStatus() {
		return statisticsService.getDiagnosticReportsByStatus();
	}

	/**
	 * GET /api/statistics/diagnostic-report/by-category
	 * Returns count of DiagnosticReport resources grouped by category.
	 *
	 * @return StatisticsDTO with category distribution
	 */
	@GetMapping("/diagnostic-report/by-category")
	public StatisticsDTO getDiagnosticReportByCategory() {
		return statisticsService.getDiagnosticReportsByCategory();
	}

	/**
	 * GET /api/statistics/imaging-study/by-category
	 * Returns count of ImagingStudy resources grouped by category (modality).
	 *
	 * @return StatisticsDTO with modality/category distribution
	 */
	@GetMapping("/imaging-study/by-category")
	public StatisticsDTO getImagingStudyByCategory() {
		return statisticsService.getImagingStudyByCategory();
	}

	/**
	 * GET /api/statistics/imaging-study/by-status
	 * Returns count of ImagingStudy resources grouped by status.
	 *
	 * @return StatisticsDTO with status distribution
	 */
	@GetMapping("/imaging-study/by-status")
	public StatisticsDTO getImagingStudyByStatus() {
		return statisticsService.getImagingStudyByStatus();
	}
}
