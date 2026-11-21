package com.greg.one2many.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Our API only ever answers in JSON. A browser opening an API address asks for HTML;
// trying to honour that turned a simple 404 into a 500 with a 30 KB stack trace in the log
// (review, 21 Nov). So: ignore what the client asks for, always JSON.
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.ignoreAcceptHeader(true).defaultContentType(MediaType.APPLICATION_JSON);
    }
}
