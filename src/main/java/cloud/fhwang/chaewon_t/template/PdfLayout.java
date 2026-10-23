package cloud.fhwang.chaewon_t.template;

/**
 * PDF 시험지 배치값 (mapping.json의 pdf). 단위 pt, 원점은 페이지 왼쪽 위.
 *
 * @param columnSplit     왼쪽 단과 오른쪽 단의 경계 x
 * @param headerBottom    이 y보다 위는 페이지 머리글로 보고 본문에서 뺀다.
 *                        페이지에 머리글 구분선(페이지 폭만 한 가로선)이 있으면 그 선을 대신 쓴다 (PdfLayoutReader.pageLayout)
 * @param footerTop       이 y보다 아래는 꼬리글(쪽 번호 등)로 보고 뺀다. 꼬리글 구분선이 있으면 그 선을 대신 쓴다
 * @param boxLineMinWidth 이보다 긴 가로선은 밑줄이 아니라 상자 테두리로 본다
 */
public record PdfLayout(float columnSplit, float headerBottom, float footerTop, float boxLineMinWidth) {

	public PdfLayout withBands(float headerBottom, float footerTop) {
		return new PdfLayout(columnSplit, headerBottom, footerTop, boxLineMinWidth);
	}
}
