package cloud.fhwang.chaewon_t.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * API 오류를 ProblemDetail(RFC 9457) JSON으로 낸다.
 * 잘못된 요청 본문·파일 누락·업로드 크기 초과 같은 스프링 예외는 부모 클래스(ResponseEntityExceptionHandler)가 같은 형식으로 처리한다.
 *
 * 응답 예: {"status":400, "title":"Bad Request", "detail":"PDF 파일이 아닙니다.", "errorType":"INVALID_FILE"}
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ConversionException.class)
	public ProblemDetail conversion(ConversionException e) {
		// 오류 종류와 원인 클래스 이름만 남긴다. 원인 메시지에 문서 내용이 섞일 수 있어 debug에서만 스택을 본다
		log.warn("변환 오류: {} (원인: {})", e.type(),
				e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
		log.debug("변환 오류 상세", e);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.type().status(), e.getMessage());
		problem.setProperty("errorType", e.type().name());
		return problem;
	}

	/** 예상하지 못한 오류. 화면에는 일반 메시지만 보낸다 */
	@ExceptionHandler(RuntimeException.class)
	public ProblemDetail unexpected(RuntimeException e) {
		log.error("처리하지 못한 오류: {}", e.getClass().getName());
		log.debug("처리하지 못한 오류 상세", e);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "처리 중 오류가 발생했습니다.");
		problem.setProperty("errorType", "INTERNAL");
		return problem;
	}
}
