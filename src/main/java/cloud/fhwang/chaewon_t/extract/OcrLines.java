package cloud.fhwang.chaewon_t.extract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import cloud.fhwang.chaewon_t.extract.HocrParser.OcrLine;
import cloud.fhwang.chaewon_t.extract.HocrParser.OcrWord;
import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;
import cloud.fhwang.chaewon_t.template.PdfLayout;

/**
 * OCR 결과(단 하나를 잘라 낸 이미지의 px 좌표) → 텍스트 PDF와 같은 모양의 줄(pt 좌표).
 * 그 뒤로는 텍스트 PDF와 같은 문항 파싱(ExamParser)·밑줄 판정을 그대로 쓴다.
 *
 * OCR은 글자 단위 좌표를 주지 않으므로 단어 너비를 글자 수로 나눠 글자 좌표를 정한다 (밑줄 판정에는 충분하다).
 * 굵게는 알 수 없어 모두 false.
 */
final class OcrLines {

	/** 단어 사이가 글자 높이의 이 배수보다 벌어지면 공백 3칸 (한 줄에 놓인 선택지 "① …      ② …"를 나누는 자리) */
	private static final float WIDE_GAP = 1.5f;
	/**
	 * 띄어쓰기를 모를 때(텍스트 출력과 줄이 안 맞을 때)만 쓰는 대체 규칙:
	 * 한글 단어 사이가 한글 글자 높이의 이 배수보다 좁으면 붙인다. 한국어 모델은 "다음에"를 "다 음 에"처럼 음절마다 단어로 나누곤 한다.
	 * 기준값은 sample.pdf 8쪽을 OCR해 실제 텍스트와 맞춰 정했다 (2026-10-21, 한글 단어 쌍 421개 중 5개만 틀림).
	 * 줄 높이(x_size) 기준은 20개가 틀렸다: 줄 높이는 영어 대문자·괄호에 따라 달라진다
	 */
	private static final float HANGUL_JOIN = 0.54f;
	/**
	 * 확신도가 이보다 낮은 단어는 "확인 필요"로 표시한다. sample.pdf 8쪽을 OCR해 실제 텍스트와 맞춰 정했다 (2026-10-21):
	 * 단어 5,931개 중 2.6%가 표시되고, 표시된 것의 61%가 실제로 틀린 단어였다 (80이면 50%, 60이면 71%지만 놓치는 것이 많다)
	 */
	private static final int UNCERTAIN_CONFIDENCE = 70;
	/** 단어 확신도 평균이 이보다 낮은 줄은 버린다. 상자 모서리·장식이 "mijn", "rx" 같은 글자로 읽힌다 */
	private static final int MIN_LINE_CONFIDENCE = 30;

	private OcrLines() {
	}

	/**
	 * @param scale   px → pt 배율 (72 / dpi)
	 * @param offsetX 잘라 낸 이미지의 왼쪽 끝 x (원본 페이지 이미지 기준 px)
	 */
	static List<PdfLine> toPdfLines(int page, int column, List<OcrLine> ocr, float scale, float offsetX,
			PdfLayout layout, List<UnderlineDetector.Segment> segments) {
		List<OcrLine> body = ocr.stream()
				.filter(l -> l.baseline() * scale > layout.headerBottom() && l.baseline() * scale < layout.footerTop())
				.filter(l -> confidence(l) >= MIN_LINE_CONFIDENCE)
				.sorted(Comparator.comparingDouble(OcrLine::baseline).thenComparingDouble(OcrLine::x0))
				.toList();
		float textLeft = (float) body.stream().mapToDouble(l -> (l.x0() + offsetX) * scale).min().orElse(0);
		List<UnderlineDetector.Segment> underlines = PdfLayoutReader.underlines(segments, textLeft, layout);

		List<PdfLine> lines = new ArrayList<>();
		for (OcrLine l : body) {
			lines.add(line(page, column, l, scale, offsetX, underlines));
		}
		return lines;
	}

	/**
	 * 머리글 구역(페이지 위쪽을 잘라 OCR한 결과)의 글자를 한 줄로.
	 * 아래 끝에 닿은 줄은 경계에서 잘린 본문 줄이라 뺀다
	 *
	 * @param imageHeight 잘라 낸 그림 높이 (px)
	 */
	static String headerText(List<OcrLine> ocr, int imageHeight) {
		return ocr.stream()
				.filter(l -> confidence(l) >= MIN_LINE_CONFIDENCE)
				.filter(l -> l.y1() < imageHeight - 2)
				.sorted(Comparator.comparingDouble(OcrLine::baseline))
				.map(l -> {
					StringBuilder sb = new StringBuilder();
					float unit = hangulHeight(l);
					OcrWord prev = null;
					for (OcrWord w : l.words()) {
						if (prev != null && !joins(prev, w, unit)) {
							sb.append(' ');
						}
						sb.append(w.text());
						prev = w;
					}
					return sb.toString();
				})
				.reduce((a, b) -> a + " " + b).orElse("");
	}

	/** 앞 단어와 붙여 쓰는지. Tesseract 텍스트 출력의 띄어쓰기를 따르고, 모르면 한글끼리 거리로 정한다 (px, 이미지 기준) */
	static boolean joins(OcrWord prev, OcrWord next, float hangulHeight) {
		if (next.spacesBefore() >= 0) {
			return next.spacesBefore() == 0;
		}
		return isHangul(prev.text().codePointBefore(prev.text().length()))
				&& isHangul(next.text().codePointAt(0))
				&& next.x0() - prev.x1() < hangulHeight * HANGUL_JOIN;
	}

	/** 줄 안의 한글만으로 된 단어 높이의 중앙값. 없으면 모든 단어 높이의 중앙값 */
	static float hangulHeight(OcrLine line) {
		List<Float> heights = line.words().stream()
				.filter(w -> w.text().codePoints().allMatch(OcrLines::isHangul))
				.map(w -> w.y1() - w.y0())
				.sorted().toList();
		if (heights.isEmpty()) {
			heights = line.words().stream().map(w -> w.y1() - w.y0()).sorted().toList();
		}
		return heights.get(heights.size() / 2);
	}

	private static boolean isHangul(int cp) {
		return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL;
	}

	private static PdfLine line(int page, int column, OcrLine l, float scale, float offsetX,
			List<UnderlineDetector.Segment> underlines) {
		float baseline = l.baseline() * scale;
		float height = l.height() * scale;
		List<Glyph> glyphs = new ArrayList<>();
		float unit = hangulHeight(l);
		OcrWord prev = null;
		for (OcrWord w : l.words()) {
			float x0 = (w.x0() + offsetX) * scale;
			float x1 = (w.x1() + offsetX) * scale;
			if (prev != null && !joins(prev, w, unit)) {
				float prevEnd = (prev.x1() + offsetX) * scale;
				int spaces = x0 - prevEnd > height * WIDE_GAP ? 3 : 1;
				float each = Math.max(0, x0 - prevEnd) / spaces;
				for (int i = 0; i < spaces; i++) {
					glyphs.add(new Glyph(" ", prevEnd + each * i, each, false, false));
				}
			}
			int[] chars = w.text().codePoints().toArray();
			float width = (x1 - x0) / chars.length;
			for (int i = 0; i < chars.length; i++) {
				float x = x0 + width * i;
				boolean underline = PdfLayoutReader.isUnderlined(x + width / 2, baseline, underlines);
				glyphs.add(new Glyph(Character.toString(chars[i]), x, width, underline, false,
						w.confidence() < UNCERTAIN_CONFIDENCE));
			}
			prev = w;
		}
		float x = (l.words().get(0).x0() + offsetX) * scale;
		return new PdfLine(page, column, x, baseline, height, PdfLayoutReader.fixSpaceUnderlines(glyphs));
	}

	private static double confidence(OcrLine l) {
		return l.words().stream().mapToInt(OcrWord::confidence).average().orElse(0);
	}
}
