package cloud.fhwang.chaewon_t.template;

/**
 * mapping.json 내용. 예: data/templates/exam/v1/mapping.json
 *
 * @param styles 템플릿 header.xml의 역할별 문단·글자 서식 id
 * @param pdf    입력 PDF 배치값 (단 경계, 머리글·꼬리글 위치 등)
 */
public record TemplateMapping(String documentType, int version, String displayName, ExamStyles styles, PdfLayout pdf) {
}
