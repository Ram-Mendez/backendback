package com.mendez.ram.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Correlation-ID";
	private static final int MAX_LENGTH = 128;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String correlationId = resolveCorrelationId(request);
		response.setHeader(HEADER_NAME, correlationId);
		MDC.put("correlationId", correlationId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.remove("correlationId");
		}
	}

	private static String resolveCorrelationId(HttpServletRequest request) {
		String headerValue = request.getHeader(HEADER_NAME);
		if (StringUtils.hasText(headerValue) && headerValue.length() <= MAX_LENGTH) {
			return headerValue;
		}
		return UUID.randomUUID().toString();
	}
}
