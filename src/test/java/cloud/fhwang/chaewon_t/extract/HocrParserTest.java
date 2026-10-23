package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.fhwang.chaewon_t.extract.HocrParser.OcrLine;
import cloud.fhwang.chaewon_t.extract.HocrParser.OcrWord;

/** Tesseract 5.5 hOCR 출력 모양 그대로 (DOCTYPE 포함, 외부 DTD는 읽지 않아야 한다) */
class HocrParserTest {

	static final String HOCR = """
			<?xml version="1.0" encoding="UTF-8"?>
			<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
			    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
			<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
			 <body>
			  <div class='ocr_page' id='page_1' title='image "a.png"; bbox 0 0 1241 3084; ppageno 0; scan_res 300 300'>
			   <div class='ocr_carea' id='block_1_1' title="bbox 47 33 157 64">
			    <p class='ocr_par' id='par_1_1' lang='eng' title="bbox 47 33 157 64">
			     <span class='ocr_line' id='line_1_1' title="bbox 47 33 157 64; baseline 0.009 -5; x_size 30; x_descenders 4; x_ascenders 9">
			      <span class='ocrx_word' id='word_1_1' title='bbox 47 33 88 60; x_wconf 66'>2.</span>
			      <span class='ocrx_word' id='word_1_2' title='bbox 105 43 157 64; x_wconf 78'>it&#39;s</span>
			     </span>
			     <span class='ocr_header' id='line_1_2' title="bbox 50 80 90 110; baseline 0 0; x_size 28">
			      <span class='ocrx_word' id='word_1_3' title='bbox 50 80 90 110; x_wconf 90' lang='kor'>정답</span>
			     </span>
			     <span class='ocr_line' id='line_1_3' title="bbox 50 120 90 150; baseline 0 0; x_size 28">
			      <span class='ocrx_word' id='word_1_4' title='bbox 50 120 90 150; x_wconf 90'> </span>
			     </span>
			    </p>
			   </div>
			  </div>
			 </body>
			</html>
			""";

	@Test
	void 줄과_단어_좌표와_기준선을_읽는다() throws Exception {
		List<OcrLine> lines = HocrParser.parse(new ByteArrayInputStream(HOCR.getBytes(StandardCharsets.UTF_8)));

		assertThat(lines).hasSize(2); // 글자 없는 줄은 뺀다
		OcrLine first = lines.get(0);
		assertThat(first.baseline()).isEqualTo(59f); // bbox 아래 64 + (-5)
		assertThat(first.height()).isEqualTo(30f);
		assertThat(first.words()).containsExactly(
				new OcrWord("2.", 47, 33, 88, 60, 66),
				new OcrWord("it's", 105, 43, 157, 64, 78));
		assertThat(lines.get(1).words()).extracting(OcrWord::text).containsExactly("정답"); // ocr_header도 줄
	}

	@Test
	void 텍스트_출력에서_단어_사이_공백_수를_가져온다() {
		List<OcrLine> lines = List.of(
				line("33.", "어", "법", "에", "맞는"),
				line("Many", "animals"));

		List<OcrLine> spaced = HocrParser.withSpaces(lines, "33. 어법에 맞는\n\nMany   animals\n");

		assertThat(spaced.get(0).words()).extracting(OcrWord::spacesBefore).containsExactly(0, 1, 0, 0, 1);
		assertThat(spaced.get(1).words()).extracting(OcrWord::spacesBefore).containsExactly(0, 3);
	}

	@Test
	void 백틱은_작은따옴표로_바꾼다() {
		assertThat(HocrParser.cleanText("`어미 고양이가´")).isEqualTo("'어미 고양이가'");
	}

	@Test
	void 줄_수나_글자가_다르면_공백을_모름으로_둔다() {
		List<OcrLine> lines = List.of(line("다", "음"), line("글"));

		assertThat(HocrParser.withSpaces(lines, "다음\n").get(0).words())
				.extracting(OcrWord::spacesBefore).containsExactly(-1, -1);
		assertThat(HocrParser.withSpaces(lines, "다른\n글\n").get(0).words())
				.extracting(OcrWord::spacesBefore).containsExactly(-1, -1);
		assertThat(HocrParser.withSpaces(lines, "다음\n글\n").get(1).words())
				.extracting(OcrWord::spacesBefore).containsExactly(0);
	}

	private static OcrLine line(String... words) {
		List<OcrWord> list = java.util.Arrays.stream(words).map(w -> new OcrWord(w, 0, 0, 10, 10, 90)).toList();
		return new OcrLine(0, 0, 100, 10, 10, 10, list);
	}
}
