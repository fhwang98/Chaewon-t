package cloud.fhwang.chaewon_t.conversion;

import java.util.List;

import cloud.fhwang.chaewon_t.extract.ExamDocument;

/**
 * 변환 작업 1건의 상태 (API 응답).
 *
 * @param warnings  확인이 필요한 곳 (ExamValidator). 생성은 막지 않는다
 * @param hasResult 지금 내용으로 만든 HWPX가 있는지. 문서를 고치면 false가 된다
 */
public record ConversionView(String id, ExamDocument document, List<String> warnings, boolean hasResult) {
}
