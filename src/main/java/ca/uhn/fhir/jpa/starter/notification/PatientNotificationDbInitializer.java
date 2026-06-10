package ca.uhn.fhir.jpa.starter.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.Statement;

@Component
public class PatientNotificationDbInitializer {

	private static final Logger log = LoggerFactory.getLogger(PatientNotificationDbInitializer.class);
	private final DataSource dataSource;

	public PatientNotificationDbInitializer(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@PostConstruct
	public void init() {
		log.info("Initializing PostgreSQL LISTEN/NOTIFY trigger for Patient resources...");
		try (Connection conn = dataSource.getConnection()) {
			String dbProductName = conn.getMetaData().getDatabaseProductName();
			if (!"PostgreSQL".equalsIgnoreCase(dbProductName)) {
				log.warn("Database is {}, not PostgreSQL. Skipping LISTEN/NOTIFY trigger registration.", dbProductName);
				return;
			}

			try (Statement stmt = conn.createStatement()) {
				// 1. Create or replace the trigger function
				String createFunctionSql =
						"CREATE OR REPLACE FUNCTION notify_patient_created() " +
						"RETURNS TRIGGER AS $$ " +
						"BEGIN " +
						"    IF NEW.res_type = 'Patient' THEN " +
						"        PERFORM pg_notify('patient_created', NEW.res_id::text); " +
						"    END IF; " +
						"    RETURN NEW; " +
						"END; " +
						"$$ LANGUAGE plpgsql;";
				stmt.execute(createFunctionSql);

				// 2. Drop the trigger if it exists and recreate it to be safe
				String dropTriggerSql = "DROP TRIGGER IF EXISTS trg_patient_created ON hfj_resource;";
				stmt.execute(dropTriggerSql);

				String createTriggerSql =
						"CREATE TRIGGER trg_patient_created " +
						"AFTER INSERT ON hfj_resource " +
						"FOR EACH ROW " +
						"EXECUTE FUNCTION notify_patient_created();";
				stmt.execute(createTriggerSql);

				log.info("PostgreSQL trigger 'trg_patient_created' registered successfully on table 'hfj_resource'.");
			}
		} catch (Exception e) {
			log.error("Failed to initialize PostgreSQL trigger. Please ensure the trigger is created manually.", e);
		}
	}
}
