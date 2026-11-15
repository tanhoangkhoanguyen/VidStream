package io.github.tanhoangkhoanguyen.vidstream.auth;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// Actuator is open: it listens only on the internal management port.
@Configuration
class SecurityConfig {

  @Bean
  @Profile("web")
  SecurityFilterChain web(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(a ->
            a.requestMatchers(EndpointRequest.toAnyEndpoint())
                .permitAll()
                .anyRequest()
                .authenticated())
        .oauth2Login(Customizer.withDefaults())
        .build();
  }

  @Bean
  @Profile("worker")
  SecurityFilterChain worker(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(a ->
            a.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll().anyRequest().denyAll())
        .build();
  }
}
