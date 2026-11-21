package com.greg.one2many.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Our biggest real request is a profile or event form: well under 2 KB.
// Without this, a 25 MB body is read completely before validation says "too long";
// many of those at once could run Java out of memory and lose everyone (review, 21 Nov).
// Runs before everything else, even before the login check.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeFilter extends OncePerRequestFilter {

    private static final long MAX_BODY_BYTES = 8 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        // announced size too big: refuse without reading a byte
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(413); // Content Too Large
            return;
        }
        // size unknown (sent in chunks, which a proxy may do): read it, but stop after the limit
        chain.doFilter(new LimitedRequest(request), response);
    }

    // a request whose body stops being readable after MAX_BODY_BYTES (reading on = 400 "malformed")
    private static class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream limited; // created once: one counter for the whole body

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = wrap(super.getInputStream());
            }
            return limited;
        }

        private static ServletInputStream wrap(ServletInputStream original) {
            return new ServletInputStream() {
                private long count = 0;

                @Override
                public int read() throws IOException {
                    int b = original.read();
                    if (b != -1 && ++count > MAX_BODY_BYTES) {
                        throw new IOException("Request body larger than " + MAX_BODY_BYTES + " bytes");
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = original.read(buffer, offset, length);
                    if (n > 0 && (count += n) > MAX_BODY_BYTES) {
                        throw new IOException("Request body larger than " + MAX_BODY_BYTES + " bytes");
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return original.isFinished();
                }

                @Override
                public boolean isReady() {
                    return original.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    original.setReadListener(listener);
                }
            };
        }
    }
}
