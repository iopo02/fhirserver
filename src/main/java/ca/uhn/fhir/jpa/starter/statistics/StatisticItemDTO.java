package ca.uhn.fhir.jpa.starter.statistics;

/**
 * DTO representing a single statistic item (e.g., count for a specific status or category).
 */
public class StatisticItemDTO {
	private String name;
	private Long count;

	public StatisticItemDTO() {
	}

	public StatisticItemDTO(String name, Long count) {
		this.name = name;
		this.count = count;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Long getCount() {
		return count;
	}

	public void setCount(Long count) {
		this.count = count;
	}
}
