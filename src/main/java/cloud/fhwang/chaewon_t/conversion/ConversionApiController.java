package cloud.fhwang.chaewon_t.conversion;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import cloud.fhwang.chaewon_t.extract.ExamDocument;
import jakarta.validation.Valid;

/**
 * 변환 API. 작업 1건 = id(UUID) 하나.
 *
 *   POST /api/conversions                 PDF 업로드 → 202 + 읽기 상태 (읽기는 백그라운드)
 *   GET  /api/conversions/{id}/status     읽기 진행 상태 (PROCESSING → READY / FAILED)
 *   GET  /api/conversions/{id}            현재 내용·경고 (읽는 중이면 409)
 *   PUT  /api/conversions/{id}/document   에디터 자동 저장
 *   POST /api/conversions/{id}/render     HWPX 생성
 *   GET  /api/conversions/{id}/download   HWPX 내려받기
 *   DELETE /api/conversions/{id}          작업 취소·삭제 (읽는 중이면 멈춘다)
 */
@RestController
@RequestMapping("/api/conversions")
public class ConversionApiController {

	private final ConversionService service;

	public ConversionApiController(ConversionService service) {
		this.service = service;
	}

	/** OCR이 필요한 PDF는 몇 분 걸리므로 기다리지 않고 202(접수됨)를 돌려준다. 화면은 status를 물어본다 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ConversionStatus> upload(@RequestParam("file") MultipartFile file) {
		ConversionStatus status = service.upload(file);
		return ResponseEntity.accepted()
				.location(URI.create("/api/conversions/" + status.id() + "/status"))
				.body(status);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> cancel(@PathVariable String id) {
		service.cancel(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/status")
	public ConversionStatus status(@PathVariable String id) {
		return service.status(id);
	}

	@GetMapping("/{id}")
	public ConversionView get(@PathVariable String id) {
		return service.get(id);
	}

	@PutMapping("/{id}/document")
	public ConversionView update(@PathVariable String id, @Valid @RequestBody ExamDocument document) {
		return service.update(id, document);
	}

	@PostMapping("/{id}/render")
	public ResponseEntity<Void> render(@PathVariable String id) {
		service.render(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/download")
	public ResponseEntity<Resource> download(@PathVariable String id) {
		ConversionService.Download download = service.download(id);
		// 한글 파일 이름은 RFC 5987 형식(filename*=UTF-8''...)으로 보내야 브라우저에서 깨지지 않는다
		ContentDisposition disposition = ContentDisposition.attachment()
				.filename(download.fileName(), StandardCharsets.UTF_8)
				.build();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(new FileSystemResource(download.file()));
	}
}
