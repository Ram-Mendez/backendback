package com.mendez.ram.attachment.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.mendez.ram.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AttachmentPathSanitizer {

	private static final int MAX_SEGMENT_LENGTH = 180;
	private static final int MAX_PATH_LENGTH = 1024;
	private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[/\\\\].*");
	private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");
	private static final Pattern FORBIDDEN_FILENAME_CHARS = Pattern.compile("[<>:\"\\\\|?*]");
	private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
			"CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

	public SanitizedAttachmentPath sanitize(String submittedRelativePath, String submittedFilename) {
		String candidate = hasText(submittedRelativePath) ? submittedRelativePath : submittedFilename;
		if (!hasText(candidate)) {
			throw invalidPath();
		}

		String normalized = Normalizer.normalize(candidate.trim().replace('\\', '/'), Normalizer.Form.NFKC);
		if (normalized.startsWith("/") || normalized.startsWith("~") || WINDOWS_ABSOLUTE_PATH.matcher(normalized).matches()
				|| CONTROL_CHARS.matcher(normalized).find()) {
			throw invalidPath();
		}

		String[] parts = normalized.split("/");
		StringBuilder sanitized = new StringBuilder();
		for (String part : parts) {
			if (!hasText(part) || ".".equals(part) || "..".equals(part)) {
				throw invalidPath();
			}
			String sanitizedPart = sanitizeSegment(part);
			if (sanitized.length() > 0) {
				sanitized.append('/');
			}
			sanitized.append(sanitizedPart);
		}

		if (sanitized.isEmpty() || sanitized.length() > MAX_PATH_LENGTH) {
			throw invalidPath();
		}

		String relativePath = sanitized.toString();
		int separatorIndex = relativePath.lastIndexOf('/');
		String fileName = separatorIndex >= 0 ? relativePath.substring(separatorIndex + 1) : relativePath;
		return new SanitizedAttachmentPath(fileName, relativePath);
	}

	private String sanitizeSegment(String segment) {
		String sanitized = FORBIDDEN_FILENAME_CHARS.matcher(segment).replaceAll("_");
		sanitized = sanitized.replaceAll("\\s+", " ").trim();
		sanitized = trimTrailingDotsAndSpaces(sanitized);
		if (sanitized.isBlank()) {
			sanitized = "file";
		}
		if (sanitized.length() > MAX_SEGMENT_LENGTH) {
			sanitized = sanitized.substring(0, MAX_SEGMENT_LENGTH);
			sanitized = trimTrailingDotsAndSpaces(sanitized);
		}
		String baseName = sanitized;
		int dotIndex = sanitized.indexOf('.');
		if (dotIndex >= 0) {
			baseName = sanitized.substring(0, dotIndex);
		}
		if (WINDOWS_RESERVED_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
			sanitized = appendReservedNameSuffix(sanitized);
		}
		return sanitized;
	}

	private static String appendReservedNameSuffix(String value) {
		int dotIndex = value.indexOf('.');
		if (dotIndex > 0) {
			return value.substring(0, dotIndex) + "_" + value.substring(dotIndex);
		}
		return value + "_";
	}

	private static String trimTrailingDotsAndSpaces(String value) {
		int end = value.length();
		while (end > 0) {
			char current = value.charAt(end - 1);
			if (current != '.' && current != ' ') {
				break;
			}
			end--;
		}
		return value.substring(0, end);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static ApiException invalidPath() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_PATH",
				"La ruta del adjunto no es valida.");
	}

	public record SanitizedAttachmentPath(String fileName, String relativePath) {
	}
}
