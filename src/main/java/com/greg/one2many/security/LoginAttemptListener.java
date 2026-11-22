package com.greg.one2many.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// Spring Security announces every login result as an event, for the form login AND for
// HTTP Basic (the AuthenticationEventPublisher bean in SecurityConfig makes sure of that).
// Here we feed those results to the LoginThrottleService.
@Component
@RequiredArgsConstructor
public class LoginAttemptListener {

    private final LoginThrottleService throttle;

    @EventListener
    public void onBadPassword(AuthenticationFailureBadCredentialsEvent event) {
        if (!cameFromThisLaptop(event.getAuthentication())) {
            throttle.recordOutsideFailure();
        }
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        throttle.recordSuccess();
    }

    // the current request is available here: Spring's RequestContextFilter runs before
    // the security filters. Fallback: the address Spring Security noted in the details.
    private boolean cameFromThisLaptop(Authentication authentication) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return LoginThrottleService.isLocal(servletAttributes.getRequest());
        }
        if (authentication != null && authentication.getDetails() instanceof WebAuthenticationDetails details) {
            return LoginThrottleService.isLoopback(details.getRemoteAddress());
        }
        return false; // unknown: count it (safer)
    }
}
