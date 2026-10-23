package cloud.fhwang.chaewon_t.extract;

import java.util.List;

/**
 * PDF 한 단의 글자 한 줄.
 *
 * @param column 0 = 왼쪽 단, 1 = 오른쪽 단
 * @param x      첫 글자 왼쪽 x
 * @param y      기준선 y (원점 왼쪽 위)
 * @param height 글자 높이 (줄 간격 판단용)
 * @param glyphs 글자들 (x 순서). 단어 사이 공백이 PDF에 없으면 공백 글자를 끼워 넣은 상태
 */
public record PdfLine(int page, int column, float x, float y, float height, List<Glyph> glyphs) {

	public PdfLine {
		glyphs = List.copyOf(glyphs);
	}

	/** 마지막 글자 오른쪽 x */
	public float endX() {
		Glyph last = glyphs.get(glyphs.size() - 1);
		return last.x() + last.width();
	}

	public String text() {
		StringBuilder sb = new StringBuilder();
		glyphs.forEach(g -> sb.append(g.text()));
		return sb.toString();
	}

	/**
	 * 글자 하나(또는 합자). underline은 밑줄 감지 결과, bold는 굵은 글꼴이거나 테두리를 함께 그린(가짜 굵게) 글자,
	 * uncertain은 OCR이 자신 없게 읽은 글자
	 */
	public record Glyph(String text, float x, float width, boolean underline, boolean bold, boolean uncertain) {

		public Glyph(String text, float x, float width, boolean underline, boolean bold) {
			this(text, x, width, underline, bold, false);
		}
	}
}
