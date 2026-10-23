package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.fhwang.chaewon_t.extract.GlyphDecoder.ShapeGlyph;
import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;
import cloud.fhwang.chaewon_t.template.PdfLayout;

/**
 * 모양 표 다수결과 글자 자리로 줄 다시 짜기. PDF 없이 모양 이름을 글자처럼 써서 만든다
 * (모양 "s-c"는 글자 c의 모양, "blank"는 공백 모양). 글자 하나 폭 5pt, 글자 크기 10pt
 */
class GlyphDecoderTest {

	static final PdfLayout LAYOUT = new PdfLayout(297.5f, 60f, 800f, 240f);

	@Test
	void 모양마다_OCR이_가장_많이_읽은_글자로_정한다() {
		GlyphDecoder decoder = new GlyphDecoder();
		// 같은 모양의 "cat"이 네 줄. OCR은 한 번 "c"를 "e"로 잘못 읽었다 (영문은 1등이 75% 이상이어야 확실)
		decoder.learn(glyphs(100, "cat"), List.of(ocr(100, "cat")));
		decoder.learn(glyphs(120, "cat"), List.of(ocr(120, "eat")));
		decoder.learn(glyphs(140, "cat"), List.of(ocr(140, "cat")));
		decoder.learn(glyphs(160, "cat"), List.of(ocr(160, "cat")));

		assertThat(decoder.decode("s-c")).isEqualTo(new GlyphDecoder.Decoded("c", false));
		assertThat(decoder.decode("s-t")).isEqualTo(new GlyphDecoder.Decoded("t", false));
	}

	@Test
	void 줄은_PDF_글자_자리로_다시_짜고_표가_적은_모양은_확인_필요() {
		GlyphDecoder decoder = new GlyphDecoder();
		decoder.learn(glyphs(100, "dogs"), List.of(ocr(100, "dogs")));
		decoder.learn(glyphs(120, "dogs"), List.of(ocr(120, "dogs")));
		decoder.learn(glyphs(140, "x"), List.of(ocr(140, "x"))); // 한 번만 나온 모양

		List<ShapeGlyph> page = new ArrayList<>(glyphs(200, "dog x"));
		List<PdfLine> lines = decoder.lines(1, page, LAYOUT, List.of(), List.of());

		assertThat(lines).hasSize(1);
		assertThat(lines.get(0).text()).isEqualTo("dog x");
		assertThat(lines.get(0).y()).isEqualTo(200f);
		assertThat(lines.get(0).glyphs()).filteredOn(Glyph::uncertain).extracting(Glyph::text).containsExactly("x");
	}

	@Test
	void 표가_없는_모양은_같은_자리의_OCR_글자를_쓰고_확인_필요() {
		GlyphDecoder decoder = new GlyphDecoder();
		List<ShapeGlyph> page = glyphs(200, "q");

		List<PdfLine> lines = decoder.lines(1, page, LAYOUT, List.of(), List.of(ocr(200.5f, "g")));

		assertThat(lines.get(0).glyphs()).extracting(Glyph::text, Glyph::uncertain).containsExactly(
				org.assertj.core.groups.Tuple.tuple("g", true));
	}

	@Test
	void 글자_수가_안_맞는_줄의_드문_모양은_두_번째로_줄_맞춤해_채운다() {
		GlyphDecoder decoder = new GlyphDecoder();
		decoder.learn(glyphs(100, "abc"), List.of(ocr(100, "abc")));
		decoder.learn(glyphs(120, "abc"), List.of(ocr(120, "abc")));
		// "a1bc": OCR이 글자 하나를 더 읽어("a1bbc") 수가 안 맞는다. 모양 1은 이 줄에만 있다
		List<ShapeGlyph> page = glyphs(140, "a1bc");
		decoder.learn(page, List.of(ocr(140, "a1bbc")));
		assertThat(decoder.decode("s-1")).isNull();

		decoder.learnUnknown(page, List.of(ocr(140, "a1bbc")));

		assertThat(decoder.decode("s-1").text()).isEqualTo("1");
	}

	@Test
	void 표도_OCR_글자도_없으면_물음표로_남긴다() {
		GlyphDecoder decoder = new GlyphDecoder();
		assertThat(decoder.lines(1, glyphs(200, "z"), LAYOUT, List.of(), List.of()).get(0).text()).isEqualTo("?");
	}

	@Test
	void OCR이_거의_못_읽는_연달은_모양은_빈칸_밑줄로_정한다() {
		GlyphDecoder decoder = new GlyphDecoder();
		List<ShapeGlyph> page = new ArrayList<>(glyphs(100, "ab______c"));
		page.addAll(glyphs(120, "ab______c"));
		// OCR은 빈칸을 읽지 않았다. 한 줄에서만 "~"를 하나 읽었다
		decoder.learn(page, List.of(ocr(100, "ab"), ocr(120, "ab")));
		decoder.addVotes("s-_", "~", 1);

		decoder.learnRuns(page);
		decoder.settleRuns();

		assertThat(decoder.decode("s-_")).isEqualTo(new GlyphDecoder.Decoded("_", false));
		assertThat(decoder.decode("s-a").text()).isEqualTo("a");
	}

	@Test
	void 글자_수가_같아도_자리가_어긋난_줄은_표를_주지_않는다() {
		GlyphDecoder decoder = new GlyphDecoder();
		List<Glyph> far = List.of(new Glyph("x", 90, 5, false, false), new Glyph("y", 95, 5, false, false));

		decoder.learn(glyphs(100, "ab"), List.of(new PdfLine(1, 0, 48, 100, 10, far)));

		assertThat(decoder.decode("s-a")).isNull();
	}

	@Test
	void 밑줄_친_공백이_이어지면_빈칸_밑줄로_바꾼다() {
		GlyphDecoder decoder = new GlyphDecoder();
		decoder.learn(glyphs(100, "It is"), List.of(ocr(100, "It is")));
		decoder.learn(glyphs(120, "It is"), List.of(ocr(120, "It is")));
		// "It      is": 가운데 공백 6개(x 58~88) 아래에 선 → 빈칸
		List<ShapeGlyph> page = glyphs(200, "It      is");
		List<UnderlineDetector.Segment> line = List.of(new UnderlineDetector.Segment(58, 88, 202));

		String text = decoder.lines(1, page, LAYOUT, line, List.of()).get(0).text();

		assertThat(text).isEqualTo("It________is");
	}

	@Test
	void 멀리_떨어진_글자_사이는_공백_3칸() {
		GlyphDecoder decoder = new GlyphDecoder();
		decoder.learn(glyphs(100, "ab"), List.of(ocr(100, "ab")));
		decoder.learn(glyphs(120, "ab"), List.of(ocr(120, "ab")));
		List<ShapeGlyph> page = List.of(glyph(48, 200, "a"), glyph(120, 200, "b")); // 72pt 떨어짐

		assertThat(decoder.lines(1, page, LAYOUT, List.of(), List.of()).get(0).text()).isEqualTo("a   b");
	}

	/** x=48부터 5pt씩. 공백은 공백 모양 */
	private static List<ShapeGlyph> glyphs(float y, String text) {
		List<ShapeGlyph> glyphs = new ArrayList<>();
		float x = 48;
		for (char c : text.toCharArray()) {
			glyphs.add(glyph(x, y, String.valueOf(c)));
			x += 5;
		}
		return glyphs;
	}

	private static ShapeGlyph glyph(float x, float y, String c) {
		boolean blank = c.isBlank();
		return new ShapeGlyph(blank ? "blank" : "s-" + c, blank, x, y, 10, false);
	}

	/** OCR 줄: 같은 자리에 같은 폭으로 읽었다고 둔다 */
	private static PdfLine ocr(float y, String text) {
		List<Glyph> glyphs = new ArrayList<>();
		float x = 48;
		for (char c : text.toCharArray()) {
			glyphs.add(new Glyph(String.valueOf(c), x, 5, false, false));
			x += 5;
		}
		return new PdfLine(1, 0, 48, y, 10, glyphs);
	}
}
