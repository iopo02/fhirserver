package ca.uhn.fhir.jpa.starter.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class SecurityDbInitializer {

	private static final Logger log = LoggerFactory.getLogger(SecurityDbInitializer.class);
	private final DataSource dataSource;
	private final PasswordEncoder passwordEncoder;

	public SecurityDbInitializer(DataSource dataSource, PasswordEncoder passwordEncoder) {
		this.dataSource = dataSource;
		this.passwordEncoder = passwordEncoder;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void init() {
		log.info("Checking for default security seed data...");
		try (Connection conn = dataSource.getConnection()) {
			createAdminUserIfMissing(conn);
		} catch (Exception e) {
			log.error("Failed to initialize default admin user.", e);
		}
	}

	private void createAdminUserIfMissing(Connection conn) {
		// 1. Verifica especificamente pelo EMAIL do admin, não pelo count total
		String checkSql = "SELECT COUNT(*) FROM users WHERE email = 'admin@email.com'";

		try (PreparedStatement psCheck = conn.prepareStatement(checkSql);
				ResultSet rs = psCheck.executeQuery()) {

			if (rs.next() && rs.getInt(1) == 0) {
				log.info("Admin user not found. Seeding default 'admin' user...");

				// Gera a hash de forma limpa
				String encryptedPassword = passwordEncoder.encode("admin123");

				String insertUserSql = """
						    INSERT INTO users (id, username, email, password, first_name, last_name, active, locked, created_at, updated_at)
						    VALUES (1, 'admin', 'admin@email.com', ?, 'Admin', 'Global', TRUE, FALSE, NOW(), NOW())
						""";

				try (PreparedStatement psUser = conn.prepareStatement(insertUserSql)) {
					psUser.setString(1, encryptedPassword);
					psUser.executeUpdate();
				}

				// Inserir a role apenas se o user acabou de ser criado
				String insertRoleSql = "INSERT INTO user_roles (user_id, role) VALUES (1, 'ADMIN')";
				try (Statement stmtRole = conn.createStatement()) {
					stmtRole.execute(insertRoleSql);
				}

				log.info("Default administrator 'admin' successfully seeded with password 'admin123'.");
			} else {
				log.info("Admin user ('admin@email.com') already exists. Skipping seed.");
			}
		} catch (Exception e) {
			log.error("Error seeding default admin user into database", e);
		}
	}
}