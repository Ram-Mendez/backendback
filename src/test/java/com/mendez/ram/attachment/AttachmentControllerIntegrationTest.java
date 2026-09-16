package com.mendez.ram.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mendez.ram.TestcontainersConfiguration;
import com.mendez.ram.attachment.config.AttachmentProperties;
import com.mendez.ram.attachment.storage.AttachmentStorage;
import com.mendez.ram.attachment.storage.LocalAttachmentStorage;
import com.mendez.ram.attachment.storage.StoreAttachmentCommand;
import com.mendez.ram.attachment.storage.StoredAttachment;
import com.mendez.ram.attachment.storage.StoredAttachmentResource;
import com.mendez.ram.auth.dto.AuthTokenResponse;
import com.mendez.ram.auth.dto.LoginRequest;
import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.entity.ClaimStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Import({ TestcontainersConfiguration.class, AttachmentControllerIntegrationTest.AttachmentStorageTestConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AttachmentControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AttachmentProperties attachmentProperties;

	@Autowired
	private RecordingAttachmentStorage recordingStorage;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanStorageRoot() throws IOException {
		recordingStorage.reset();
		deleteRecursively(attachmentProperties.getLocalStorageRoot().toAbsolutePath().normalize());
	}

	@Test
	void uploadsListsDownloadsAndDeletesMultipleAttachmentsKeepingFolderStructure() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments happy path");

		MvcResult uploaded = mockMvc.perform(upload(userToken, claimId,
				textFile("files", "invoice.txt", "Factura"),
				textFile("files", "notes.txt", "Notas"))
				.param("relativePaths", "docs/2026/invoice.txt", "docs/2026/sub/notes.txt"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$[0].fileName").value("invoice.txt"))
				.andExpect(jsonPath("$[0].relativePath").value("docs/2026/invoice.txt"))
				.andExpect(jsonPath("$[0].contentType").value("text/plain"))
				.andExpect(jsonPath("$[0].sizeBytes").value(7))
				.andExpect(jsonPath("$[1].relativePath").value("docs/2026/sub/notes.txt"))
				.andReturn();
		UUID firstAttachmentId = extractUuid(uploaded, 0);

		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].relativePath").value("docs/2026/invoice.txt"))
				.andExpect(jsonPath("$[1].relativePath").value("docs/2026/sub/notes.txt"));

		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments/" + firstAttachmentId + "/content")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("invoice.txt")))
				.andExpect(content().string("Factura"));

		mockMvc.perform(delete("/api/v1/claims/" + claimId + "/attachments/" + firstAttachmentId)
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments/" + firstAttachmentId + "/content")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
	}

	@Test
	void uploadWithoutRelativePathsUsesOriginalFilenameAndAllowsOctetStreamWhenContentMatches() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments optional relative paths");

		mockMvc.perform(upload(userToken, claimId,
				new MockMultipartFile("files", "plain.txt", "application/octet-stream",
						"Plain content".getBytes(StandardCharsets.UTF_8))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$[0].fileName").value("plain.txt"))
				.andExpect(jsonPath("$[0].relativePath").value("plain.txt"))
				.andExpect(jsonPath("$[0].contentType").value("application/octet-stream"));
	}

	@Test
	void exposesUploadCapabilitiesForBatchingClients() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments capabilities");

		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments/capabilities")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.maxFileSizeBytes").value(1024))
				.andExpect(jsonPath("$.maxRequestSizeBytes").value(2048))
				.andExpect(jsonPath("$.maxFilesPerRequest").value(3));
	}

	@Test
	void duplicateRelativePathsAreKeptWithStableSuffixes() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments duplicate path");

		mockMvc.perform(upload(userToken, claimId,
				textFile("files", "report.txt", "Uno"),
				textFile("files", "report.txt", "Dos"))
				.param("relativePaths", "folder/report.txt", "folder/report.txt"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$[0].relativePath").value("folder/report.txt"))
				.andExpect(jsonPath("$[1].relativePath").value("folder/report (1).txt"));
	}

	@Test
	void concurrentUploadsWithSameRelativePathAreSerializedAndSuffixed() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments concurrent duplicate path");
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<Integer> first = executor.submit(() -> uploadRaceFile(userToken, claimId, start, "Uno"));
			Future<Integer> second = executor.submit(() -> uploadRaceFile(userToken, claimId, start, "Dos"));
			start.countDown();

			assertThat(List.of(first.get(), second.get())).containsOnly(201);
		}
		finally {
			executor.shutdownNow();
		}

		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].relativePath", containsInAnyOrder("folder/race.txt", "folder/race (1).txt")));
	}

	@Test
	void rejectsTraversalTooLargeAndKnownSignatureMismatch() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments validation");

		mockMvc.perform(upload(userToken, claimId, textFile("files", "secret.txt", "No"))
				.param("relativePaths", "../secret.txt"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT_PATH"));

		mockMvc.perform(upload(userToken, claimId, new MockMultipartFile("files", "huge.txt", "text/plain",
				"x".repeat(1200).getBytes(StandardCharsets.UTF_8)))
				.param("relativePaths", "huge.txt"))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_FILE_TOO_LARGE"));

		mockMvc.perform(upload(userToken, claimId, new MockMultipartFile("files", "fake.png", "image/png",
				"not really a png".getBytes(StandardCharsets.UTF_8)))
				.param("relativePaths", "fake.png"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_TYPE_NOT_ALLOWED"));
	}

	@Test
	void acceptsGenericUnknownFormatWithUnknownExtension() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments unknown format");

		mockMvc.perform(upload(userToken, claimId,
				new MockMultipartFile("files", "model.never-seen-before", "application/x-new-format",
						new byte[] { 0x13, 0x37, 0x42, 0x00, 0x55 }))
				.param("relativePaths", "design/model.never-seen-before"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$[0].fileName").value("model.never-seen-before"))
				.andExpect(jsonPath("$[0].relativePath").value("design/model.never-seen-before"))
				.andExpect(jsonPath("$[0].contentType").value("application/x-new-format"));
	}

	@Test
	void acceptsRealXlsSignatureWithoutRequiringBrowserMimeVariant() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments xls");

		mockMvc.perform(upload(userToken, claimId,
				new MockMultipartFile("files", "legacy.xls", "application/x-ole-storage", oleCompoundBytes()))
				.param("relativePaths", "spreadsheets/legacy.xls"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$[0].relativePath").value("spreadsheets/legacy.xls"))
				.andExpect(jsonPath("$[0].contentType").value("application/x-ole-storage"));
	}

	@Test
	void acceptsPptxAndGenericBinaryArchiveFormats() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments office and archives");

		mockMvc.perform(upload(userToken, claimId,
				new MockMultipartFile("files", "deck.pptx",
						"application/vnd.openxmlformats-officedocument.presentationml.presentation", zipBytes()),
				new MockMultipartFile("files", "bundle.7z", "application/octet-stream", sevenZipBytes()))
				.param("relativePaths", "presentations/deck.pptx", "archives/bundle.7z"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$[0].relativePath").value("presentations/deck.pptx"))
				.andExpect(jsonPath("$[1].relativePath").value("archives/bundle.7z"));
	}

	@Test
	void acceptsMissingBrowserMimeOctetStreamUnknownExtensionAndNoExtension() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments generic content types");

		mockMvc.perform(upload(userToken, claimId,
				new MockMultipartFile("files", "no-mime.asset", null, new byte[] { 1, 2, 3, 4 }),
				new MockMultipartFile("files", "octet.payload", "application/octet-stream", new byte[] { 5, 6, 7 }),
				new MockMultipartFile("files", "README", null, "plain".getBytes(StandardCharsets.UTF_8)))
				.param("relativePaths", "assets/no-mime.asset", "assets/octet.payload", "README"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$[0].contentType").value("application/octet-stream"))
				.andExpect(jsonPath("$[1].contentType").value("application/octet-stream"))
				.andExpect(jsonPath("$[2].relativePath").value("README"))
				.andExpect(jsonPath("$[2].contentType").value("application/octet-stream"));
	}

	@Test
	void rejectsMissingPartsPathMismatchAndRequestLimits() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments request shape");

		mockMvc.perform(multipart("/api/v1/claims/" + claimId + "/attachments")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_REQUIRED"));

		mockMvc.perform(upload(userToken, claimId, textFile("files", "one.txt", "Uno"))
				.param("relativePaths", "one.txt", "extra.txt"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT_PATH"));

		mockMvc.perform(upload(userToken, claimId,
				textFile("files", "one.txt", "Uno"),
				textFile("files", "two.txt", "Dos"),
				textFile("files", "three.txt", "Tres"),
				textFile("files", "four.txt", "Cuatro")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_LIMIT_EXCEEDED"));

		String largeText = "x".repeat(800);
		mockMvc.perform(upload(userToken, claimId,
				textFile("files", "one.txt", largeText),
				textFile("files", "two.txt", largeText),
				textFile("files", "three.txt", largeText)))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_REQUEST_TOO_LARGE"));

		mockMvc.perform(post("/api/v1/claims/" + claimId + "/attachments")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.parseMediaType("multipart/form-data; boundary=broken"))
						.content("--broken\r\nthis is not a valid part\r\n".getBytes(StandardCharsets.UTF_8)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void multiUploadRollbackRemovesEarlierStoredFilesWhenLaterFileFails() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments rollback cleanup");

		mockMvc.perform(upload(userToken, claimId,
				textFile("files", "ok.txt", "Ok"),
				new MockMultipartFile("files", "fake.png", "image/png",
						"not a png".getBytes(StandardCharsets.UTF_8)))
				.param("relativePaths", "ok.txt", "fake.png"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_TYPE_NOT_ALLOWED"));

		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
		assertThat(Files.exists(claimStorageDirectory(claimId))).isFalse();
	}

	@Test
	void deleteCommitsMetadataEvenIfPhysicalCleanupFailsAfterCommit() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(userToken, "Attachments delete cleanup failure");
		MvcResult uploaded = mockMvc.perform(upload(userToken, claimId, textFile("files", "delete.txt", "Delete me"))
				.param("relativePaths", "delete.txt"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID attachmentId = extractUuid(uploaded, 0);

		recordingStorage.failNextDelete();
		mockMvc.perform(delete("/api/v1/claims/" + claimId + "/attachments/" + attachmentId)
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isNoContent());

		assertThat(recordingStorage.failedDeletes()).isEqualTo(1);
		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments/" + attachmentId + "/content")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
		assertThat(Files.exists(claimStorageDirectory(claimId))).isTrue();
	}

	@Test
	void userCannotAccessAnotherUsersClaimAttachments() throws Exception {
		String managerToken = accessToken("manager@local.dev", "DevManager123!");
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = createClaim(managerToken, "Manager private attachment");
		MvcResult uploaded = mockMvc.perform(upload(managerToken, claimId, textFile("files", "manager.txt", "Privado"))
				.param("relativePaths", "manager.txt"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID attachmentId = extractUuid(uploaded, 0);

		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));

		mockMvc.perform(get("/api/v1/claims/" + claimId + "/attachments/" + attachmentId + "/content")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));

		mockMvc.perform(delete("/api/v1/claims/" + claimId + "/attachments/" + attachmentId)
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
	}

	@Test
	void finalClaimDoesNotAllowUploadOrDelete() throws Exception {
		String managerToken = accessToken("manager@local.dev", "DevManager123!");
		MvcResult created = createClaimResult(managerToken, "Attachments final claim");
		Long claimId = extractLong(created, "id");
		Long version = extractLong(created, "version");
		MvcResult uploaded = mockMvc.perform(upload(managerToken, claimId, textFile("files", "final.txt", "Final"))
				.param("relativePaths", "final.txt"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID attachmentId = extractUuid(uploaded, 0);
		version = changeStatus(managerToken, claimId, ClaimStatus.REGISTERED, version);
		version = changeStatus(managerToken, claimId, ClaimStatus.UNDER_REVIEW, version);
		changeStatus(managerToken, claimId, ClaimStatus.ACCEPTED, version);

		mockMvc.perform(upload(managerToken, claimId, textFile("files", "blocked.txt", "Blocked"))
				.param("relativePaths", "blocked.txt"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CLAIM_NOT_EDITABLE"));

		mockMvc.perform(delete("/api/v1/claims/" + claimId + "/attachments/" + attachmentId)
						.header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CLAIM_NOT_EDITABLE"));
	}

	@Test
	void claimAttachmentDatabaseConstraintsArePresent() {
		List<String> constraints = jdbcTemplate.queryForList("""
				select conname
				from pg_constraint
				where conrelid = 'claim_attachments'::regclass
				""", String.class);

		assertThat(constraints).contains(
				"fk_claim_attachments_claim",
				"fk_claim_attachments_created_by",
				"ck_claim_attachments_size_non_negative",
				"ck_claim_attachments_sha256",
				"ux_claim_attachments_claim_relative_path",
				"ux_claim_attachments_storage_key");
	}

	private MockMultipartHttpServletRequestBuilder upload(String token, Long claimId, MockMultipartFile... files) {
		MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/claims/" + claimId + "/attachments");
		for (MockMultipartFile file : files) {
			request.file(file);
		}
		return request.header(HttpHeaders.AUTHORIZATION, bearer(token));
	}

	private MockMultipartFile textFile(String name, String fileName, String content) {
		return new MockMultipartFile(name, fileName, "text/plain", content.getBytes(StandardCharsets.UTF_8));
	}

	private byte[] oleCompoundBytes() {
		return new byte[] {
				(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1,
				0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
	}

	private byte[] zipBytes() {
		return new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00 };
	}

	private byte[] sevenZipBytes() {
		return new byte[] { 0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0x00, 0x04 };
	}

	private Long createClaim(String token, String title) throws Exception {
		return extractLong(createClaimResult(token, title), "id");
	}

	private MvcResult createClaimResult(String token, String title) throws Exception {
		return mockMvc.perform(post("/api/v1/claims")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new CreateClaimRequest(
						title,
						"Reclamacion generada por test de adjuntos.",
						"Cliente Test"))))
				.andExpect(status().isCreated())
				.andExpect(header().string(HttpHeaders.LOCATION, startsWith("http://localhost/api/v1/claims/")))
				.andReturn();
	}

	private int uploadRaceFile(String token, Long claimId, CountDownLatch start, String content) throws Exception {
		start.await();
		return mockMvc.perform(upload(token, claimId, textFile("files", "race.txt", content))
				.param("relativePaths", "folder/race.txt"))
				.andReturn()
				.getResponse()
				.getStatus();
	}

	private Long changeStatus(String token, Long claimId, ClaimStatus targetStatus, Long version) throws Exception {
		MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.patch("/api/v1/claims/" + claimId + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeClaimStatusRequest(targetStatus, version))))
				.andExpect(status().isOk())
				.andReturn();
		return extractLong(result, "version");
	}

	private String accessToken(String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), AuthTokenResponse.class).accessToken();
	}

	private Long extractLong(MvcResult result, String fieldName) {
		try {
			return objectMapper.readTree(result.getResponse().getContentAsString()).get(fieldName).asLong();
		}
		catch (Exception exception) {
			throw new AssertionError("Could not extract " + fieldName, exception);
		}
	}

	private UUID extractUuid(MvcResult result, int index) {
		try {
			String id = objectMapper.readTree(result.getResponse().getContentAsString()).get(index).get("id").asText();
			return UUID.fromString(id);
		}
		catch (Exception exception) {
			throw new AssertionError("Could not extract attachment id", exception);
		}
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private Path claimStorageDirectory(Long claimId) {
		return attachmentProperties.getLocalStorageRoot()
				.toAbsolutePath()
				.normalize()
				.resolve("claims")
				.resolve(claimId.toString());
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class AttachmentStorageTestConfiguration {

		@Bean
		@Primary
		RecordingAttachmentStorage recordingAttachmentStorage(AttachmentProperties properties) {
			return new RecordingAttachmentStorage(new LocalAttachmentStorage(properties));
		}
	}

	static class RecordingAttachmentStorage implements AttachmentStorage {

		private final AttachmentStorage delegate;
		private boolean failNextDelete;
		private int failedDeletes;

		RecordingAttachmentStorage(AttachmentStorage delegate) {
			this.delegate = delegate;
		}

		@Override
		public StoredAttachment store(StoreAttachmentCommand command) {
			return delegate.store(command);
		}

		@Override
		public StoredAttachmentResource load(String storageKey) {
			return delegate.load(storageKey);
		}

		@Override
		public void delete(String storageKey) {
			if (failNextDelete) {
				failNextDelete = false;
				failedDeletes++;
				throw new IllegalStateException("Simulated attachment delete failure");
			}
			delegate.delete(storageKey);
		}

		void failNextDelete() {
			this.failNextDelete = true;
		}

		int failedDeletes() {
			return failedDeletes;
		}

		void reset() {
			failNextDelete = false;
			failedDeletes = 0;
		}
	}
}
