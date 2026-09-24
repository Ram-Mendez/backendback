package com.mendez.ram.security;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorWriter {

	private final ObjectMapper objectMapper;
	private final Clock clock;

	public SecurityErrorWriter(ObjectMapper objectMapper, Clock clock) {
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public void write(HttpServletResponse response, HttpStatus status, String code, String message, String path)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
		String errorTypeName = code.toLowerCase().replace('_', '-');
		URI errorType = URI.create("urn:ram:error:" + errorTypeName);

		problem.setType(errorType);
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create(path));
		problem.setProperty("code", code);
		problem.setProperty("timestamp", OffsetDateTime.now(clock));

		objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
