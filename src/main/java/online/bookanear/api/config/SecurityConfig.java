package online.bookanear.api.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        return http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/error"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/password/forgot",
                                "/api/v1/auth/password/reset",
                                "/api/v1/registration"
                        ).permitAll()

                        // Old verification links only redirect to the React form.
                        .requestMatchers(HttpMethod.GET, "/verify-email").permitAll()

                        // Browser diagnostics are unauthenticated so even login/onboarding failures are logged.
                        .requestMatchers(HttpMethod.POST, "/api/v1/frontend-errors").permitAll()
                        .requestMatchers("/api/v1/registration/**").permitAll()
                        .requestMatchers("/api/v1/public/**").permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/bookings",
                                "/api/v1/bookings/universal"
                        ).permitAll()

                        .requestMatchers("/api/v1/backoffice/**").authenticated()

                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}