package com.greg.one2many.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protection comes from our SameSite cookies instead (see notes)
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // station only: must be logged in as admin
                .requestMatchers(HttpMethod.POST, "/api/attendees/scan").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // attendee doors: open, protected by token + wristband instead
                .requestMatchers("/api/attendees/**").permitAll()
                // everything else (future dashboard etc.): login required
                .anyRequest().authenticated()
            )
            .formLogin(Customizer.withDefaults())   // login page for the station's browser
            .httpBasic(Customizer.withDefaults());  // username:password for curl testing
        return http.build();
    }
}