package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;

/** 오인식 모양은 sample2·3을 Tesseract(kor+eng best)로 읽은 결과에서 가져왔다 */
class OcrMarksTest {

	@Test
	void 숫자가_안_보이는_번호는_앞_번호_다음으로() {
		List<PdfLine> lines = List.of(
				line(1, "1. Which is right? [33]"),
				line(1, "® the first"),
				line(1, "(2) the second"),
				line(1, "3) the third"),
				line(1, "@ the fourth"),
				line(1, "(9) the fifth"),
				line(1, "2. Next question [34]"),
				line(1, "(D starts again"));

		assertThat(texts(OcrMarks.normalize(lines, Set.of(1)))).containsExactly(
				"1. Which is right? [33]",
				"① the first", "② the second", "③ the third", "④ the fourth", "⑤ the fifth",
				"2. Next question [34]",
				"① starts again");
	}

	@Test
	void 순서로_짐작한_번호만_확인_필요로_표시한다() {
		List<PdfLine> lines = OcrMarks.normalize(List.of(line(1, "® first"), line(1, "(2) second")), Set.of(1));

		assertThat(lines.get(0).glyphs().get(0)).extracting(Glyph::text, Glyph::uncertain).containsExactly("①", true);
		assertThat(lines.get(1).glyphs().get(0)).extracting(Glyph::text, Glyph::uncertain).containsExactly("②", false);
	}

	@Test
	void 한_줄에_선택지가_둘이면_넓은_공백_뒤도_고친다() {
		List<PdfLine> lines = List.of(
				line(1, "(3) Simultaneously   @ In contrast"),
				line(1, "(5) Therefore"));

		assertThat(texts(OcrMarks.normalize(lines, Set.of(1))))
				.containsExactly("③ Simultaneously   ④ In contrast", "⑤ Therefore");
	}

	@Test
	void 지문_속_괄호는_건드리지_않는다() {
		List<PdfLine> lines = List.of(line(1, "® (A) - (©) - (B)"));

		assertThat(texts(OcrMarks.normalize(lines, Set.of(1)))).containsExactly("① (A) - (©) - (B)");
	}

	@Test
	void 지문_속_괄호_없는_기호는_밑줄_문제의_번호로_본다() {
		List<PdfLine> lines = List.of(
				line(1, "16. 다음 글의 밑줄 친 부분 중, 어법상 틀린 것은? [37]"),
				line(1, "it is friction © that holds it. The ② wood"),
				line(1, "grips 3) firmly and ®tension, which"),
				line(1, "@ we need."));

		assertThat(texts(OcrMarks.normalize(lines, Set.of(1)))).containsExactly(
				"16. 다음 글의 밑줄 친 부분 중, 어법상 틀린 것은? [37]",
				"it is friction ① that holds it. The ② wood",
				"grips ③ firmly and ④ tension, which",
				"⑤ we need.");
	}

	@Test
	void 정답_줄은_숫자가_보이는_번호만_고친다() {
		List<PdfLine> lines = List.of(line(30, "75 번 - (2) 해설"), line(30, "76 번 - @ 해설"), line(30, "63H - (3) 해설"));

		assertThat(texts(OcrMarks.normalize(lines, Set.of(30))))
				.containsExactly("75 번 - ② 해설", "76 번 - @ 해설", "63H - ③ 해설");
	}

	@Test
	void 닫는_괄호를_1로_읽은_출처를_고친다() {
		List<PdfLine> lines = List.of(line(1, "3. 다음 글의 내용과 일치하지 않는 것은? [241"), line(1, "곳은? [41~421"));

		assertThat(texts(OcrMarks.normalize(lines, Set.of(1))))
				.containsExactly("3. 다음 글의 내용과 일치하지 않는 것은? [24]", "곳은? [41~42]");
	}

	@Test
	void OCR이_아닌_페이지는_그대로_둔다() {
		List<PdfLine> lines = List.of(line(2, "(2) real text"), line(2, "[241"));

		assertThat(texts(OcrMarks.normalize(lines, Set.of(1)))).containsExactly("(2) real text", "[241");
	}

	/** 한 글자 = 한 Glyph (OCR 줄과 같은 모양) */
	private static PdfLine line(int page, String text) {
		List<Glyph> glyphs = new ArrayList<>();
		float x = 48;
		for (int cp : text.codePoints().toArray()) {
			glyphs.add(new Glyph(Character.toString(cp), x, 5, false, false));
			x += 5;
		}
		return new PdfLine(page, 0, 48, 100, 10, glyphs);
	}

	private static List<String> texts(List<PdfLine> lines) {
		return lines.stream().map(PdfLine::text).toList();
	}
}
