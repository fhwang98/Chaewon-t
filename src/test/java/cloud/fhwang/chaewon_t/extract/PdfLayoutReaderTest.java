package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;
import cloud.fhwang.chaewon_t.template.PdfLayout;

class PdfLayoutReaderTest {

	static final PdfLayout LAYOUT = new PdfLayout(297.5f, 60f, 800f, 240f);

	@TempDir
	Path tmp;

	@Test
	void 왼쪽_단_다음_오른쪽_단_순서로_읽고_머리글_꼬리글은_뺀다() throws Exception {
		Path pdf = twoColumnPdf();

		try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
			List<PdfLine> lines = PdfLayoutReader.read(doc, LAYOUT);

			assertThat(lines).extracting(l -> l.text().strip())
					.containsExactly("1. Left first", "Left second", "Right first", "Right second");
			assertThat(lines).extracting(PdfLine::column).containsExactly(0, 0, 1, 1);
			assertThat(PdfLayoutReader.headerText(doc, LAYOUT)).isEqualTo("Exam Title");
		}
	}

	@Test
	void 글자_아래_짧은_선은_밑줄이고_긴_선은_상자_테두리로_본다() throws Exception {
		Path pdf = twoColumnPdf();

		try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
			PdfLine leftSecond = PdfLayoutReader.read(doc, LAYOUT).get(1);

			String underlined = leftSecond.glyphs().stream().filter(Glyph::underline).map(Glyph::text)
					.reduce("", String::concat);
			assertThat(underlined).isEqualTo("second");
			PdfLine leftFirst = PdfLayoutReader.read(doc, LAYOUT).get(0);
			assertThat(leftFirst.glyphs()).noneMatch(Glyph::underline);
		}
	}

	@Test
	void 세로선과_이어진_가로선은_짧아도_상자_테두리로_본다(@TempDir Path tmp) throws Exception {
		// sample5: 상자 너비(230pt)가 길이 기준(240pt)보다 짧아, 상자 윗변이 바로 위 문항 머리의 밑줄로 잡혔다
		Path file = tmp.resolve("box.pdf");
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			float h = page.getMediaBox().getHeight();
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				text(cs, font, h, 52, 113, "Prompt above box");
				text(cs, font, h, 52, 140, "Passage word");
				cs.moveTo(50, h - 115);                       // 상자: 문항 머리 기준선 2pt 아래부터
				cs.lineTo(250, h - 115);
				cs.lineTo(250, h - 160);
				cs.lineTo(50, h - 160);
				cs.closePath();
				cs.stroke();
				line(cs, h, 52, 52 + font.getStringWidth("Passage") / 1000 * 12, 142); // 진짜 밑줄
			}
			doc.save(file.toFile());
		}

		try (PDDocument doc = Loader.loadPDF(file.toFile())) {
			List<PdfLine> lines = PdfLayoutReader.read(doc, LAYOUT);
			assertThat(lines.get(0).glyphs()).noneMatch(Glyph::underline);
			assertThat(lines.get(1).glyphs().stream().filter(Glyph::underline).map(Glyph::text).reduce("", String::concat))
					.isEqualTo("Passage");
		}
	}

	@Test
	void 머리글_꼬리글_구분선이_있으면_고정값_대신_쓴다() {
		PDPage page = new PDPage(PDRectangle.A4); // 595 x 842
		List<UnderlineDetector.Segment> segments = List.of(
				new UnderlineDetector.Segment(0, 595, 0),       // 배경 사각형 윗변: 무시
				new UnderlineDetector.Segment(38, 557, 45.6f),  // 머리글 구분선 (sample2)
				new UnderlineDetector.Segment(48, 286, 52.3f),  // 상자 테두리: 짧아서 무시
				new UnderlineDetector.Segment(38, 558, 808.8f));

		PdfLayout layout = PdfLayoutReader.pageLayout(page, segments, LAYOUT);

		assertThat(layout.headerBottom()).isEqualTo(45.6f);
		assertThat(layout.footerTop()).isEqualTo(808.8f);
		assertThat(PdfLayoutReader.pageLayout(page, List.of(), LAYOUT)).isEqualTo(LAYOUT);
	}

	@Test
	void 문자_정보가_없는_글자가_많거나_글자가_거의_없으면_OCR() {
		assertThat(new PdfLayoutReader.PageGlyphs(List.of(), Set.of(), 4000, 0).needsOcr()).isFalse();
		assertThat(new PdfLayoutReader.PageGlyphs(List.of(), Set.of(), 0, 4500).needsOcr()).isTrue(); // 인쇄 PDF
		assertThat(new PdfLayoutReader.PageGlyphs(List.of(), Set.of(), 3, 0).needsOcr()).isTrue();    // 스캔본
	}

	/** 머리글 "Exam Title"(y=40), 왼쪽·오른쪽 단 두 줄씩, 꼬리글 "- 1 -"(y=820), "second" 아래 밑줄, 상자선 2개 */
	private Path twoColumnPdf() throws Exception {
		Path file = tmp.resolve("two.pdf");
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			float h = page.getMediaBox().getHeight();
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				text(cs, font, h, 48, 40, "Exam Title");
				// 오른쪽 단을 먼저 쓴다: 쓰는 순서와 상관없이 왼쪽 단부터 읽는지 확인
				text(cs, font, h, 309, 100, "Right first");
				text(cs, font, h, 309, 113, "Right second");
				text(cs, font, h, 48, 100, "1. Left first");
				text(cs, font, h, 52, 113, "Left second");
				text(cs, font, h, 280, 820, "- 1 -");

				float secondX = 52 + font.getStringWidth("Left ") / 1000 * 12;
				float secondW = font.getStringWidth("second") / 1000 * 12;
				line(cs, h, secondX, secondX + secondW, 115); // 밑줄: 기준선 2pt 아래
				line(cs, h, 45, 290, 116);                    // 상자 테두리: 단 너비
				line(cs, h, 40, 120, 102);                    // 상자 테두리: 글자 왼쪽 끝(48)보다 바깥에서 시작
			}
			doc.save(file.toFile());
		}
		return file;
	}

	private static void text(PDPageContentStream cs, PDType1Font font, float h, float x, float yFromTop, String s)
			throws Exception {
		cs.beginText();
		cs.setFont(font, 12);
		cs.newLineAtOffset(x, h - yFromTop);
		cs.showText(s);
		cs.endText();
	}

	private static void line(PDPageContentStream cs, float h, float x1, float x2, float yFromTop) throws Exception {
		cs.moveTo(x1, h - yFromTop);
		cs.lineTo(x2, h - yFromTop);
		cs.stroke();
	}
}
