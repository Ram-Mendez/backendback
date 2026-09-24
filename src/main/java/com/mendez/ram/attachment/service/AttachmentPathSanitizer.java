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
		String submittedPathCandidate = submittedFilename;
		if (hasText(submittedRelativePath)) {
			submittedPathCandidate = submittedRelativePath;
		}

		if (!hasText(submittedPathCandidate)) {
			throw invalidAttachmentPath();
		}

		String pathWithNormalizedSeparators = submittedPathCandidate.trim().replace('\\', '/');
		String normalizedPath = Normalizer.normalize(pathWithNormalizedSeparators, Normalizer.Form.NFKC);

		if (isAbsoluteOrHomeRelativePath(normalizedPath)) {
			throw invalidAttachmentPath();
		}
		if (CONTROL_CHARS.matcher(normalizedPath).find()) {
			throw invalidAttachmentPath();
		}

		String[] pathSegments = normalizedPath.split("/");
		StringBuilder sanitizedPathBuilder = new StringBuilder();
		for (String pathSegment : pathSegments) {
			if (!hasText(pathSegment) || ".".equals(pathSegment) || "..".equals(pathSegment)) {
				throw invalidAttachmentPath();
			}

			String sanitizedSegment = sanitizeSegment(pathSegment);
			if (sanitizedPathBuilder.length() > 0) {
				sanitizedPathBuilder.append('/');
			}

			sanitizedPathBuilder.append(sanitizedSegment);
		}

		if (sanitizedPathBuilder.isEmpty() || sanitizedPathBuilder.length() > MAX_PATH_LENGTH) {
			throw invalidAttachmentPath();
		}

		String relativePath = sanitizedPathBuilder.toString();
		int separatorIndex = relativePath.lastIndexOf('/');
		String fileName = separatorIndex >= 0 ? relativePath.substring(separatorIndex + 1) : relativePath;

		return new SanitizedAttachmentPath(fileName, relativePath);
	}

	private static boolean isAbsoluteOrHomeRelativePath(String normalizedPath) {
		return normalizedPath.startsWith("/")
				|| normalizedPath.startsWith("~")
				|| WINDOWS_ABSOLUTE_PATH.matcher(normalizedPath).matches();
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

	private static String appendReservedNameSuffix(String fileName) {
		int dotIndex = fileName.indexOf('.');
		if (dotIndex > 0) {
			return fileName.substring(0, dotIndex) + "_" + fileName.substring(dotIndex);
		}

		return fileName + "_";
	}

	private static String trimTrailingDotsAndSpaces(String fileNameSegment) {
		int trimmedEndIndex = fileNameSegment.length();
		while (trimmedEndIndex > 0) {
			char trailingCharacter = fileNameSegment.charAt(trimmedEndIndex - 1);
			if (trailingCharacter != '.' && trailingCharacter != ' ') {
				break;
			}

			trimmedEndIndex--;
		}

		return fileNameSegment.substring(0, trimmedEndIndex);
	}

	private static boolean hasText(String text) {
		return text != null && !text.isBlank();
	}

	private static ApiException invalidAttachmentPath() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_PATH",
				"La ruta del adjunto no es valida.");
	}

	public record SanitizedAttachmentPath(String fileName, String relativePath) {
	}
}
