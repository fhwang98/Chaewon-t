package cloud.fhwang.chaewon_t.template;

/**
 * 머리말 시험 이름이 한 줄에 들어가는 글자 크기.
 *
 * 머리말은 한 줄(17pt)을 전제로 그 아래에 선을 그어 둔다. 이름이 길어 두 줄이 되면 선이 한 줄만큼 내려가 본문을 덮는다
 * (2026-10-22 사용자 확인: "25년 가나고1 2학기 기말고사 교과서 공통영어2 ABC(가) 4과 기출문제"). 그래서 넘칠 것 같으면 글자를 줄인다.
 *
 * 머리말 글꼴(평창 평화체 Bold)의 글자 폭을 서버에서 잴 수 없어 글자 종류별 폭(글자 크기 배수)으로 어림한다.
 * 한글 1, 영문 대문자·숫자 0.62, 소문자 0.55, 공백 0.3, 괄호·문장 부호 0.35. 실제보다 넉넉히 잡아 조금 일찍 줄인다.
 * 에디터(frontend/src/editor/headerFit.ts)도 같은 계산으로 머리말 크기를 보여 준다. 값을 바꾸면 둘 다 고친다
 */
public final class HeaderTitleFit {

	/** 이보다 작게는 줄이지 않는다 (그래도 넘치면 두 줄이 된다) */
	public static final float MIN_SIZE = 10f;
	/** 템플릿 머리말의 글자 크기와 글자 영역 너비 (pt). 템플릿 section0.xml의 charPr 5 height, header subList textWidth */
	public static final float TEMPLATE_SIZE = 17f;
	public static final float TEMPLATE_WIDTH = 498.92f;
	/** 계산이 어림이라 너비의 이만큼만 쓴다 */
	private static final float SAFETY = 0.97f;

	private HeaderTitleFit() {
	}

	/** 글자 크기(pt). 기본 크기로 들어가면 그대로, 아니면 0.5pt 단위로 내림 */
	public static float size(String title, float baseSize, float widthPt) {
		float ems = ems(title);
		if (ems * baseSize <= widthPt * SAFETY) {
			return baseSize;
		}
		float fitted = (float) Math.floor(widthPt * SAFETY / ems * 2) / 2;
		return Math.max(MIN_SIZE, Math.min(baseSize, fitted));
	}

	/** 가장 작게 줄여도 한 줄에 안 들어가는지 */
	public static boolean overflows(String title, float widthPt) {
		return ems(title) * MIN_SIZE > widthPt * SAFETY;
	}

	/** 글자 폭 합 (글자 크기 배수) */
	public static float ems(String text) {
		float sum = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			sum += width(cp);
		}
		return sum;
	}

	private static float width(int cp) {
		if (cp == ' ') {
			return 0.3f;
		}
		if (cp < 128) {
			if (Character.isUpperCase(cp) || Character.isDigit(cp)) {
				return 0.62f;
			}
			return Character.isLowerCase(cp) ? 0.55f : 0.35f;
		}
		return 1f;
	}
}
