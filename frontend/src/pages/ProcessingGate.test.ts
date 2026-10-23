import { describe, expect, it } from 'vitest';
import type { ConversionStatus } from '../api/types';
import { progressText, remainingText } from './ProcessingGate';

const status = (s: Partial<ConversionStatus>): ConversionStatus => ({
  id: 'x', state: 'PROCESSING', donePages: 0, totalPages: 0, ocrPages: 0, error: null, ...s,
});

describe('progressText', () => {
  it('읽기 전에는 준비 중', () => {
    expect(progressText(null)).toBe('PDF를 읽을 준비를 하는 중…');
    expect(progressText(status({}))).toBe('PDF를 읽을 준비를 하는 중…');
  });

  it('쪽수와 글자 인식 쪽수를 보여 준다', () => {
    expect(progressText(status({ donePages: 3, totalPages: 10 }))).toBe('PDF를 읽는 중… 3/10쪽');
    expect(progressText(status({ donePages: 3, totalPages: 35, ocrPages: 35 })))
      .toBe('PDF를 읽는 중… 3/35쪽 (글자 인식 35쪽)');
  });

  it('남은 시간을 어림해 붙인다', () => {
    expect(progressText(status({ donePages: 4, totalPages: 35, ocrPages: 35, remainingSeconds: 313 })))
      .toBe('PDF를 읽는 중… 4/35쪽 (글자 인식 35쪽) · 약 5분 10초 남음');
    expect(remainingText(3)).toBe('곧 끝납니다');
    expect(remainingText(42)).toBe('약 45초 남음');
    expect(remainingText(118)).toBe('약 2분 남음');
    expect(remainingText(null)).toBe('');
  });
});
