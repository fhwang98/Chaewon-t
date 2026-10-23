package cloud.fhwang.chaewon_t.common;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml의 chaewon.* 설정.
 *
 * @param templateDir 템플릿 루트. 그 아래 {종류}/v{번호}/template.hwpx + mapping.json
 * @param tmpDir      업로드·결과 파일 작업 폴더. 그 아래 {UUID}/
 * @param retention   작업 폴더 보관 시간. 지나면 스케줄러가 삭제
 * @param maxPdfPages 업로드 PDF 페이지 수 상한
 * @param template    사용할 템플릿 종류 (templateDir 아래 폴더 이름)
 * @param ocr         글자 정보가 없는 PDF(스캔본·인쇄 PDF)를 읽는 Tesseract 설정
 */
@ConfigurationProperties("chaewon")
public record AppProperties(
		@DefaultValue("data/templates") Path templateDir,
		@DefaultValue("data/tmp") Path tmpDir,
		@DefaultValue("1h") Duration retention,
		@DefaultValue("100") int maxPdfPages,
		@DefaultValue("exam") String template,
		@DefaultValue Ocr ocr) {

	/**
	 * chaewon.ocr.*
	 *
	 * @param command     tesseract 실행 파일 (PATH에서 찾거나 전체 경로)
	 * @param tessdataDir 언어 데이터 폴더. 없으면 Tesseract 기본 위치를 쓴다 (scripts/download-tessdata.sh로 받는다)
	 * @param languages   언어 (-l). 영어 지문 + 한글 문항
	 * @param dpi         페이지를 그림으로 바꿀 해상도. 300보다 낮으면 작은 글자 인식이 떨어진다
	 * @param threads     동시에 돌릴 Tesseract 수. 0이면 CPU 수의 절반
	 * @param pageTimeout 이미지 한 장 OCR 시간 상한. 넘으면 실패로 본다
	 */
	public record Ocr(
			@DefaultValue("tesseract") String command,
			@DefaultValue("data/tessdata") Path tessdataDir,
			@DefaultValue("kor+eng") String languages,
			@DefaultValue("300") int dpi,
			@DefaultValue("0") int threads,
			@DefaultValue("2m") Duration pageTimeout) {

		/** 기본값 (위 @DefaultValue와 같게). 스프링 없이 만드는 테스트·probe용 */
		public static Ocr defaults() {
			return new Ocr("tesseract", Path.of("data/tessdata"), "kor+eng", 300, 0, Duration.ofMinutes(2));
		}
	}
}
