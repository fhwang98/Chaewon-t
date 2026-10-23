package cloud.fhwang.chaewon_t.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import cloud.fhwang.chaewon_t.common.AppProperties;
import cloud.fhwang.chaewon_t.common.ConversionException;
import cloud.fhwang.chaewon_t.common.ErrorType;

/** 업로드 PDF 검증: 확장자 → 첫 바이트 %PDF- → 열리는지·페이지 수 상한 (싼 검사부터) */
@Component
public class PdfValidator {

	private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	private final int maxPages;

	public PdfValidator(AppProperties props) {
		this.maxPages = props.maxPdfPages();
	}

	public void validate(String originalName, Path pdf) {
		if (originalName == null || !originalName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
			throw invalid("PDF 파일(.pdf)만 올릴 수 있습니다.");
		}
		if (!hasPdfHeader(pdf)) {
			throw invalid("PDF 파일이 아닙니다.");
		}
		int pages;
		try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
			pages = doc.getNumberOfPages();
		} catch (IOException e) { // 암호 PDF(InvalidPasswordException)도 여기로 온다
			throw new ConversionException(ErrorType.INVALID_FILE, "PDF를 열 수 없습니다. 손상되었거나 암호가 걸린 파일일 수 있습니다.", e);
		}
		if (pages > maxPages) {
			throw invalid("페이지가 너무 많습니다. (최대 " + maxPages + "쪽)");
		}
	}

	private static boolean hasPdfHeader(Path pdf) {
		try (InputStream in = Files.newInputStream(pdf)) {
			return Arrays.equals(in.readNBytes(PDF_HEADER.length), PDF_HEADER);
		} catch (IOException e) {
			return false;
		}
	}

	private static ConversionException invalid(String message) {
		return new ConversionException(ErrorType.INVALID_FILE, message);
	}
}
