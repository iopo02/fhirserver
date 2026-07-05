package ca.uhn.fhir.jpa.starter.statistics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

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
     * This queries the HPI search parameter index tables.
     *
     * @param resourceType FHIR resource type (e.g., "DiagnosticReport")
     * @param paramName    Search parameter name (e.g., "status")
     * @param displayName  Human-readable name for the grouping
     * @return Statistics DTO with counts grouped by parameter values
     */
    private StatisticsDTO queryResourceBySearchParam(String resourceType, String paramName, String displayName)
            throws SQLException {
        String sql = """
                SELECT
                	sp_value as value,
                	COUNT(DISTINCT resource_id) as count
                FROM hapi_spidx_string
                WHERE
                	resource_type = ?
                	AND param_name = ?
                	AND sp_value IS NOT NULL
                	AND sp_value != ''
                GROUP BY sp_value
                ORDER BY count DESC, sp_value ASC
                """;

        List<StatisticItemDTO> items = new ArrayList<>();
        long total = 0L;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, resourceType);
            pstmt.setString(2, paramName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String value = rs.getString("value");
                    long count = rs.getLong("count");
                    items.add(new StatisticItemDTO(value, count));
                    total += count;
                }
            }
        }

        log.info("Retrieved {} {} statistics for {}: {} items, {} total",
                resourceType, paramName, displayName, items.size(), total);

        return new StatisticsDTO(resourceType, displayName, items, total);
    }

    /**
     * Generic query using token search parameters (more appropriate for coded
     * values).
     * This is an alternative implementation for parameters stored as tokens.
     *
     * @param resourceType FHIR resource type
     * @param paramName    Search parameter name
     * @param displayName  Human-readable name
     * @return Statistics DTO
     */
    private StatisticsDTO queryResourceByTokenParam(String resourceType, String paramName, String displayName)
            throws SQLException {
        String sql = """
                SELECT
                	sp_value as value,
                	COUNT(DISTINCT resource_id) as count
                FROM hapi_spidx_token
                WHERE
                	resource_type = ?
                	AND param_name = ?
                	AND sp_value IS NOT NULL
                	AND sp_value != ''
                GROUP BY sp_value
                ORDER BY count DESC, sp_value ASC
                """;

        List<StatisticItemDTO> items = new ArrayList<>();
        long total = 0L;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, resourceType);
            pstmt.setString(2, paramName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String value = rs.getString("value");
                    long count = rs.getLong("count");
                    items.add(new StatisticItemDTO(value, count));
                    total += count;
                }
            }
        }

        log.info("Retrieved {} {} statistics for {}: {} items, {} total",
                resourceType, paramName, displayName, items.size(), total);

        return new StatisticsDTO(resourceType, displayName, items, total);
    }

    /**
     * Query total count of a resource type.
     */
    public long getTotalCountByResourceType(String resourceType) throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM hapi_resource WHERE resource_type = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, resourceType);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("count");
                }
            }
        }
        return 0L;
    }

    /**
     * Create an empty statistics DTO (e.g., when an error occurs).
     */
    private StatisticsDTO createEmptyStatistics(String resourceType, String groupBy) {
        return new StatisticsDTO(resourceType, groupBy, Collections.emptyList(), 0L);
    }
}
