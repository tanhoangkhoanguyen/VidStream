package io.github.tanhoangkhoanguyen.vidstream.dispatch;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The worker API ({@code /api/worker/**}) takes a worker token, {@code Authorization: Bearer …},
 * and nothing else: no session, no cookie, so no CSRF. The principal is the worker's name.
 */
@Configuration
@Profile("web")
class WorkerApiSecurity {

  static final String PATH = "/api/worker/**";

  // Before the web chain, which matches every other request.
  @Bean
  @Order(1)
  SecurityFilterChain workerApiFilterChain(HttpSecurity http, WorkerTokens tokens)
      throws Exception {
    return http.securityMatcher(PATH)
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .addFilterBefore(new BearerFilter(tokens), AnonymousAuthenticationFilter.class)
        .exceptionHandling(e ->
            e.authenticationEntryPoint((request, response, failure) -> {
              response.setHeader(WWW_AUTHENTICATE, "Bearer");
              response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            }))
        .build();
  }

  /** Not a bean: Spring Boot would also register it for every request. */
  private static final class BearerFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final List<SimpleGrantedAuthority> WORKER =
        List.of(new SimpleGrantedAuthority("ROLE_WORKER"));

    private final WorkerTokens tokens;

    BearerFilter(WorkerTokens tokens) {
      this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      var header = request.getHeader(AUTHORIZATION);
      if (header != null && header.startsWith(BEARER)) {
        tokens
            .authenticate(header.substring(BEARER.length()))
            .ifPresent(name -> {
              var context = SecurityContextHolder.createEmptyContext();
              context.setAuthentication(
                  new PreAuthenticatedAuthenticationToken(name, null, WORKER));
              SecurityContextHolder.setContext(context);
            });
      }
      chain.doFilter(request, response);
    }
  }
}
