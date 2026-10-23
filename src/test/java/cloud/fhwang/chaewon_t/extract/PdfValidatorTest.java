package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cloud.fhwang.chaewon_t.common.AppProperties;
import cloud.fhwang.chaewon_t.common.ConversionException;

class PdfValidatorTest {

	@TempDir
	Path tmp;

	PdfValidator validator = new PdfValidator(new AppProperties(Path.of("t"), Path.of("w"), Duration.ofHours(1), 3, "exam", AppProperties.Ocr.defaults()));

	@Test
	void 정상_PDF는_통과() throws Exception {
		Path pdf = TestPdfs.write(tmp.resolve("a.pdf"), 3);

		assertThatCode(() -> validator.validate("견적서.PDF", pdf)).doesNotThrowAnyException();
	}

	@Test
	void 확장자가_pdf가_아니면_거부() throws Exception {
		Path pdf = TestPdfs.write(tmp.resolve("a.pdf"), 1);

		assertThatThrownBy(() -> validator.validate("a.hwp", pdf))
				.isInstanceOf(ConversionException.class).hasMessageContaining(".pdf");
	}

	@Test
	void 내용이_PDF가_아니면_거부() throws Exception {
		Path fake = Files.writeString(tmp.resolve("fake.pdf"), "hello");

		assertThatThrownBy(() -> validator.validate("fake.pdf", fake))
				.isInstanceOf(ConversionException.class).hasMessage("PDF 파일이 아닙니다.");
	}

	@Test
	void 헤더만_PDF인_깨진_파일은_거부() throws Exception {
		Path broken = Files.writeString(tmp.resolve("broken.pdf"), "%PDF-1.7 garbage");

		assertThatThrownBy(() -> validator.validate("broken.pdf", broken))
				.isInstanceOf(ConversionException.class).hasMessageContaining("열 수 없습니다");
	}

	@Test
	void 페이지_수_상한_초과는_거부() throws Exception {
		Path pdf = TestPdfs.write(tmp.resolve("a.pdf"), 4);

		assertThatThrownBy(() -> validator.validate("a.pdf", pdf))
				.isInstanceOf(ConversionException.class).hasMessageContaining("최대 3쪽");
	}
}
