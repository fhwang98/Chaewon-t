package cloud.fhwang.chaewon_t.extract;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** 테스트용 PDF 생성. 기본 글꼴(Helvetica)이라 영문·숫자만 쓸 수 있다 */
final class TestPdfs {

	/** 쓸 글자와 위치. y는 페이지 위에서부터 (pt) */
	record Text(String text, float x, float yFromTop) {
	}

	private TestPdfs() {
	}

	static Path write(Path file, int pages, Text... texts) throws IOException {
		try (PDDocument doc = new PDDocument()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			for (int i = 0; i < pages; i++) {
				PDPage page = new PDPage(PDRectangle.A4);
				doc.addPage(page);
				if (i == 0 && texts.length > 0) {
					float height = page.getMediaBox().getHeight();
					try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
						for (Text t : texts) {
							cs.beginText();
							cs.setFont(font, 12);
							cs.newLineAtOffset(t.x(), height - t.yFromTop()); // PDF 좌표는 왼쪽 아래 원점
							cs.showText(t.text());
							cs.endText();
						}
					}
				}
			}
			doc.save(file.toFile());
		}
		return file;
	}
}
