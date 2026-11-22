package com.greg.one2many.web;

import com.greg.one2many.security.LoginThrottleFilter;
import com.greg.one2many.security.LoginThrottleService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginThrottleService loginThrottle)
            throws Exception {
        http
            // login pause after repeated failed passwords from outside (checked before the password)
            .addFilterBefore(new LoginThrottleFilter(loginThrottle), UsernamePasswordAuthenticationFilter.class)
            // CSRF protection comes from our SameSite cookies instead (see notes)
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // station only: must be logged in as admin
                .requestMatchers(HttpMethod.POST, "/api/attendees/scan").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // attendee doors: open, protected by token + wristband instead
                .requestMatchers("/api/attendees/**").permitAll()
                // event info: public, shown on the station advert and the phone page
                .requestMatchers(HttpMethod.GET, "/api/event").permitAll()
                // the Vue app's public pages and files (event mode: Spring serves the built app)
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico",
                        "/app-icon.png", "/about", "/p/**", "/error").permitAll()
                // everything else, including the /station and /dashboard pages: login required
                .anyRequest().authenticated()
            )
            // login page; afterwards always the dashboard (true = ignore remembered pages), which links to the station
            .formLogin(form -> form.defaultSuccessUrl("/dashboard", true))
            .httpBasic(Customizer.withDefaults())
            // not logged in: pages (/station, /dashboard) → our login form; /api/** → plain 401.
            // Decided by path, not by the Accept header: WebConfig ignores Accept (API = always
            // JSON), and Security would otherwise fall back to the browser's grey Basic popup.
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    request -> !request.getRequestURI().startsWith("/api/")));  // username:password for curl testing
        return http.build();
    }

    // makes sure every login result (form AND HTTP Basic) is published as an event,
    // which LoginAttemptListener counts for the login pause
    @Bean
    public AuthenticationEventPublisher authenticationEventPublisher(ApplicationEventPublisher publisher) {
        return new DefaultAuthenticationEventPublisher(publisher);
    }
}