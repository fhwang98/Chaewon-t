package cloud.fhwang.chaewon_t.template;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeaderTitleFitTest {

	@Test
	void 템플릿_머리말_길이는_그대로_두고_긴_이름은_줄인다() {
		// 템플릿에 들어 있던 이름은 17pt 한 줄에 들어갔다
		assertThat(HeaderTitleFit.size("26년 다라고1 (2학기 중간) 고1 26년", 17, HeaderTitleFit.TEMPLATE_WIDTH)).isEqualTo(17f);
		// 한글에서 두 줄이 된 이름 (2026-10-22)
		float size = HeaderTitleFit.size("25년 가나고1 2학기 기말고사 교과서 공통영어2 ABC(가) 4과 기출문제", 17,
				HeaderTitleFit.TEMPLATE_WIDTH);
		assertThat(size).isLessThan(17f).isGreaterThanOrEqualTo(14f);
		assertThat(size * 2).isEqualTo((float) Math.floor(size * 2)); // 0.5pt 단위
	}

	@Test
	void 아무리_길어도_가장_작은_크기_아래로는_줄이지_않는다() {
		assertThat(HeaderTitleFit.size("가".repeat(80), 17, HeaderTitleFit.TEMPLATE_WIDTH)).isEqualTo(HeaderTitleFit.MIN_SIZE);
		assertThat(HeaderTitleFit.overflows("가".repeat(80), HeaderTitleFit.TEMPLATE_WIDTH)).isTrue();
	}
}
