package ca.uhn.fhir.jpa.starter.notification;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.IdType;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class PatientNotificationListener implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(PatientNotificationListener.class);

	private final DataSource dataSource;
	private final DaoRegistry daoRegistry;
	private final SimpMessagingTemplate messagingTemplate;

	private ExecutorService listenerExecutor;
	private ExecutorService workerExecutor;
	private volatile boolean running = false;

	public PatientNotificationListener(DataSource dataSource, DaoRegistry daoRegistry, SimpMessagingTemplate messagingTemplate) {
		this.dataSource = dataSource;
		this.daoRegistry = daoRegistry;
		this.messagingTemplate = messagingTemplate;
	}

	@Override
	public void start() {
		log.info("Starting Patient Notification Listener...");
		try (Connection conn = dataSource.getConnection()) {
			String dbProductName = conn.getMetaData().getDatabaseProductName();
			if (!"PostgreSQL".equalsIgnoreCase(dbProductName)) {
				log.warn("Database is {}, not PostgreSQL. PatientNotificationListener will not start.", dbProductName);
				return;
			}
		} catch (Exception e) {
			log.error("Failed to check database product name for PatientNotificationListener", e);
			return;
		}

		this.running = true;
		
		// Worker thread pool to process notification payloads concurrently
		this.workerExecutor = Executors.newFixedThreadPool(15, r -> {
			Thread t = new Thread(r, "patient-notification-worker");
			t.setDaemon(true);
			return t;
		});

		// Single-threaded listener that blocks on PostgreSQL socket
		this.listenerExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "patient-db-listener");
			t.setDaemon(true);
			return t;
		});
		this.listenerExecutor.submit(this::listenLoop);
	}

	@Override
	public void stop() {
		log.info("Stopping Patient Notification Listener...");
		this.running = false;
		if (listenerExecutor != null) {
			listenerExecutor.shutdownNow();
		}
		if (workerExecutor != null) {
			workerExecutor.shutdown();
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	private void listenLoop() {
		while (running) {
			try (Connection conn = dataSource.getConnection()) {
				PGConnection pgConn = conn.unwrap(PGConnection.class);
				try (Statement stmt = conn.createStatement()) {
					stmt.execute("LISTEN patient_created");
				}
				log.info("Subscribed to PostgreSQL channel 'patient_created'");

				while (running && !Thread.currentThread().isInterrupted()) {
					// Block for up to 5 seconds waiting for notifications
					PGNotification[] notifications = pgConn.getNotifications(5000);
					if (notifications != null) {
						for (PGNotification notification : notifications) {
							String pidStr = notification.getParameter();
							log.debug("Received patient_created notification for PID: {}", pidStr);
							workerExecutor.submit(() -> handlePatientCreated(pidStr));
						}
					}
				}
			} catch (SQLException e) {
				if (running) {
					log.error("Database error in LISTEN loop, retrying in 5 seconds...", e);
					try {
						Thread.sleep(5000);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				}
			} catch (Exception e) {
				log.error("Unexpected error in LISTEN loop", e);
				try {
					Thread.sleep(5000);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private void handlePatientCreated(String pidStr) {
		try {
			long pid = Long.parseLong(pidStr);
			String logicalId = getLogicalId(pid);
			if (logicalId == null) {
				log.warn("Could not find logical ID for patient PID: {}", pid);
				return;
			}

			// Fetch patient resource using HAPI DAO registry
			@SuppressWarnings("unchecked")
			IFhirResourceDao<IBaseResource> patientDao = daoRegistry.getResourceDao("Patient");
			IBaseResource resource = patientDao.read(new IdType("Patient", logicalId));

			if (!(resource instanceof Patient)) {
				log.warn("Resource fetched for logical ID {} is not a Patient, but: {}", logicalId, resource.getClass().getName());
				return;
			}
			Patient patient = (Patient) resource;

			// Construct payload
			PatientNotificationPayload payload = new PatientNotificationPayload();
			payload.setResourceId("Patient/" + logicalId);
			payload.setTimestamp(Instant.now().toString());
			payload.setResourceType("Patient");

			if (patient.hasIdentifier() && !patient.getIdentifier().isEmpty()) {
				Identifier firstId = patient.getIdentifier().get(0);
				if (firstId.hasValue()) {
					if (firstId.hasSystem()) {
						payload.setPatientIdentifier(firstId.getSystem() + "|" + firstId.getValue());
					} else {
						payload.setPatientIdentifier(firstId.getValue());
					}
				}
			}

			// Send to WebSocket topic
			messagingTemplate.convertAndSend("/topic/patients", payload);
			log.info("Forwarded patient created notification to /topic/patients: {}", payload.getResourceId());

		} catch (Exception e) {
			log.error("Error processing patient notification for PID: " + pidStr, e);
		}
	}

	private String getLogicalId(long pid) {
		String sql = "SELECT fhir_id FROM hfj_resource WHERE res_id = ?";
		try (Connection conn = dataSource.getConnection();
			 PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, pid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getString("fhir_id");
				}
			}
		} catch (SQLException e) {
			log.error("Error fetching logical ID for PID " + pid, e);
		}
		return null;
	}
}
