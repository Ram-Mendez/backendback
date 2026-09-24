package com.mendez.ram.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mendez.ram.attachment.service.AttachmentPathSanitizer;
import com.mendez.ram.exception.ApiException;
import org.junit.jupiter.api.Test;

class AttachmentPathSanitizerTest {

	private final AttachmentPathSanitizer sanitizer = new AttachmentPathSanitizer();

	@Test
	void keepsNestedRelativePathAndSanitizesUnsafeFilenameCharacters() {
		// ARRANGE — ruta anidada con caracteres no seguros en el nombre.
		var path = sanitizer.sanitize("docs/2026/invoice:one?.pdf", "ignored.txt");

		// ASSERT — conserva carpetas y limpia el nombre final.
		assertThat(path.relativePath()).isEqualTo("docs/2026/invoice_one_.pdf");
		assertThat(path.fileName()).isEqualTo("invoice_one_.pdf");
	}

	@Test
	void normalizesWindowsSeparatorsReservedNamesAndUnicode() {
		// ACT — normalizar separadores, nombre reservado y Unicode compuesto.
		var windowsPath = sanitizer.sanitize("docs\\2026\\file.txt", "ignored.txt");
		var reservedName = sanitizer.sanitize("CON.txt", "ignored.txt");
		var unicodeName = sanitizer.sanitize("Cafe\u0301.txt", "ignored.txt");

		assertThat(windowsPath.relativePath()).isEqualTo("docs/2026/file.txt");
		assertThat(reservedName.relativePath()).isEqualTo("CON_.txt");
		assertThat(unicodeName.relativePath()).isEqualTo("Caf\u00e9.txt");
	}

	@Test
	void fallsBackToOriginalFilenameWhenRelativePathIsMissing() {
		// ASSERT — sin ruta relativa se usa el nombre original.
		var path = sanitizer.sanitize(" ", "fallback.txt");

		assertThat(path.relativePath()).isEqualTo("fallback.txt");
	}

	@Test
	void rejectsTraversalAndAbsolutePaths() {
		// ASSERT — las rutas fuera de la carpeta permitida se rechazan.
		assertThatThrownBy(() -> sanitizer.sanitize("../secret.txt", "secret.txt"))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> sanitizer.sanitize("C:/secret.txt", "secret.txt"))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> sanitizer.sanitize("/secret.txt", "secret.txt"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void rejectsEmptyAndExcessivelyLongPaths() {
		// ASSERT — se rechazan rutas vacías y sobre el límite de longitud.
		assertThatThrownBy(() -> sanitizer.sanitize(" ", " "))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> sanitizer.sanitize("dir/".repeat(300) + "file.txt", "file.txt"))
				.isInstanceOf(ApiException.class);
	}
}
