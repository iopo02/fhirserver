package ca.uhn.fhir.jpa.starter.statistics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service to compute statistics about FHIR resources.
 * Uses native SQL queries for performance with large datasets.
 */
@Service
public class StatisticsService {

    private static final Logger log = LoggerFactory.getLogger(StatisticsService.class);

    private final DataSource dataSource;

    public StatisticsService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Count DiagnosticReport by status.
     * Queries the search parameter index for status values.
     */
    public StatisticsDTO getDiagnosticReportsByStatus() {
        try {
            return queryResourceBySearchParam("DiagnosticReport", "status", "status");
        } catch (Exception e) {
            log.error("Error getting DiagnosticReport statistics by status", e);
            return createEmptyStatistics("DiagnosticReport", "status");
        }
    }

    /**
     * Count DiagnosticReport by category.
     * Queries the search parameter index for category values.
     */
    public StatisticsDTO getDiagnosticReportsByCategory() {
        try {
            return queryResourceBySearchParam("DiagnosticReport", "category", "category");
        } catch (Exception e) {
            log.error("Error getting DiagnosticReport statistics by category", e);
            return createEmptyStatistics("DiagnosticReport", "category");
        }
    }

    /**
     * Count ImagingStudy by category.
     * Categories are typically stored in the description field.
     */
    public StatisticsDTO getImagingStudyByCategory() {
        try {
            return queryResourceBySearchParam("ImagingStudy", "modality", "modality");
        } catch (Exception e) {
            log.error("Error getting ImagingStudy statistics by category", e);
            return createEmptyStatistics("ImagingStudy", "category");
        }
    }

    /**
     * Count ImagingStudy by status.
     * Queries the search parameter index for status values.
     */
    public StatisticsDTO getImagingStudyByStatus() {
        try {
            return queryResourceBySearchParam("ImagingStudy", "status", "status");
        } catch (Exception e) {
            log.error("Error getting ImagingStudy statistics by status", e);
            return createEmptyStatistics("ImagingStudy", "status");
        }
    }

    /**
     * Generic query to count resources grouped by a search parameter value.
     * This queries the HAPI search parameter index tables.
     * Tries string index first, then token index, then falls back to JSON parsing.
     *
     * @param resourceType FHIR resource type (e.g., "DiagnosticReport")
     * @param paramName    Search parameter name (e.g., "status")
     * @param displayName  Human-readable name for the grouping
     * @return Statistics DTO with counts grouped by parameter values
     */
    private StatisticsDTO queryResourceBySearchParam(String resourceType, String paramName, String displayName)
            throws SQLException {
        // First, try querying the string search parameter index
        List<StatisticItemDTO> items = queryStringSearchParamIndex(resourceType, paramName);
        
        // If no results, try the token search parameter index
        if (items.isEmpty()) {
            log.debug("No results in string index for {}/{}, trying token index", resourceType, paramName);
            items = queryTokenSearchParamIndex(resourceType, paramName);
        }
        
        // If still no results, fall back to JSON parsing
        if (items.isEmpty()) {
            log.debug("No results in token index for {}/{}, falling back to JSON parsing", resourceType, paramName);
            items = queryJsonResourceField(resourceType, paramName);
        }

        long total = items.stream().mapToLong(StatisticItemDTO::getCount).sum();

        log.info("Retrieved {} {} statistics for {}: {} items, {} total",
                resourceType, paramName, displayName, items.size(), total);

        return new StatisticsDTO(resourceType, displayName, items, total);
    }

    /**
     * Query string search parameter index.
     */
    private List<StatisticItemDTO> queryStringSearchParamIndex(String resourceType, String paramName)
            throws SQLException {
        String sql = """
                SELECT
                	sp_value_exact as value,
                	COUNT(DISTINCT res_id) as count
                FROM hfj_spidx_string
                WHERE
                	res_type = ?
                	AND sp_name = ?
                	AND sp_value_exact IS NOT NULL
                	AND sp_value_exact != ''
                GROUP BY sp_value_exact
                ORDER BY count DESC, sp_value_exact ASC
                """;

        List<StatisticItemDTO> items = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, resourceType);
            pstmt.setString(2, paramName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String value = rs.getString("value");
                    long count = rs.getLong("count");
                    items.add(new StatisticItemDTO(value, count));
                }
            }
        } catch (SQLException e) {
            log.debug("Query failed for string index {}/{}: {}", resourceType, paramName, e.getMessage());
        }

        return items;
    }

    /**
     * Query token search parameter index (for coded/concept values).
     */
    private List<StatisticItemDTO> queryTokenSearchParamIndex(String resourceType, String paramName)
            throws SQLException {
        String sql = """
                SELECT
                	sp_value as value,
                	COUNT(DISTINCT res_id) as count
                FROM hfj_spidx_token
                WHERE
                	res_type = ?
                	AND sp_name = ?
                	AND sp_value IS NOT NULL
                	AND sp_value != ''
                GROUP BY sp_value
                ORDER BY count DESC, sp_value ASC
                """;

        List<StatisticItemDTO> items = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, resourceType);
            pstmt.setString(2, paramName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String value = rs.getString("value");
                    long count = rs.getLong("count");
                    items.add(new StatisticItemDTO(value, count));
                }
            }
        } catch (SQLException e) {
            log.debug("Query failed for token index {}/{}: {}", resourceType, paramName, e.getMessage());
        }

        return items;
    }

    /**
     * Fall back to querying the JSON content directly when search parameter indexes are empty.
     * This is useful when resources haven't been reindexed or parameters are stored in JSON only.
     */
    private List<StatisticItemDTO> queryJsonResourceField(String resourceType, String paramName)
            throws SQLException {
        // Build JSON path based on parameter
        String jsonPath = buildJsonPath(paramName);
        
        String sql = """
                SELECT
                	resource_text::jsonb -> ? ->> 0 as value,
                	COUNT(DISTINCT res_id) as count
                FROM hfj_resource
                WHERE
                	res_type = ?
                	AND resource_text IS NOT NULL
                	AND resource_text::jsonb -> ? IS NOT NULL
                GROUP BY (resource_text::jsonb -> ? ->> 0)
                HAVING (resource_text::jsonb -> ? ->> 0) IS NOT NULL
                ORDER BY count DESC, value ASC
                """;

        List<StatisticItemDTO> items = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, jsonPath);
            pstmt.setString(2, resourceType);
            pstmt.setString(3, jsonPath);
            pstmt.setString(4, jsonPath);
            pstmt.setString(5, jsonPath);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String value = rs.getString("value");
                    if (value != null && !value.isEmpty()) {
                        long count = rs.getLong("count");
                        items.add(new StatisticItemDTO(value, count));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("JSON parsing query failed for {}/{}: {}", resourceType, paramName, e.getMessage());
        }

        return items;
    }

    /**
     * Build JSON path for extracting parameter values from resource JSON.
     */
    private String buildJsonPath(String paramName) {
        return switch (paramName) {
            case "status" -> "status";
            case "category" -> "category";
            case "modality" -> "modality";
            default -> paramName;
        };
    }


    /**
     * Create an empty statistics DTO (e.g., when an error occurs).
     */
    private StatisticsDTO createEmptyStatistics(String resourceType, String groupBy) {
        return new StatisticsDTO(resourceType, groupBy, Collections.emptyList(), 0L);
    }
}
