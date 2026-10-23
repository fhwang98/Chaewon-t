package cloud.fhwang.chaewon_t.extract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;
import cloud.fhwang.chaewon_t.template.PdfLayout;

/**
 * 2단 PDF를 읽는 순서(1쪽 왼쪽 단 → 1쪽 오른쪽 단 → 2쪽 …)대로 줄 목록으로 만든다.
 *
 * PDFTextStripper가 겹친 글자(가짜 굵게) 제거 등을 마친 뒤 writeString으로 넘겨주는 글자 좌표를 모으고,
 * 단·줄 나누기는 직접 한다. 전체 페이지 기준으로 정렬하면 왼쪽·오른쪽 단의 같은 높이 줄이 한 줄로 섞이기 때문이다.
 */
public final class PdfLayoutReader {

	private PdfLayoutReader() {
	}

	/** 본문 줄 (머리글·꼬리글 구역 제외). 모든 페이지를 PDF 글자 정보로 읽는다 */
	public static List<PdfLine> read(PDDocument doc, PdfLayout layout) throws IOException {
		List<PdfLine> lines = new ArrayList<>();
		for (int pageNo = 1; pageNo <= doc.getNumberOfPages(); pageNo++) {
			PDPage page = doc.getPage(pageNo - 1);
			List<UnderlineDetector.Segment> segments = UnderlineDetector.horizontalSegments(page);
			lines.addAll(textLines(pageNo, glyphs(doc, pageNo), segments, pageLayout(page, segments, layout)));
		}
		return lines;
	}

	/** 머리글·꼬리글 구분선은 페이지 위·아래 이 비율 안에서만 찾는다 */
	private static final float BAND = 0.15f;
	/** 이 비율보다 긴 가로선만 구분선으로 본다 (상자 테두리는 단 너비라 절반이 안 된다) */
	private static final float RULE_WIDTH = 0.7f;
	/** 페이지 가장자리에 붙은 선(배경 사각형의 윗변·아랫변)은 구분선이 아니다 */
	private static final float EDGE = 5f;

	/**
	 * 이 페이지에 맞춘 머리글·꼬리글 경계. 고정값(mapping.json)은 PDF마다 맞지 않는다:
	 * sample.pdf는 구분선이 58.6pt인데 sample2~4는 45.6pt라, 60pt로 자르면 sample2의 첫 문항 머리 줄(기준선 59.3pt)이 잘렸다.
	 * 위쪽 구역에서 가장 아래 구분선, 아래쪽 구역에서 가장 위 구분선을 쓰고, 없으면 고정값
	 */
	static PdfLayout pageLayout(PDPage page, List<UnderlineDetector.Segment> segments, PdfLayout layout) {
		PDRectangle box = page.getCropBox();
		float width = box.getWidth();
		float height = box.getHeight();
		float header = layout.headerBottom();
		float footer = layout.footerTop();
		boolean foundHeader = false;
		boolean foundFooter = false;
		for (UnderlineDetector.Segment s : segments) {
			if (s.width() < width * RULE_WIDTH || s.y() < EDGE || s.y() > height - EDGE) {
				continue;
			}
			if (s.y() < height * BAND) {
				header = foundHeader ? Math.max(header, s.y()) : s.y();
				foundHeader = true;
			} else if (s.y() > height * (1 - BAND)) {
				footer = foundFooter ? Math.min(footer, s.y()) : s.y();
				foundFooter = true;
			}
		}
		return layout.withBands(header, footer);
	}

	/**
	 * 한 페이지의 본문 줄을 PDF 글자 정보로 만든다
	 *
	 * @param layout 이 페이지에 맞춘 배치값 (pageLayout)
	 */
	static List<PdfLine> textLines(int pageNo, PageGlyphs page, List<UnderlineDetector.Segment> segments,
			PdfLayout layout) {
		List<PdfLine> lines = new ArrayList<>();
		for (int column = 0; column < 2; column++) {
			int col = column;
			List<TextPosition> inColumn = page.glyphs().stream()
					.filter(t -> t.getY() > layout.headerBottom() && t.getY() < layout.footerTop())
					.filter(t -> (t.getX() < layout.columnSplit()) == (col == 0))
					.toList();
			double textLeft = inColumn.stream().filter(t -> !t.getUnicode().isBlank())
					.mapToDouble(TextPosition::getX).min().orElse(0);
			lines.addAll(toLines(pageNo, col, inColumn, underlines(segments, (float) textLeft, layout), page.bold()));
		}
		return lines;
	}

	/**
	 * 상자 테두리를 뺀 가로선. 상자선은 단의 글자 왼쪽 끝보다 바깥에서 시작하거나(샘플: 글자 48.2, 상자 45.2)
	 * 단 너비만큼 길다. 밑줄은 늘 글자 위치에서 시작한다 (샘플 14번의 214pt짜리 긴 밑줄도 살리려고 두 기준을 함께 쓴다).
	 * 세로선·둥근 모서리와 이어진 선(box)도 상자 테두리다
	 */
	static List<UnderlineDetector.Segment> underlines(List<UnderlineDetector.Segment> segments,
			float textLeft, PdfLayout layout) {
		return segments.stream()
				.filter(s -> !s.box() && s.width() < layout.boxLineMinWidth())
				.filter(s -> s.x1() >= textLeft - 1.5)
				.toList();
	}

	/** 1쪽 머리글 구역의 글자 (시험 이름 기본값) */
	public static String headerText(PDDocument doc, PdfLayout layout) throws IOException {
		if (doc.getNumberOfPages() == 0) {
			return "";
		}
		PDPage page = doc.getPage(0);
		return headerText(glyphs(doc, 1), pageLayout(page, UnderlineDetector.horizontalSegments(page), layout));
	}

	static String headerText(PageGlyphs page, PdfLayout layout) {
		List<TextPosition> header = page.glyphs().stream()
				.filter(t -> t.getY() <= layout.headerBottom()).toList();
		return toLines(1, 0, header, List.of(), Set.of()).stream()
				.map(PdfLine::text).map(String::strip)
				.reduce((a, b) -> a + " " + b).orElse("");
	}

	/**
	 * 한 페이지의 글자 좌표.
	 *
	 * @param bold     굵은 글자
	 * @param mapped   어떤 문자인지 알 수 있는 글자 수
	 * @param unmapped 글꼴에 문자 정보(ToUnicode)가 없어 무슨 글자인지 알 수 없는 글자 수.
	 *                 프린터 드라이버로 인쇄한 PDF(sample2~4: PScript5 → Distiller)는 글자를 문자 정보 없는 Type3 글꼴로 바꿔 전부 여기에 든다
	 */
	record PageGlyphs(List<TextPosition> glyphs, Set<TextPosition> bold, int mapped, int unmapped) {

		/** 이 개수보다 읽을 수 있는 글자가 적으면 글자 없는 페이지(스캔본)로 본다 */
		private static final int MIN_TEXT_GLYPHS = 20;

		/** PDF 글자 정보를 믿을 수 없어 OCR로 읽어야 하는 페이지 */
		boolean needsOcr() {
			return mapped < MIN_TEXT_GLYPHS || unmapped > mapped;
		}
	}

	/** 그리기 방식(렌더링 모드)과 글꼴은 글자를 처리하는 순간에만 알 수 있어 processTextPosition에서 기록한다 */
	static PageGlyphs glyphs(PDDocument doc, int pageNo) throws IOException {
		return glyphs(doc, pageNo, false);
	}

	/**
	 * @param raw true면 PDFTextStripper의 뒤처리(겹친 글자 지우기, 악센트 합치기)를 거치기 전 글자를 그대로 모은다.
	 *            문자 정보 없는 Type3 글꼴은 글자 코드를 그대로 글자로 쓰는데, 폭이 엉터리라 "ll"의 두 번째 l을 겹친 글자로
	 *            지우고, 코드 168(¨)처럼 악센트로 보이는 글자를 앞 글자에 합쳐 "31."이 "3."이 됐다 (sample2).
	 *            그런 페이지는 이걸로 모아 GlyphDecoder가 직접 거른다
	 */
	static PageGlyphs glyphs(PDDocument doc, int pageNo, boolean raw) throws IOException {
		List<TextPosition> result = new ArrayList<>();
		Set<TextPosition> bold = Collections.newSetFromMap(new IdentityHashMap<>());
		int[] counts = new int[2]; // [mapped, unmapped]
		PDFTextStripper stripper = new PDFTextStripper() {
			@Override
			protected void processTextPosition(TextPosition text) {
				// 한글 PDF는 굵은 글꼴이 없으면 글자에 테두리를 함께 그려(FILL_STROKE) 굵게 보이게 한다
				RenderingMode mode = getGraphicsState().getTextState().getRenderingMode();
				String font = text.getFont() == null ? "" : text.getFont().getName();
				if (mode == RenderingMode.FILL_STROKE || (font != null && font.contains("Bold"))) {
					bold.add(text);
				}
				counts[hasUnicode(text) ? 0 : 1]++;
				if (raw) {
					result.add(text);
				} else {
					super.processTextPosition(text);
				}
			}

			@Override
			protected void writeString(String text, List<TextPosition> positions) {
				result.addAll(positions);
			}
		};
		stripper.setSortByPosition(true);
		stripper.setStartPage(pageNo);
		stripper.setEndPage(pageNo);
		stripper.getText(doc); // writeString 콜백으로 글자가 모인다
		return new PageGlyphs(result, bold, counts[0], counts[1]);
	}

	/** 글꼴이 이 글자 코드를 문자로 바꿀 수 있는지. 못 바꾸면 PDFBox는 코드 값을 그대로 글자로 내놓는다("AJDE" 같은 깨진 글자) */
	private static boolean hasUnicode(TextPosition text) {
		if (text.getFont() == null) {
			return false;
		}
		for (int code : text.getCharacterCodes()) {
			if (text.getFont().toUnicode(code) == null) {
				return false;
			}
		}
		return true;
	}

	/** 기준선 y가 가까운 글자끼리 한 줄로 묶고, x 순서로 정렬한다 */
	private static List<PdfLine> toLines(int page, int column, List<TextPosition> glyphs,
			List<UnderlineDetector.Segment> underlines, Set<TextPosition> bold) {
		List<TextPosition> sorted = new ArrayList<>(glyphs);
		sorted.sort(Comparator.comparingDouble(TextPosition::getY).thenComparingDouble(TextPosition::getX));

		List<PdfLine> lines = new ArrayList<>();
		List<TextPosition> current = new ArrayList<>();
		for (TextPosition t : sorted) {
			if (!current.isEmpty() && Math.abs(t.getY() - current.get(0).getY()) > sameLineTolerance(current.get(0))) {
				addLine(lines, page, column, current, underlines, bold);
				current = new ArrayList<>();
			}
			current.add(t);
		}
		addLine(lines, page, column, current, underlines, bold);
		return lines;
	}

	/** 공백뿐인 줄은 버린다 (샘플에서 단 경계 근처에 공백 글자만 있는 줄이 있어 단 왼쪽 끝 계산을 흐렸다) */
	private static void addLine(List<PdfLine> lines, int page, int column, List<TextPosition> positions,
			List<UnderlineDetector.Segment> underlines, Set<TextPosition> bold) {
		if (positions.stream().anyMatch(t -> !t.getUnicode().isBlank())) {
			lines.add(line(page, column, positions, underlines, bold));
		}
	}

	private static float sameLineTolerance(TextPosition first) {
		return Math.max(1f, first.getHeight() * 0.4f);
	}

	private static PdfLine line(int page, int column, List<TextPosition> positions,
			List<UnderlineDetector.Segment> underlines, Set<TextPosition> bold) {
		List<TextPosition> sorted = new ArrayList<>(positions);
		sorted.sort(Comparator.comparingDouble(TextPosition::getX));

		List<Glyph> glyphs = new ArrayList<>();
		float height = 0;
		float baseline = sorted.get(0).getY();
		TextPosition prev = null;
		for (TextPosition t : sorted) {
			height = Math.max(height, t.getHeight());
			// PDF에 공백 글자가 없는데 글자 사이가 벌어져 있으면 단어 경계로 보고 공백을 넣는다
			if (prev != null && !prev.getUnicode().isBlank() && !t.getUnicode().isBlank()
					&& t.getX() - (prev.getX() + prev.getWidth()) > prev.getWidthOfSpace() * 0.5f) {
				glyphs.add(new Glyph(" ", prev.getX() + prev.getWidth(), 0, false, false));
			}
			glyphs.add(new Glyph(t.getUnicode(), t.getX(), t.getWidth(), isUnderlined(t, underlines), bold.contains(t)));
			prev = t;
		}
		// 줄의 x는 첫 번째 공백 아닌 글자 기준 (들여쓰기 판단용)
		float x = sorted.stream().filter(t -> !t.getUnicode().isBlank()).findFirst().orElseThrow().getX();
		return new PdfLine(page, column, x, baseline, height, fixSpaceUnderlines(glyphs));
	}

	/** 글자 가운데 x가 선 범위 안이고, 선이 기준선 바로 아래(0~4pt)에 있으면 밑줄 */
	private static boolean isUnderlined(TextPosition t, List<UnderlineDetector.Segment> underlines) {
		return isUnderlined(t.getX() + t.getWidth() / 2, t.getY(), underlines);
	}

	static boolean isUnderlined(float center, float baseline, List<UnderlineDetector.Segment> underlines) {
		for (UnderlineDetector.Segment s : underlines) {
			float below = s.y() - baseline;
			if (center >= s.x1() && center <= s.x2() && below >= -0.5f && below <= 4f) {
				return true;
			}
		}
		return false;
	}

	/** 빈칸 밑줄 "_" 하나의 폭 어림 (pt). 본문 8~10pt 글자에서 "_" 폭 */
	private static final float UNDERSCORE = 4f;

	/**
	 * 2칸 이상 이어진 밑줄 친 공백 = 빈칸 문제의 빈칸 → "_"로 바꾼다. 선 길이만큼 "_" 개수를 맞춘다(최소 3개).
	 * sample2는 빈칸을 "공백 6개 + 그 아래 선"으로 그려서, 공백이 줄 앞뒤에서 잘려 나가 빈칸이 통째로 사라졌다
	 * ("It's ________ with which" → "It's with which"). 밑줄 친 단어 사이 공백 한 칸은 그대로 둔다
	 */
	static List<Glyph> underlinedBlanks(List<Glyph> glyphs) {
		List<Glyph> result = new ArrayList<>(glyphs.size());
		int i = 0;
		while (i < glyphs.size()) {
			Glyph g = glyphs.get(i);
			int j = i;
			while (j < glyphs.size() && glyphs.get(j).text().isBlank() && glyphs.get(j).underline()) {
				j++;
			}
			if (j - i >= 2) {
				float x0 = g.x();
				Glyph last = glyphs.get(j - 1);
				float width = Math.max(last.x() + last.width() - x0, 0);
				int n = Math.max(3, Math.round(width / UNDERSCORE));
				for (int k = 0; k < n; k++) {
					result.add(new Glyph("_", x0 + width * k / n, width / n, false, false));
				}
				i = j;
				continue;
			}
			result.add(g);
			i++;
		}
		return result;
	}

	/** 공백은 앞뒤 글자가 모두 밑줄(굵게·확인 필요)일 때만 그 서식. 단어 끝 공백에 서식이 붙지 않게 */
	static List<Glyph> fixSpaceUnderlines(List<Glyph> glyphs) {
		glyphs = underlinedBlanks(glyphs);
		List<Glyph> fixed = new ArrayList<>(glyphs.size());
		for (int i = 0; i < glyphs.size(); i++) {
			Glyph g = glyphs.get(i);
			if (g.text().isBlank()) {
				boolean inside = i > 0 && i < glyphs.size() - 1;
				boolean underline = inside && glyphs.get(i - 1).underline() && glyphs.get(i + 1).underline();
				boolean bold = inside && glyphs.get(i - 1).bold() && glyphs.get(i + 1).bold();
				boolean uncertain = inside && glyphs.get(i - 1).uncertain() && glyphs.get(i + 1).uncertain();
				g = new Glyph(g.text(), g.x(), g.width(), underline, bold, uncertain);
			}
			fixed.add(g);
		}
		return fixed;
	}
}
