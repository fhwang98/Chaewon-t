package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.fhwang.chaewon_t.extract.HocrParser.OcrLine;
import cloud.fhwang.chaewon_t.extract.HocrParser.OcrWord;
import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;
import cloud.fhwang.chaewon_t.template.PdfLayout;

/** 300dpi 기준: 1pt = 300/72 px. 계산을 쉽게 하려고 scale을 0.5(1px = 0.5pt)로 둔다 */
class OcrLinesTest {

	static final float SCALE = 0.5f;
	static final PdfLayout LAYOUT = new PdfLayout(297.5f, 60f, 800f, 240f);

	@Test
	void 머리글_꼬리글_구역과_확신도_낮은_줄은_뺀다() {
		List<OcrLine> ocr = List.of(
				line(100, 80, 30, word("Header", 100, 160, 95)),   // 기준선 40pt: 머리글
				line(100, 200, 30, word("Body", 100, 150, 95)),    // 100pt: 본문
				line(100, 300, 30, word("rx", 100, 120, 10)),      // 확신도 10: 상자 장식
				line(100, 1700, 30, word("- 1 -", 100, 150, 95))); // 850pt: 꼬리글

		List<PdfLine> lines = OcrLines.toPdfLines(1, 0, ocr, SCALE, 0, LAYOUT, List.of());

		assertThat(lines).extracting(PdfLine::text).containsExactly("Body");
		assertThat(lines.get(0).x()).isEqualTo(50f);
		assertThat(lines.get(0).y()).isEqualTo(100f);
	}

	@Test
	void 음절마다_나뉜_한글은_붙이고_멀리_떨어진_선택지는_공백_3칸() {
		// 한글 글자 높이 30px: 16px(0.54배)보다 가까우면 붙인다. 줄 높이 40px의 1.5배(60px)보다 멀면 공백 3칸
		List<OcrLine> ocr = List.of(line(0, 200, 40,
				word("다", 0, 30, 90), word("음", 40, 70, 90), word("글", 100, 130, 90),
				word("①", 300, 330, 90), word("two", 340, 400, 90)));

		PdfLine line = OcrLines.toPdfLines(1, 0, ocr, SCALE, 0, LAYOUT, List.of()).get(0);

		assertThat(line.text()).isEqualTo("다음 글   ① two");
	}

	@Test
	void 오른쪽_단은_잘라_낸_위치만큼_x를_옮기고_밑줄을_찾는다() {
		List<OcrLine> ocr = List.of(line(0, 200, 30, word("is", 0, 20, 90), word("not", 30, 60, 90)));
		// "not": 이미지 x 30~60 + 단 시작 600px → 630~660px → 315~330pt. 기준선 100pt 바로 아래 선
		List<UnderlineDetector.Segment> segments = List.of(new UnderlineDetector.Segment(315, 330, 101.5f));

		PdfLine line = OcrLines.toPdfLines(1, 1, ocr, SCALE, 600, LAYOUT, segments).get(0);

		assertThat(line.column()).isEqualTo(1);
		assertThat(line.x()).isEqualTo(300f);
		String underlined = line.glyphs().stream().filter(Glyph::underline).map(Glyph::text).reduce("", String::concat);
		assertThat(underlined).isEqualTo("not");
	}

	@Test
	void 확신도_70_미만_단어는_확인_필요로_표시한다() {
		List<OcrLine> ocr = List.of(line(0, 200, 30, word("sure", 0, 40, 95), word("maybe", 50, 100, 65)));

		PdfLine line = OcrLines.toPdfLines(1, 0, ocr, SCALE, 0, LAYOUT, List.of()).get(0);

		String uncertain = line.glyphs().stream().filter(Glyph::uncertain).map(Glyph::text).reduce("", String::concat);
		assertThat(uncertain).isEqualTo("maybe");
	}

	@Test
	void 머리글은_아래_끝에서_잘린_줄을_뺀다() {
		List<OcrLine> ocr = List.of(
				line(0, 50, 30, word("26년", 0, 50, 90), word("다라고2", 70, 150, 90)),
				line(0, 99, 30, word("잘린", 0, 50, 90)));

		assertThat(OcrLines.headerText(ocr, 100)).isEqualTo("26년 다라고2");
	}

	private static OcrLine line(float x0, float baseline, float height, OcrWord... words) {
		List<OcrWord> list = new ArrayList<>(List.of(words));
		float x1 = list.get(list.size() - 1).x1();
		return new OcrLine(x0, baseline - height, x1, baseline, baseline, height, list);
	}

	/** 단어 높이는 30px */
	private static OcrWord word(String text, float x0, float x1, int confidence) {
		return new OcrWord(text, x0, 0, x1, 30, confidence);
	}
}
