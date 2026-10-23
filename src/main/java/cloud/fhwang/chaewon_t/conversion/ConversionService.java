package cloud.fhwang.chaewon_t.conversion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import cloud.fhwang.chaewon_t.common.ConversionException;
import cloud.fhwang.chaewon_t.common.ErrorType;
import cloud.fhwang.chaewon_t.common.TempWorkspace;
import cloud.fhwang.chaewon_t.extract.ExamDocument;
import cloud.fhwang.chaewon_t.extract.ExamPdfReader;
import cloud.fhwang.chaewon_t.extract.ExamValidator;
import cloud.fhwang.chaewon_t.extract.PdfValidator;
import cloud.fhwang.chaewon_t.extract.TesseractOcr;
import cloud.fhwang.chaewon_t.render.ExamHwpxRenderer;
import cloud.fhwang.chaewon_t.template.Template;
import cloud.fhwang.chaewon_t.template.TemplateRegistry;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * 변환 흐름: 업로드 → 추출(백그라운드) → (에디터에서 수정, 자동 저장) → HWPX 생성 → 내려받기.
 *
 * DB 없이 작업 폴더 data/tmp/{id}/ 의 파일로 상태를 들고 간다.
 *   input.pdf      업로드 원본 (읽기에 실패하면 바로 지운다)
 *   status.json    읽기가 끝난 결과: READY(OCR 페이지 수 포함) 또는 FAILED(오류 문장)
 *   document.json  시험지 내용 (에디터에서 저장하면 덮어씀)
 *   result.hwpx    생성 결과 (문서를 고치면 지워서, 옛 결과를 내려받지 않게 한다)
 *
 * 읽는 중인 작업의 진행 상황은 메모리(running)에만 있다. 서버가 다시 시작되면 사라지므로,
 * 폴더는 있는데 status.json이 없고 running에도 없으면 "중단됨"으로 본다.
 */
@Service
public class ConversionService {

	private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

	private static final String INPUT = "input.pdf";
	private static final String STATUS = "status.json";
	private static final String DOCUMENT = "document.json";
	private static final String RESULT = "result.hwpx";

	/** 파일 이름에 쓸 수 없는 글자 (Windows 기준) */
	private static final String UNSAFE_FILE_CHARS = "[\\\\/:*?\"<>|\\p{Cntrl}]";

	private final TemplateRegistry templates;
	private final TempWorkspace workspace;
	private final PdfValidator validator;
	private final ExamPdfReader reader;
	private final ExamHwpxRenderer renderer;
	private final TesseractOcr ocr;
	private final JsonMapper json = JsonMapper.shared();
	private final Map<String, ConversionStatus> running = new ConcurrentHashMap<>();
	/** 읽는 중인 작업 (취소할 때 멈추려고 들고 있는다) */
	private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();
	/** 읽기 시작 시각 (남은 시간 예상용, System.nanoTime) */
	private final Map<String, Long> startedAt = new ConcurrentHashMap<>();
	/** 취소한 작업. 멈추는 도중 실패 처리(status.json 쓰기)를 하지 않게 표시해 둔다 */
	private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
	/** 읽기 작업. OCR은 페이지 단위로 TesseractOcr의 풀에서 다시 나눠 돌므로 작업 자체는 둘만 동시에 */
	private final ExecutorService jobs = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "conversion");
		t.setDaemon(true);
		return t;
	});

	public ConversionService(TemplateRegistry templates, TempWorkspace workspace, PdfValidator validator,
			ExamPdfReader reader, ExamHwpxRenderer renderer, TesseractOcr ocr) {
		this.templates = templates;
		this.workspace = workspace;
		this.validator = validator;
		this.reader = reader;
		this.renderer = renderer;
		this.ocr = ocr;
	}

	/**
	 * PDF를 저장·검증하고 읽기를 백그라운드로 시작한다. 검증 실패는 바로 오류로 돌려주고 작업 폴더를 지운다.
	 * 읽기 결과는 status()로 확인한다
	 */
	public ConversionStatus upload(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ConversionException(ErrorType.INVALID_FILE, "PDF 파일을 선택하세요.");
		}
		Template template = templates.defaultTemplate();
		String id = workspace.create();
		try {
			Path pdf = workspace.file(id, INPUT);
			file.transferTo(pdf);
			validator.validate(file.getOriginalFilename(), pdf);
		} catch (IOException e) {
			workspace.delete(id);
			throw new ConversionException(ErrorType.STORAGE_FAILED, "업로드한 파일을 저장하지 못했습니다.", e);
		} catch (RuntimeException e) {
			workspace.delete(id);
			throw e;
		}
		long size = file.getSize(); // 요청이 끝나면 업로드 임시 파일이 사라지므로 미리 읽어 둔다
		ConversionStatus status = ConversionStatus.processing(id, 0, 0, 0, 0);
		running.put(id, status);
		startedAt.put(id, System.nanoTime());
		tasks.put(id, jobs.submit(() -> extract(id, template, size)));
		return status;
	}

	/** 백그라운드 읽기. 끝나면 status.json을 쓴 다음 running에서 뺀다 (그 사이에 상태가 비지 않게) */
	private void extract(String id, Template template, long size) {
		long start = System.nanoTime();
		try {
			ExamPdfReader.Result result = reader.read(workspace.file(id, INPUT), template.mapping().pdf(),
					workspace.directory(id),
					(done, total, ocrPages, ocrDone) -> running.compute(id, (k, old) -> ConversionStatus.processing(id,
							Math.max(done, old == null ? 0 : old.donePages()), total,
							Math.max(ocrPages, old == null ? 0 : old.ocrPages()),
							Math.max(ocrDone, old == null ? 0 : old.ocrDone())))); // 늦게 온 작은 값이 덮지 않게
			save(id, result.document());
			writeStatus(new ConversionStatus(id, ConversionStatus.State.READY, result.pages(), result.pages(),
					result.ocrPages(), null));
			log.info("추출 완료: {} bytes, {}쪽(OCR {}쪽), 문항 {}개, {} ms", size, result.pages(), result.ocrPages(),
					result.document().questions().size(), millisSince(start));
		} catch (RuntimeException e) {
			if (cancelled.contains(id)) {
				log.info("추출 취소: {} ms", millisSince(start));
				workspace.delete(id); // 멈추는 사이 남은 파일까지 지운다
			} else if (e instanceof ConversionException ce) {
				log.warn("추출 실패: {} (원인: {})", ce.type(), ce.getCause() == null ? "-" : ce.getCause().getClass().getSimpleName());
				fail(id, ce.getMessage());
			} else {
				log.error("추출 중 처리하지 못한 오류: {}", e.getClass().getName());
				log.debug("추출 오류 상세", e);
				fail(id, "PDF를 읽는 중 오류가 발생했습니다.");
			}
		} finally {
			running.remove(id);
			tasks.remove(id);
			cancelled.remove(id);
			startedAt.remove(id);
		}
	}

	/**
	 * 작업을 지운다. 읽는 중이면 멈춘다(돌고 있는 Tesseract도 끝낸다). 다 읽은 작업이면 파일만 지운다.
	 * 없는 작업이어도 오류로 보지 않는다 (두 번 눌러도 같은 결과)
	 */
	public void cancel(String id) {
		Future<?> task = tasks.get(id);
		if (task != null) {
			cancelled.add(id);
			task.cancel(true);
		}
		// cancel(true) 직후에도 isDone()은 true라 작업 스레드가 아직 멈추는 중일 수 있다. 표시는 extract가 끝날 때 지운다
		// (시작 전에 취소된 작업은 extract가 불리지 않아 표시가 남지만 UUID 하나라 무시한다)
		running.remove(id);
		workspace.delete(id);
	}

	private void fail(String id, String message) {
		deleteQuietly(workspace.file(id, INPUT));
		writeStatus(ConversionStatus.failed(id, message));
	}

	public ConversionStatus status(String id) {
		ConversionStatus current = running.get(id);
		if (current != null) {
			Long start = startedAt.get(id);
			long elapsed = start == null ? 0 : (System.nanoTime() - start) / 1_000_000;
			return current.withRemaining(ConversionEta.remainingSeconds(elapsed, current.totalPages(),
					current.ocrPages(), current.ocrDone(), ocr.threads()));
		}
		Path file = workspace.file(id, STATUS);
		if (Files.isRegularFile(file)) {
			return json.readValue(file, ConversionStatus.class);
		}
		if (!workspace.exists(id)) {
			throw jobNotFound();
		}
		return ConversionStatus.failed(id, "PDF 읽기가 중단되었습니다. 서버가 다시 시작되었을 수 있으니 PDF를 다시 올려 주세요.");
	}

	public ConversionView get(String id) {
		return view(id, load(id));
	}

	/** 에디터 자동 저장. 내용이 바뀌었으므로 이전 결과 HWPX는 지운다 */
	public ConversionView update(String id, ExamDocument doc) {
		load(id); // 작업이 있는지 확인
		save(id, doc);
		deleteQuietly(workspace.file(id, RESULT));
		return view(id, doc);
	}

	public void render(String id) {
		ExamDocument doc = load(id);
		Template template = templates.defaultTemplate();
		long start = System.nanoTime();
		Path out = workspace.file(id, RESULT);
		renderer.render(template.hwpxPath(), out, doc, template.mapping().styles());
		log.info("생성 완료: 문항 {}개, {} bytes, {} ms", doc.questions().size(), sizeOf(out), millisSince(start));
	}

	public Download download(String id) {
		ExamDocument doc = load(id);
		Path file = workspace.file(id, RESULT);
		if (!Files.isRegularFile(file)) {
			throw new ConversionException(ErrorType.RESULT_NOT_READY, "아직 HWPX를 만들지 않았습니다.");
		}
		return new Download(file, fileName(doc.headerTitle()));
	}

	/** 내려받을 파일과 이름 */
	public record Download(Path file, String fileName) {
	}

	/** 머리말 제목을 파일 이름으로. 못 쓰는 글자는 빼고, 비면 "시험지" */
	static String fileName(String title) {
		String name = title == null ? "" : title.replaceAll(UNSAFE_FILE_CHARS, "").strip();
		return (name.isEmpty() ? "시험지" : name) + ".hwpx";
	}

	/** 경고 = 내용 검사 결과 + OCR로 읽은 페이지가 있으면 그 안내 */
	private ConversionView view(String id, ExamDocument doc) {
		List<String> warnings = new ArrayList<>();
		int ocrPages = status(id).ocrPages();
		if (ocrPages > 0) {
			warnings.add("글자 인식(OCR)으로 읽은 페이지가 " + ocrPages + "쪽 있습니다. 틀린 글자·선택지 번호·밑줄을 확인하세요.");
		}
		warnings.addAll(ExamValidator.warnings(doc));
		return new ConversionView(id, doc, warnings, Files.isRegularFile(workspace.file(id, RESULT)));
	}

	/** 읽기가 끝난 문서. 읽는 중이면 409, 실패했으면 그 오류 */
	private ExamDocument load(String id) {
		Path file = workspace.file(id, DOCUMENT);
		if (!Files.isRegularFile(file)) {
			ConversionStatus status = status(id);
			if (status.state() == ConversionStatus.State.PROCESSING) {
				throw new ConversionException(ErrorType.EXTRACTION_IN_PROGRESS, "아직 PDF를 읽는 중입니다.");
			}
			if (status.state() == ConversionStatus.State.FAILED) {
				throw new ConversionException(ErrorType.EXTRACTION_FAILED, status.error());
			}
			throw jobNotFound();
		}
		return json.readValue(file, ExamDocument.class);
	}

	private void writeStatus(ConversionStatus status) {
		json.writeValue(workspace.file(status.id(), STATUS), status);
	}

	@PreDestroy
	void shutdown() {
		jobs.shutdownNow();
	}

	private static ConversionException jobNotFound() {
		return new ConversionException(ErrorType.JOB_NOT_FOUND,
				"변환 작업을 찾을 수 없습니다. 보관 시간이 지나 삭제되었을 수 있습니다.");
	}

	private void save(String id, ExamDocument doc) {
		json.writeValue(workspace.file(id, DOCUMENT), doc);
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.warn("이전 결과 파일 삭제 실패: {}", e.getClass().getSimpleName());
		}
	}

	private static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private static long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			return -1;
		}
	}
}
