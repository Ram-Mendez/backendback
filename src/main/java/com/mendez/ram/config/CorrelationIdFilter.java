package com.mendez.ram.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
	private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String correlationId = resolveCorrelationId(request);
		response.setHeader(HEADER_NAME, correlationId);
		MDC.put("correlationId", correlationId);

		long startedAt = System.nanoTime();
		String method = request.getMethod();
		String path = request.getRequestURI();
		String query = request.getQueryString();

		LOGGER.info("HTTP -> method={} path={} query={}", method, path, query == null ? "-" : query);

		try {
			filterChain.doFilter(request, response);
		}
		catch (Exception exception) {
			long elapsedMs = elapsedMillis(startedAt);
			LOGGER.error("HTTP !! method={} path={} status={} elapsedMs={} exception={} message={}",
					method, path, response.getStatus(), elapsedMs,
					exception.getClass().getSimpleName(), exception.getMessage(), exception);
			throw exception;
		}
		finally {
			long elapsedMs = elapsedMillis(startedAt);
			LOGGER.info("HTTP <- method={} path={} status={} elapsedMs={}",
					method, path, response.getStatus(), elapsedMs);
			MDC.remove("correlationId");
		}
	}

	private static long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	private static String resolveCorrelationId(HttpServletRequest request) {
		String headerValue = request.getHeader(HEADER_NAME);
		if (StringUtils.hasText(headerValue) && headerValue.length() <= MAX_LENGTH) {
			return headerValue;
		}
		return UUID.randomUUID().toString();
	}
}
