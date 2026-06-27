package ca.uhn.fhir.jpa.starter.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
// 1. Vinculamos explicitamente este ecossistema de repositórios aos Beans abaixo
@EnableJpaRepositories(
    basePackages = "ca.uhn.fhir.jpa.starter.security",
    entityManagerFactoryRef = "securityEntityManagerFactory",
    transactionManagerRef = "securityTransactionManager"
)
public class SecurityJpaConfig {

    @Bean(name = "securityEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean securityEntityManagerFactory(
            EntityManagerFactoryBuilder builder, DataSource dataSource) {
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"); 

        return builder
                .dataSource(dataSource)
                .packages("ca.uhn.fhir.jpa.starter.security") // Scan exclusivo para a entidade User
                .persistenceUnit("securityPU")
                .properties(properties)
                .build();
    }

    @Bean(name = "securityTransactionManager")
    public PlatformTransactionManager securityTransactionManager(
            @Qualifier("securityEntityManagerFactory") EntityManagerFactory securityEntityManagerFactory) {
        return new JpaTransactionManager(securityEntityManagerFactory);
    }
}