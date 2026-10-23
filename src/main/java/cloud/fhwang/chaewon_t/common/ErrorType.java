package cloud.fhwang.chaewon_t.common;

import org.springframework.http.HttpStatus;

/** 오류 종류. 로그에는 이 값만 남긴다 (문서 내용 노출 방지). */
public enum ErrorType {
	INVALID_FILE(HttpStatus.BAD_REQUEST),
	TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND),
	TEMPLATE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
	JOB_NOT_FOUND(HttpStatus.NOT_FOUND),
	EXTRACTION_FAILED(HttpStatus.BAD_REQUEST),
	OCR_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
	EXTRACTION_IN_PROGRESS(HttpStatus.CONFLICT),
	RENDER_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
	RESULT_NOT_READY(HttpStatus.CONFLICT),
	STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR);

	private final HttpStatus status;

	ErrorType(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
