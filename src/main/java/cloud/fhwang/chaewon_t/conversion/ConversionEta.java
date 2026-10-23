package cloud.fhwang.chaewon_t.conversion;

/**
 * PDF 읽기 남은 시간 예상.
 *
 * - 글자 정보가 있는 PDF(OCR 없음): 1초 안에 끝나므로 0
 * - OCR: 남은 OCR 쪽 × 쪽당 시간 + 마무리 시간. 쪽당 시간은 처음에는 어림값(OCR 한 쪽 CPU 시간 / 동시 실행 수),
 *   동시 실행 수만큼 OCR이 끝난 뒤에는 실제 속도(지난 시간 / OCR 끝난 쪽)
 *   (2026-10-22 M1 맥북 에어, 동시 4개: sample2 35쪽 171초 → 쪽당 4.9초 × 4 ≒ 20초. 서버 CPU가 다르면 실제 속도로 곧 바뀐다)
 * - OCR할 쪽은 인쇄 PDF면 진행 중에 늘어날 수 있다(필요한 쪽만 OCR). 그때 남은 시간도 늘어난다
 * - 마무리: 글자 모양 다수결, 드문 글자 다시 읽기(10초 남짓), 문항 나누기
 */
final class ConversionEta {

	private static final double OCR_PAGE_SECONDS_ONE_THREAD = 20;
	private static final int FINISH_SECONDS = 12;

	private ConversionEta() {
	}

	/** 모르면 null (아직 쪽수도 모를 때) */
	static Integer remainingSeconds(long elapsedMillis, int total, int ocrPages, int ocrDone, int threads) {
		if (total <= 0) {
			return null;
		}
		if (ocrPages == 0) {
			return 0;
		}
		int left = Math.max(0, ocrPages - ocrDone);
		double perPage = ocrDone >= Math.max(2, threads)
				? elapsedMillis / 1000.0 / ocrDone
				: OCR_PAGE_SECONDS_ONE_THREAD / Math.max(1, threads);
		return (int) Math.max(1, Math.round(perPage * left + FINISH_SECONDS));
	}
}
