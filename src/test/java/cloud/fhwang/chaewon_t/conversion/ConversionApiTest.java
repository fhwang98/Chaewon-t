package cloud.fhwang.chaewon_t.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * API 흐름: 업로드 → 조회 → 저장(PUT) → 생성 → 내려받기, 그리고 오류 응답(ProblemDetail).
 * 템플릿은 src/test/resources/exam-template.hwpx(축소본) + 프로젝트의 mapping.json.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversionApiTest {

	static final Path ROOT = tempDir();

	@DynamicPropertySource
	static void dirs(DynamicPropertyRegistry registry) throws IOException {
		Path version = Files.createDirectories(ROOT.resolve("templates/exam/v1"));
		Files.copy(Path.of("data/templates/exam/v1/mapping.json"), version.resolve("mapping.json"));
		try (InputStream in = ConversionApiTest.class.getResourceAsStream("/exam-template.hwpx")) {
			Files.copy(in, version.resolve("template.hwpx"));
		}
		registry.add("chaewon.template-dir", () -> ROOT.resolve("templates").toString());
		registry.add("chaewon.tmp-dir", () -> ROOT.resolve("tmp").toString());
	}

	@Autowired
	MockMvc mvc;

	@Test
	void 업로드_조회_저장_생성_내려받기() throws Exception {
		String body = mvc.perform(multipart("/api/conversions").file(pdf()))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", endsWith("/status")))
				.andExpect(jsonPath("$.state").value("PROCESSING"))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");
		awaitReady(id);

		mvc.perform(get("/api/conversions/" + id + "/status"))
				.andExpect(jsonPath("$.state").value("READY"))
				.andExpect(jsonPath("$.totalPages").value(1))
				.andExpect(jsonPath("$.ocrPages").value(0));

		mvc.perform(get("/api/conversions/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.document.headerTitle").value("Test Exam"))
				.andExpect(jsonPath("$.document.questions.length()").value(2))
				.andExpect(jsonPath("$.document.questions[0].prompt.spans[0].text").value("Choose the best title."))
				.andExpect(jsonPath("$.document.questions[0].source").value("1-1"))
				.andExpect(jsonPath("$.warnings").isArray())
				.andExpect(jsonPath("$.hasResult").value(false));

		mvc.perform(get("/api/conversions/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.document.questions[1].prompt.spans[0].text").value("Write the answer."));

		mvc.perform(put("/api/conversions/" + id + "/document").contentType(MediaType.APPLICATION_JSON).content("""
				{"headerTitle": "26년 테스트고1 (2학기 중간)",
				 "questions": [{"number": 1, "source": "1-1",
				   "prompt": {"spans": [{"text": "New prompt", "underline": false, "bold": false}]},
				   "body": [{"paragraphs": [{"spans": [{"text": "Edited", "underline": true, "bold": true}]}]}],
				   "choices": []}],
				 "answers": [{"number": 1, "answer": "a", "explanation": "because"}]}
				"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.document.questions[0].body[0].paragraphs[0].spans[0].bold").value(true));

		mvc.perform(post("/api/conversions/" + id + "/render")).andExpect(status().isNoContent());
		mvc.perform(get("/api/conversions/" + id)).andExpect(jsonPath("$.hasResult").value(true));

		byte[] hwpx = mvc.perform(get("/api/conversions/" + id + "/download"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
				.andExpect(header().string("Content-Disposition", containsString(".hwpx")))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(hwpx, 0, 2)).isEqualTo("PK");

		// 다시 저장하면 이전 결과는 지워진다
		mvc.perform(put("/api/conversions/" + id + "/document").contentType(MediaType.APPLICATION_JSON)
				.content("{\"headerTitle\": \"t\", \"questions\": [], \"answers\": []}"))
				.andExpect(jsonPath("$.hasResult").value(false))
				.andExpect(jsonPath("$.warnings[0]").value("문항을 찾지 못했습니다."));
	}

	@Test
	void 만들기_전에_내려받으면_409() throws Exception {
		String id = uploadId();

		mvc.perform(get("/api/conversions/" + id + "/download"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorType").value("RESULT_NOT_READY"));
	}

	@Test
	void PDF가_아니면_ProblemDetail로_400() throws Exception {
		mvc.perform(multipart("/api/conversions").file(new MockMultipartFile("file", "a.pdf", "application/pdf", "hello".getBytes())))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errorType").value("INVALID_FILE"))
				.andExpect(jsonPath("$.detail").value("PDF 파일이 아닙니다."));
	}

	@Test
	void 파일_없이_올리면_400() throws Exception {
		mvc.perform(multipart("/api/conversions"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void 없는_작업이나_잘못된_id는_404() throws Exception {
		mvc.perform(get("/api/conversions/" + UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorType").value("JOB_NOT_FOUND"));
		mvc.perform(get("/api/conversions/not-a-uuid"))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/conversions/..%2F..%2Fetc"))
				.andExpect(status().is4xxClientError());
	}

	@Test
	void 너무_큰_문서는_400() throws Exception {
		String id = uploadId();
		String question = "{\"number\":1,\"prompt\":{\"spans\":[]},\"body\":[],\"choices\":[]}";
		String questions = String.join(",", java.util.Collections.nCopies(301, question));

		mvc.perform(put("/api/conversions/" + id + "/document").contentType(MediaType.APPLICATION_JSON)
				.content("{\"headerTitle\":\"t\",\"questions\":[" + questions + "],\"answers\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void 작업을_취소하면_지워지고_상태가_404() throws Exception {
		String id = uploadId();

		mvc.perform(delete("/api/conversions/" + id)).andExpect(status().isNoContent());
		mvc.perform(get("/api/conversions/" + id + "/status")).andExpect(status().isNotFound());
		mvc.perform(delete("/api/conversions/" + id)).andExpect(status().isNoContent()); // 두 번 눌러도 된다
	}

	@Test
	void 없는_작업의_상태는_404() throws Exception {
		mvc.perform(get("/api/conversions/" + UUID.randomUUID() + "/status"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorType").value("JOB_NOT_FOUND"));
	}

	private String uploadId() throws Exception {
		String body = mvc.perform(multipart("/api/conversions").file(pdf()))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");
		awaitReady(id);
		return id;
	}

	/** 읽기는 백그라운드에서 돈다. 텍스트 PDF 한 쪽이라 금방 끝난다 */
	private void awaitReady(String id) throws Exception {
		long deadline = System.currentTimeMillis() + 10_000;
		while (true) {
			String body = mvc.perform(get("/api/conversions/" + id + "/status"))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			String state = JsonPath.read(body, "$.state");
			if (!state.equals("PROCESSING")) {
				assertThat(state).isEqualTo("READY");
				return;
			}
			assertThat(System.currentTimeMillis()).as("읽기가 10초 안에 끝나야 한다").isLessThan(deadline);
			Thread.sleep(50);
		}
	}

	/** 샘플과 같은 배치의 영문 시험지: 머리글, 문항 2개 (기본 글꼴이라 한글·①은 못 쓴다) */
	private static MockMultipartFile pdf() throws IOException {
		try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			float h = page.getMediaBox().getHeight();
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				text(cs, font, h, 48, 40, "Test Exam");
				text(cs, font, h, 48, 85, "1. Choose the best title. [1-1]");
				text(cs, font, h, 59, 101, "Fake news spreads fast.");
				text(cs, font, h, 48, 150, "2. Write the answer. [1-2]");
				text(cs, font, h, 59, 166, "People share news.");
			}
			doc.save(out);
			return new MockMultipartFile("file", "exam.pdf", "application/pdf", out.toByteArray());
		}
	}

	private static void text(PDPageContentStream cs, PDType1Font font, float h, float x, float y, String s)
			throws IOException {
		cs.beginText();
		cs.setFont(font, 10);
		cs.newLineAtOffset(x, h - y);
		cs.showText(s);
		cs.endText();
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("chaewon-api-test");
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
