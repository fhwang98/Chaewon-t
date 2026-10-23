/**
 * 머리말 시험 이름이 한 줄에 들어가는 글자 크기 (pt). 서버 template/HeaderTitleFit.java와 같은 계산이다.
 * HWPX에서 이름이 길면 글자를 줄여 한 줄에 넣으므로, 화면에서도 같은 크기로 보여 준다 (값을 바꾸면 둘 다 고친다)
 */
export const HEADER_SIZE = 17;
export const HEADER_WIDTH = 498.92;
const MIN_SIZE = 10;
const SAFETY = 0.97;

/** 글자 폭 합 (글자 크기 배수). 한글 1, 영문 대문자·숫자 0.62, 소문자 0.55, 공백 0.3, 괄호·문장 부호 0.35 */
export function ems(text: string): number {
  let sum = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0)!;
    if (ch === ' ') sum += 0.3;
    else if (cp >= 128) sum += 1;
    else if (/[A-Z0-9]/.test(ch)) sum += 0.62;
    else if (/[a-z]/.test(ch)) sum += 0.55;
    else sum += 0.35;
  }
  return sum;
}

export function headerFontSize(title: string, base = HEADER_SIZE, width = HEADER_WIDTH): number {
  const total = ems(title);
  if (total * base <= width * SAFETY) return base;
  const fitted = Math.floor((width * SAFETY / total) * 2) / 2;
  return Math.max(MIN_SIZE, Math.min(base, fitted));
}
