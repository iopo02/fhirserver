package ca.uhn.fhir.jpa.starter.notification;

public class PatientNotificationPayload {
	private String resourceId;
	private String timestamp;
	private String resourceType;
	private String patientIdentifier;

	public PatientNotificationPayload() {
	}

	public PatientNotificationPayload(String resourceId, String timestamp, String resourceType, String patientIdentifier) {
		this.resourceId = resourceId;
		this.timestamp = timestamp;
		this.resourceType = resourceType;
		this.patientIdentifier = patientIdentifier;
	}

	public String getResourceId() {
		return resourceId;
	}

	public void setResourceId(String resourceId) {
		this.resourceId = resourceId;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(String timestamp) {
		this.timestamp = timestamp;
	}

	public String getResourceType() {
		return resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
	}

	public String getPatientIdentifier() {
		return patientIdentifier;
	}

	public void setPatientIdentifier(String patientIdentifier) {
		this.patientIdentifier = patientIdentifier;
	}
}
