import type { Editor } from '@tiptap/react';
import { useEffect, useRef, useState } from 'react';
import { on } from './buttons';

/** 글자 색 고르기 판의 색 (한글 기본 색 일부) */
const COLORS = [
  { value: '#000000', label: '검정' }, { value: '#ff0000', label: '빨강' }, { value: '#0000ff', label: '파랑' },
  { value: '#008000', label: '초록' }, { value: '#ff6600', label: '주황' }, { value: '#800080', label: '보라' },
  { value: '#203a7b', label: '남색' }, { value: '#808080', label: '회색' },
];

/** 단추를 눌러도 에디터에서 고른 곳이 풀리지 않게 mousedown 기본 동작을 막는다 */
const keepSelection = (e: { preventDefault: () => void }) => e.preventDefault();

/**
 * 글자 색: 누르면 색 판이 열린다. 자주 쓰는 색, 기본(검정으로 되돌림), 다른 색(브라우저 색 고르기).
 * 판의 단추는 keepSelection으로 에디터 선택을 지킨다
 */
export default function ColorPicker({ editor, color }: { editor: Editor; color: string | null }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  const apply = (value: string | null) => {
    const chain = editor.chain().focus();
    (value ? chain.setMark('fontColor', { color: value }) : chain.unsetMark('fontColor')).run();
    setOpen(false);
  };

  return (
    <div className="color-menu" ref={box}>
      <button type="button" className={on('color-button', open)} title="글자 색" aria-label="글자 색"
        aria-expanded={open} onMouseDown={keepSelection} onClick={() => setOpen(!open)}>
        <span className="color-letter" style={{ borderBottomColor: color ?? '#000000' }}>가</span>
        <span className="caret" aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="color-palette" role="dialog" aria-label="글자 색 고르기">
          <div className="swatches">
            {COLORS.map((c) => (
              <button key={c.value} type="button" className={on('swatch', color === c.value)} title={c.label}
                aria-label={c.label} style={{ background: c.value }}
                onMouseDown={keepSelection} onClick={() => apply(c.value)} />
            ))}
          </div>
          <button type="button" className="text-btn" onMouseDown={keepSelection} onClick={() => apply(null)}>
            기본 (검정)
          </button>
          <label className="text-btn other-color">
            다른 색…
            <input type="color" value={color ?? '#000000'} aria-label="다른 색"
              onChange={(e) => apply(e.target.value)} />
          </label>
        </div>
      )}
    </div>
  );
}
