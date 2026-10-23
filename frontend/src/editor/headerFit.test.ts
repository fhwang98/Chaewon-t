import { describe, expect, it } from 'vitest';
import { headerFontSize } from './headerFit';

describe('headerFontSize', () => {
  it('템플릿 길이 이름은 17pt 그대로, 긴 이름은 서버와 같은 크기로 줄인다', () => {
    expect(headerFontSize('26년 다라고1 (2학기 중간) 고1 26년')).toBe(17);
    const size = headerFontSize('25년 가나고1 2학기 기말고사 교과서 공통영어2 ABC(가) 4과 기출문제');
    expect(size).toBeLessThan(17);
    expect(size).toBeGreaterThanOrEqual(14);
  });

  it('아무리 길어도 10pt 아래로는 줄이지 않는다', () => {
    expect(headerFontSize('가'.repeat(80))).toBe(10);
  });
});
