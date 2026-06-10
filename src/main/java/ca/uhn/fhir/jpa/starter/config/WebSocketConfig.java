package ca.uhn.fhir.jpa.starter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	@Override
	public void configureMessageBroker(MessageBrokerRegistry config) {
		// Enable simple memory-based message broker for routing messages
		config.enableSimpleBroker("/topic");
		config.setApplicationDestinationPrefixes("/app");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// Registers WebSocket endpoint at /ws-patients with SockJS support
		registry.addEndpoint("/ws-patients")
				.setAllowedOriginPatterns("*")
				.withSockJS();

		// Registers raw WebSocket endpoint at /ws-patients (without SockJS fallback)
		registry.addEndpoint("/ws-patients")
				.setAllowedOriginPatterns("*");
	}
}
