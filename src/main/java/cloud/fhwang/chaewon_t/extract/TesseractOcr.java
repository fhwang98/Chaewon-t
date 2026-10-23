package cloud.fhwang.chaewon_t.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import cloud.fhwang.chaewon_t.common.AppProperties;
import cloud.fhwang.chaewon_t.common.ConversionException;
import cloud.fhwang.chaewon_t.common.ErrorType;
import cloud.fhwang.chaewon_t.extract.HocrParser.OcrLine;
import jakarta.annotation.PreDestroy;

/**
 * Tesseract 실행. Java 바인딩(tess4j) 대신 명령어로 부른다:
 * 서버에는 apt install만 하면 되고, Tesseract가 죽어도 서버 프로세스는 함께 죽지 않는다.
 *
 * 이미지 한 장 = 프로세스 하나. 여러 장을 스레드 풀에서 동시에 돌린다.
 * 그래서 Tesseract 자체의 멀티스레드(OpenMP)는 끈다(OMP_THREAD_LIMIT=1). 둘 다 켜면 CPU를 나눠 먹어 오히려 느려진다.
 */
@Component
public class TesseractOcr {

	private static final Logger log = LoggerFactory.getLogger(TesseractOcr.class);

	/** 페이지 분할 방식: 4 = 크기가 다른 글자가 섞인 한 단, 6 = 한 덩어리 (머리글) */
	static final int PSM_COLUMN = 4;
	static final int PSM_BLOCK = 6;

	private final AppProperties.Ocr props;
	private final ExecutorService pool;
	private final int threads;

	@Autowired
	public TesseractOcr(AppProperties props) {
		this(props.ocr());
	}

	public TesseractOcr(AppProperties.Ocr props) {
		this.props = props;
		this.threads = props.threads() > 0 ? props.threads()
				: Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
		AtomicInteger seq = new AtomicInteger();
		this.pool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "ocr-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	int dpi() {
		return props.dpi();
	}

	/** 동시에 돌리는 Tesseract 수 */
	public int threads() {
		return threads;
	}

	<T> Future<T> submit(Callable<T> task) {
		return pool.submit(task);
	}

	@PreDestroy
	public void shutdown() {
		pool.shutdownNow();
	}

	/** 이미지 한 장을 읽어 줄 목록으로. 결과(hOCR·텍스트)는 이미지 옆에 잠깐 썼다가 지운다 */
	List<OcrLine> recognize(Path image, int psm) {
		return recognize(image, psm, props.dpi(), null);
	}

	/**
	 * @param dpi      그림 해상도 (다시 읽기용 600dpi 그림 등)
	 * @param language 언어 (-l). null이면 설정값(kor+eng)
	 */
	List<OcrLine> recognize(Path image, int psm, int dpi, String language) {
		String base = image.getFileName().toString().replaceFirst("\\.png$", "") + ".ocr";
		Path outBase = image.resolveSibling(base);
		Path hocr = image.resolveSibling(base + ".hocr");
		Path txt = image.resolveSibling(base + ".txt");
		Path err = image.resolveSibling(base + ".err");
		ProcessBuilder pb = new ProcessBuilder(command(image, outBase, psm, dpi, language == null ? props.languages() : language))
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(err.toFile());
		pb.environment().put("OMP_THREAD_LIMIT", "1");
		Process process;
		try {
			process = pb.start();
		} catch (IOException e) {
			throw new ConversionException(ErrorType.OCR_FAILED,
					"서버에 글자 인식 프로그램(Tesseract)이 설치되어 있지 않습니다.", e);
		}
		try {
			if (!process.waitFor(props.pageTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new ConversionException(ErrorType.OCR_FAILED, "글자 인식(OCR)이 너무 오래 걸려 멈췄습니다.");
			}
			if (process.exitValue() != 0) {
				// stderr에는 파일 경로·설정 오류만 나온다 (문서 내용 없음). 언어 데이터가 없을 때 원인을 알 수 있게 첫 줄을 남긴다
				log.warn("Tesseract 종료 코드 {}: {}", process.exitValue(), firstLine(err));
				throw new ConversionException(ErrorType.OCR_FAILED, "글자 인식(OCR)에 실패했습니다.");
			}
			List<OcrLine> lines;
			try (InputStream in = Files.newInputStream(hocr)) {
				lines = HocrParser.parse(in);
			}
			return HocrParser.withSpaces(lines, Files.readString(txt));
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new ConversionException(ErrorType.OCR_FAILED, "글자 인식(OCR)이 중단되었습니다.", e);
		} catch (IOException e) {
			throw new ConversionException(ErrorType.OCR_FAILED, "글자 인식(OCR) 결과를 읽지 못했습니다.", e);
		} finally {
			deleteQuietly(hocr);
			deleteQuietly(txt);
			deleteQuietly(err);
		}
	}

	/** 출력 이름(outBase)에 .hocr·.txt가 붙어 두 파일이 생긴다 */
	private List<String> command(Path image, Path outBase, int psm, int dpi, String language) {
		List<String> cmd = new ArrayList<>(List.of(props.command(), image.toString(), outBase.toString(),
				"--dpi", String.valueOf(dpi),
				"--psm", String.valueOf(psm),
				"-l", language));
		if (Files.isDirectory(props.tessdataDir())) {
			cmd.addAll(List.of("--tessdata-dir", props.tessdataDir().toAbsolutePath().toString()));
		}
		// 설정 파일 이름("hocr", "txt")으로 주면 --tessdata-dir 아래 configs/에서 찾다가 실패한다. 변수로 직접 켠다.
		// preserve_interword_spaces: 텍스트 출력이 한글 음절 사이에 공백을 넣지 않게 한다 (HocrParser.withSpaces)
		cmd.addAll(List.of("-c", "tessedit_create_hocr=1", "-c", "tessedit_create_txt=1",
				"-c", "preserve_interword_spaces=1"));
		return cmd;
	}

	private static String firstLine(Path file) {
		try {
			return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).findFirst().orElse("-");
		} catch (IOException e) {
			return "-";
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			// 작업 폴더는 보관 시간이 지나면 통째로 지워진다
		}
	}
}
