package cloud.fhwang.chaewon_t.common;

/**
 * 사용자에게 보여 줄 수 있는 오류. message는 화면에 그대로 나가므로 문서 내용·추출값을 넣지 않는다.
 */
public class ConversionException extends RuntimeException {

	private final ErrorType type;

	public ConversionException(ErrorType type, String message) {
		this(type, message, null);
	}

	public ConversionException(ErrorType type, String message, Throwable cause) {
		super(message, cause);
		this.type = type;
	}

	public ErrorType type() {
		return type;
	}
}
