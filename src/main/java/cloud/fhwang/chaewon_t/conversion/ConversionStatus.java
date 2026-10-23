package cloud.fhwang.chaewon_t.conversion;

/**
 * PDF 읽기 진행 상태 (API 응답). OCR이 필요한 PDF는 몇 분 걸려서, 업로드는 바로 돌아오고 화면이 이 상태를 물어본다.
 *
 * @param donePages  다 읽은 페이지 수
 * @param totalPages 전체 페이지 수 (읽기 시작 전이면 0)
 * @param ocrPages   글자 인식(OCR)으로 읽는 페이지 수 (인쇄 PDF는 필요한 쪽만 OCR해 진행 중에 늘어날 수 있다)
 * @param ocrDone    OCR이 끝난 페이지 수
 * @param error      FAILED일 때 화면에 보여 줄 문장
 * @param remainingSeconds 읽는 중일 때 남은 시간 예상 (초). 모르면 null
 */
public record ConversionStatus(String id, State state, int donePages, int totalPages, int ocrPages, int ocrDone,
		String error, Integer remainingSeconds) {

	public ConversionStatus(String id, State state, int donePages, int totalPages, int ocrPages, String error) {
		this(id, state, donePages, totalPages, ocrPages, ocrPages, error, null);
	}

	ConversionStatus withRemaining(Integer seconds) {
		return new ConversionStatus(id, state, donePages, totalPages, ocrPages, ocrDone, error, seconds);
	}

	public enum State {
		PROCESSING, READY, FAILED
	}

	static ConversionStatus processing(String id, int donePages, int totalPages, int ocrPages, int ocrDone) {
		return new ConversionStatus(id, State.PROCESSING, donePages, totalPages, ocrPages, ocrDone, null, null);
	}

	static ConversionStatus failed(String id, String error) {
		return new ConversionStatus(id, State.FAILED, 0, 0, 0, error);
	}
}
