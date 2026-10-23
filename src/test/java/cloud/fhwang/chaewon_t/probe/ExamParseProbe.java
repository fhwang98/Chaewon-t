package cloud.fhwang.chaewon_t.probe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import cloud.fhwang.chaewon_t.common.AppProperties;
import cloud.fhwang.chaewon_t.extract.ExamDocument;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Block;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;
import cloud.fhwang.chaewon_t.extract.ExamPdfReader;
import cloud.fhwang.chaewon_t.extract.ExamValidator;
import cloud.fhwang.chaewon_t.extract.TesseractOcr;
import cloud.fhwang.chaewon_t.template.PdfLayout;

/**
 * 2단계 확인용: PDF 기출문제를 파싱해 구조를 출력한다. 밑줄은 <u>…</u>로 표시.
 *
 * 실행: ./gradlew examParseProbe -Pfile=samples/sample.pdf
 */
public class ExamParseProbe {

	/** samples/sample.pdf 배치값 (PdfProbe 좌표로 확인) */
	public static final PdfLayout SAMPLE_LAYOUT = new PdfLayout(297.5f, 60f, 800f, 240f);

	public static void main(String[] args) {
		if (args.length < 1) {
			System.out.println("사용법: ExamParseProbe <sample.pdf>");
			return;
		}
		ExamDocument doc = read(Path.of(args[0]));
		// 전체 결과를 JSON으로도 남긴다 (화면 출력은 긴 문단을 줄인다). probe-out은 git에 넣지 않는다
		Path json = Path.of("probe-out", Path.of(args[0]).getFileName().toString().replaceFirst("\\.pdf$", "") + ".json");
		try {
			Files.createDirectories(json.getParent());
			tools.jackson.databind.json.JsonMapper.shared().writeValue(json.toFile(), doc);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		System.out.println("머리말: " + doc.headerTitle());
		System.out.println("문항 " + doc.questions().size() + "개, 정답 " + doc.answers().size() + "개");
		System.out.println("경고: " + ExamValidator.warnings(doc));
		for (Question q : doc.questions()) {
			System.out.printf("%n[%d] %s  [%s]%s%n", q.number(), marked(q.prompt()), q.source(), q.subjective() ? " (주관식)" : "");
			for (int b = 0; b < q.body().size(); b++) {
				Block block = q.body().get(b);
				for (Paragraph p : block.paragraphs()) {
					System.out.println("  B" + b + " | " + shorten(marked(p)));
				}
			}
			q.choices().forEach(c -> System.out.println("  C  | " + shorten(marked(c))));
		}
		System.out.println();
		doc.answers().forEach(a -> System.out.println(a.number() + " 번 - " + a.answer() + " | " + shorten(a.explanation())));
	}

	/** 샘플 배치값으로 읽는다. OCR이 필요한 페이지는 Tesseract 기본 설정(data/tessdata)으로 읽고 진행 상황을 찍는다 */
	public static ExamDocument read(Path pdf) {
		TesseractOcr ocr = new TesseractOcr(AppProperties.Ocr.defaults());
		try {
			Path work = Files.createTempDirectory("exam-probe");
			long start = System.nanoTime();
			ExamPdfReader.Result result = new ExamPdfReader(ocr).read(pdf, SAMPLE_LAYOUT, work,
					(done, total, ocrPages, ocrDone) -> System.err.printf("\r읽는 중 %d/%d쪽 (OCR %d/%d쪽)", done, total, ocrDone, ocrPages));
			System.err.printf("%n%d쪽 중 OCR %d쪽, %d초%n", result.pages(), result.ocrPages(),
					(System.nanoTime() - start) / 1_000_000_000);
			return result.document();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			ocr.shutdown();
		}
	}

	private static String marked(Paragraph p) {
		StringBuilder sb = new StringBuilder();
		for (Span s : p.spans()) {
			sb.append(s.underline() ? "<u>" + s.text() + "</u>" : s.text());
		}
		return sb.toString();
	}

	private static String shorten(String s) {
		return s.length() <= 110 ? s : s.substring(0, 60) + " … " + s.substring(s.length() - 40);
	}
}
