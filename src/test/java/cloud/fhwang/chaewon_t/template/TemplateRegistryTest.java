package cloud.fhwang.chaewon_t.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cloud.fhwang.chaewon_t.common.AppProperties;
import cloud.fhwang.chaewon_t.common.ConversionException;
import cloud.fhwang.chaewon_t.common.ErrorType;

class TemplateRegistryTest {

	@TempDir
	Path tmp;

	TemplateRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new TemplateRegistry(new AppProperties(tmp, tmp.resolve("work"), Duration.ofHours(1), 100, "exam", AppProperties.Ocr.defaults()));
	}

	@Test
	void 프로젝트의_mapping_json을_읽는다() throws Exception {
		Path dir = Files.createDirectories(tmp.resolve("exam/v1"));
		Files.copy(Path.of("data/templates/exam/v1/mapping.json"), dir.resolve("mapping.json"));

		Template t = registry.defaultTemplate();

		assertThat(t.mapping().styles().question().paraPr()).isEqualTo("11");
		assertThat(t.mapping().styles().chars().boldUnderline()).isEqualTo("19");
		assertThat(t.mapping().pdf().columnSplit()).isEqualTo(297.5f);
		assertThat(t.hwpxPath()).isEqualTo(tmp.resolve("exam/v1/template.hwpx").toAbsolutePath());
	}

	@Test
	void 가장_큰_버전을_쓴다() throws Exception {
		writeMapping(1, validMapping(1));
		writeMapping(10, validMapping(10));
		writeMapping(2, validMapping(2));

		assertThat(registry.get("exam").mapping().version()).isEqualTo(10);
	}

	@Test
	void 스타일_id가_비면_오류() throws Exception {
		writeMapping(1, validMapping(1).replace("\"bold\": \"14\"", "\"bold\": \"\""));

		assertThatThrownBy(() -> registry.get("exam"))
				.isInstanceOf(ConversionException.class)
				.hasMessageContaining("styles.chars")
				.extracting("type").isEqualTo(ErrorType.TEMPLATE_INVALID);
	}

	@Test
	void 폴더_번호와_version이_다르면_오류() throws Exception {
		writeMapping(2, validMapping(1));

		assertThatThrownBy(() -> registry.get("exam")).hasMessageContaining("version");
	}

	@Test
	void 규칙에_맞지_않는_종류_이름은_찾지_않는다() {
		assertThatThrownBy(() -> registry.get("../etc"))
				.isInstanceOf(ConversionException.class)
				.extracting("type").isEqualTo(ErrorType.TEMPLATE_NOT_FOUND);
	}

	private void writeMapping(int version, String json) throws Exception {
		Path dir = Files.createDirectories(tmp.resolve("exam").resolve("v" + version));
		Files.writeString(dir.resolve("mapping.json"), json);
	}

	private static String validMapping(int version) {
		return """
				{
				  "documentType": "exam", "version": %d, "displayName": "시험지",
				  "styles": {
				    "question": { "paraPr": "11", "style": "1" }, "body": { "paraPr": "12", "style": "2" },
				    "bodyLast": { "paraPr": "15", "style": "2" }, "gap": { "paraPr": "13", "style": "0" },
				    "choice": { "paraPr": "14", "style": "4" }, "choiceLast": { "paraPr": "16", "style": "4" },
				    "spacer": { "paraPr": "1", "style": "0" }, "answer": { "paraPr": "1", "style": "0" },
				    "chars": { "normal": "12", "underline": "18", "bold": "14", "boldUnderline": "19", "heading": "5" },
				    "answerHeading": "< 정 답 >"
				  },
				  "pdf": { "columnSplit": 297.5, "headerBottom": 60, "footerTop": 800, "boxLineMinWidth": 240 }
				}
				""".formatted(version);
	}
}
