package cloud.fhwang.chaewon_t.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversionEtaTest {

	@Test
	void 쪽수를_모르면_모름_OCR이_없으면_0() {
		assertThat(ConversionEta.remainingSeconds(500, 0, 0, 0, 4)).isNull();
		assertThat(ConversionEta.remainingSeconds(500, 37, 0, 0, 4)).isZero();
	}

	@Test
	void 처음에는_OCR할_쪽수로_어림한다() {
		// OCR할 4쪽 × (20초 / 4) = 20초 + 마무리 12초
		assertThat(ConversionEta.remainingSeconds(0, 35, 4, 0, 4)).isEqualTo(32);
	}

	@Test
	void 동시_실행_수만큼_끝나면_실제_속도로_계산한다() {
		// OCR 4쪽에 40초 → 쪽당 10초, OCR할 쪽이 18쪽으로 늘었으면 남은 14쪽 = 140초 + 12초
		assertThat(ConversionEta.remainingSeconds(40_000, 35, 18, 4, 4)).isEqualTo(152);
		assertThat(ConversionEta.remainingSeconds(200_000, 35, 18, 18, 4)).isEqualTo(12); // 마무리만 남음
	}
}
