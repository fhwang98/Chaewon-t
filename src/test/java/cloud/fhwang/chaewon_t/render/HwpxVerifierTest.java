package cloud.fhwang.chaewon_t.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HwpxVerifierTest {

	@TempDir
	Path tmp;

	Path template;
	Path out;

	@BeforeEach
	void setUp() throws Exception {
		template = tmp.resolve("template.hwpx");
		try (var in = getClass().getResourceAsStream("/exam-template.hwpx")) {
			Files.copy(in, template);
		}
		out = tmp.resolve("out.hwpx");
	}

	@Test
	void 없는_id_참조를_찾는다() throws Exception {
		String section = section().replaceFirst("paraPrIDRef=\"12\"", "paraPrIDRef=\"777\"");

		assertThat(verifyWith(section)).containsExactly("Contents/section0.xml: header.xml에 없는 paraPrIDRef=777");
	}

	@Test
	void 남은_자리표시자를_찾는다() throws Exception {
		String section = section().replaceFirst(">dummy ", ">{{name}} ");

		assertThat(verifyWith(section)).containsExactly("Contents/section0.xml: {{ 가 남아 있음");
	}

	@Test
	void 깨진_XML을_찾는다() throws Exception {
		assertThat(verifyWith(section().substring(0, 500))).containsExactly("Contents/section0.xml: XML 파싱 실패");
	}

	private String section() throws Exception {
		return new String(HwpxPackage.read(template, ExamHwpxRenderer.SECTION), StandardCharsets.UTF_8);
	}

	private List<String> verifyWith(String section) throws Exception {
		HwpxPackage.write(template, out, Map.of(ExamHwpxRenderer.SECTION, section.getBytes(StandardCharsets.UTF_8)));
		return HwpxVerifier.verify(out, List.of(ExamHwpxRenderer.SECTION));
	}
}
