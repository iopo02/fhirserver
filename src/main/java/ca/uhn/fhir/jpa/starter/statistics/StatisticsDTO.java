package ca.uhn.fhir.jpa.starter.statistics;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * DTO representing a collection of statistics grouped by a specific criterion.
 * Includes metadata about the statistics query.
 */
public class StatisticsDTO {
	@JsonProperty("timestamp")
	private Instant timestamp;

	@JsonProperty("resourceType")
	private String resourceType;

	@JsonProperty("groupBy")
	private String groupBy;

	@JsonProperty("data")
	private List<StatisticItemDTO> data;

	@JsonProperty("total")
	private Long total;

	public StatisticsDTO() {
		this.timestamp = Instant.now();
	}

	public StatisticsDTO(String resourceType, String groupBy, List<StatisticItemDTO> data, Long total) {
		this.timestamp = Instant.now();
		this.resourceType = resourceType;
		this.groupBy = groupBy;
		this.data = data;
		this.total = total;
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(Instant timestamp) {
		this.timestamp = timestamp;
	}

	public String getResourceType() {
		return resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
	}

	public String getGroupBy() {
		return groupBy;
	}

	public void setGroupBy(String groupBy) {
		this.groupBy = groupBy;
	}

	public List<StatisticItemDTO> getData() {
		return data;
	}

	public void setData(List<StatisticItemDTO> data) {
		this.data = data;
	}

	public Long getTotal() {
		return total;
	}

	public void setTotal(Long total) {
		this.total = total;
	}
}
