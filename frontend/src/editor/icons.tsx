import type { Align } from './formatting';

/** 선으로 그린 아이콘 (24 칸 좌표, Lucide 모양을 따름). 글자 화살표(↶ ⇤)는 글꼴마다 모양·크기가 달라 SVG로 그린다 */
export function Icon({ d }: { d: string }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

export function AlignIcon({ align }: { align: Align }) {
  // 네 줄: 정렬에 따라 짧은 줄의 위치가 다르다
  const lines = [16, 10, 16, 12];
  return (
    <svg width="16" height="14" viewBox="0 0 16 14" aria-hidden="true">
      {lines.map((w, i) => {
        const width = align === 'justify' ? 16 : w;
        const x = align === 'left' || align === 'justify' ? 0 : align === 'right' ? 16 - width : (16 - width) / 2;
        return <rect key={i} x={x} y={i * 4} width={width} height="2" fill="currentColor" />;
      })}
    </svg>
  );
}
