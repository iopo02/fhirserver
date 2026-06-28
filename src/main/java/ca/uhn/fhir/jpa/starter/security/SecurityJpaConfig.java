package ca.uhn.fhir.jpa.starter.security;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor; // <-- CORRIGIDO O IMPORT
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

@Configuration
@EnableJpaRepositories(basePackages = "ca.uhn.fhir.jpa.starter.security")
public class SecurityJpaConfig implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // Interceta o Gestor de Entidades customizado do HAPI FHIR antes de ele carregar
        if (bean instanceof LocalContainerEntityManagerFactoryBean) {
            LocalContainerEntityManagerFactoryBean emf = (LocalContainerEntityManagerFactoryBean) bean;
            
            // Força o HAPI FHIR a incluir o teu pacote de segurança no varrimento do Hibernate
            emf.setPackagesToScan(
                "ca.uhn.fhir.jpa.model.entity",
                "ca.uhn.fhir.jpa.entity",
                "ca.uhn.fhir.jpa.starter",
                "ca.uhn.fhir.jpa.starter.security" // <-- Teu pacote adicionado aqui
            );
        }
        return bean;
    }
}