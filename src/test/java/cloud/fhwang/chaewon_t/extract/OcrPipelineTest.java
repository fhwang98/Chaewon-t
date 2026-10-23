package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cloud.fhwang.chaewon_t.common.AppProperties;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.template.PdfLayout;

/**
 * OCR 전체 흐름 회귀 테스트: 그림뿐인 PDF(스캔본과 같은 모양) → Tesseract → 줄 → 문항.
 * 실제 Tesseract를 부르므로 설치되어 있지 않으면 건너뛴다 (brew install tesseract + scripts/download-tessdata.sh).
 *
 * 시험지 내용은 저장소에 넣지 않으므로 영어 문장으로 그림 PDF를 그때그때 만든다 (기본 글꼴이라 한글·①은 못 쓴다.
 * 선택지 번호는 "(1)"로 쓰고, OCR 보정이 ①로 바꾸는지 본다). 한 쪽이라 몇 초 걸린다
 */
class OcrPipelineTest {

	static final PdfLayout LAYOUT = new PdfLayout(297.5f, 60f, 800f, 240f);

	@TempDir
	Path tmp;

	TesseractOcr ocr;

	@BeforeEach
	void setUp() {
		assumeTrue(tesseractInstalled(), "Tesseract가 없어 OCR 테스트를 건너뜁니다");
		ocr = new TesseractOcr(AppProperties.Ocr.defaults());
	}

	@AfterEach
	void tearDown() {
		if (ocr != null) {
			ocr.shutdown();
		}
	}

	@Test
	void 그림뿐인_PDF를_OCR로_읽어_문항_출처_선택지를_나눈다() throws Exception {
		Path pdf = imageOnlyPdf();

		ExamPdfReader.Result result = new ExamPdfReader(ocr).read(pdf, LAYOUT, tmp, (done, total, ocrPages, ocrDone) -> {
		});

		assertThat(result.pages()).isEqualTo(1);
		assertThat(result.ocrPages()).isEqualTo(1);
		List<Question> questions = result.document().questions();
		assertThat(questions).extracting(Question::number).containsExactly(1, 2);

		Question first = questions.get(0);
		assertThat(first.prompt().plainText()).isEqualTo("Which is the best title for the passage?");
		assertThat(first.source()).isEqualTo("1-1");
		assertThat(first.body().get(0).paragraphs().get(0).plainText())
				.isEqualTo("Fake news spreads quickly when people share stories without checking the source.");
		assertThat(first.choices()).extracting(Paragraph::plainText).containsExactly(
				"① Sharing News Safely", "② Why Stories Spread", "③ The Cost of Rumors",
				"④ Checking Every Source", "⑤ Media and Trust");

		Question second = questions.get(1);
		assertThat(second.source()).isEqualTo("1-2");
		assertThat(second.body()).isNotEmpty();
	}

	/** 텍스트 PDF를 그려서(300dpi) 그 그림만 담은 PDF로 만든다. 글자 정보가 없으므로 OCR 대상이 된다 */
	private Path imageOnlyPdf() throws Exception {
		Path text = tmp.resolve("text.pdf");
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			float h = page.getMediaBox().getHeight();
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				text(cs, font, h, 300, 40, "Test Academy Exam");
				text(cs, font, h, 48, 85, "1. Which is the best title for the passage? [1-1]");
				text(cs, font, h, 59, 110, "Fake news spreads quickly when people share");
				text(cs, font, h, 48, 123, "stories without checking the source.");
				text(cs, font, h, 48, 150, "(1) Sharing News Safely");
				text(cs, font, h, 48, 163, "(2) Why Stories Spread");
				text(cs, font, h, 48, 176, "(3) The Cost of Rumors");
				text(cs, font, h, 48, 189, "(4) Checking Every Source");
				text(cs, font, h, 48, 202, "(5) Media and Trust");
				text(cs, font, h, 48, 240, "2. Write the main idea in one sentence. [1-2]");
				text(cs, font, h, 59, 265, "People should check where news comes from.");
			}
			doc.save(text.toFile());
		}

		Path scanned = tmp.resolve("scanned.pdf");
		try (PDDocument source = Loader.loadPDF(text.toFile()); PDDocument doc = new PDDocument()) {
			BufferedImage image = new PDFRenderer(source).renderImageWithDPI(0, 300, ImageType.GRAY);
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			PDImageXObject xobject = LosslessFactory.createFromImage(doc, image);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.drawImage(xobject, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
			}
			doc.save(scanned.toFile());
		}
		return scanned;
	}

	private static void text(PDPageContentStream cs, PDType1Font font, float h, float x, float yFromTop, String s)
			throws Exception {
		cs.beginText();
		cs.setFont(font, 10);
		cs.newLineAtOffset(x, h - yFromTop);
		cs.showText(s);
		cs.endText();
	}

	private static boolean tesseractInstalled() {
		try {
			Process p = new ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
