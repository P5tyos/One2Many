package com.greg.one2many.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Runs inside the security chain, just BEFORE the password is checked (added in SecurityConfig,
// deliberately not a @Component so it doesn't also run as a plain servlet filter).
// During a login pause it refuses outside login attempts without looking at the password,
// so even the right password gets the same answer: the reply reveals nothing.
// Only login attempts are affected: a form POST to /login, or a request carrying HTTP Basic.
// Requests that are already logged in (session cookie) pass straight through.
public class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginThrottleService throttle;

    public LoginThrottleFilter(LoginThrottleService throttle) {
        this.throttle = throttle;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean formLogin = "POST".equalsIgnoreCase(request.getMethod())
                && (request.getContextPath() + "/login").equals(request.getRequestURI());
        String authorization = request.getHeader("Authorization");
        boolean basicLogin = authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);

        if ((formLogin || basicLogin) && !LoginThrottleService.isLocal(request) && throttle.isPaused()) {
            if (formLogin) {
                response.sendRedirect(request.getContextPath() + "/login?error"); // same as a wrong password
            } else {
                response.setStatus(429); // Too Many Requests; no body, no WWW-Authenticate
                response.setHeader("Retry-After", String.valueOf(throttle.secondsLeft()));
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
