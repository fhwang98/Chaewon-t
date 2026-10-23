import { useEditorState, type Editor } from '@tiptap/react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  clearAllUncertain, clearUncertain, selectNextUncertain, selectRange, uncertainList, uncertainRanges,
} from './extensions';
import {
  changeIndent, clearFormatting, findMatches, findNext, replaceAll, replaceNext, setSearch, type Align,
} from './formatting';

const FONT_SIZES = [6, 7, 8, 9, 10, 11, 12, 14, 16, 18, 20, 24];
const FIRST_LINE: { label: string; value: number | null }[] = [
  { label: '첫 줄 기본', value: null },
  { label: '첫 줄 들여쓰기', value: 10 },
  { label: '첫 줄 내어쓰기', value: -10 },
];
/** 켜고 끄는 글자 모양. toggleBold() 등은 모두 toggleMark(이름)과 같다 */
const MARKS: { mark: 'bold' | 'italic' | 'underline' | 'strike'; label: string; keys: string; icon: ReactNode }[] = [
  { mark: 'bold', label: '굵게', keys: 'Ctrl+B', icon: <b>가</b> },
  { mark: 'italic', label: '기울임', keys: 'Ctrl+I', icon: <i>가</i> },
  { mark: 'underline', label: '밑줄', keys: 'Ctrl+U', icon: <u>가</u> },
  { mark: 'strike', label: '취소선', keys: 'Ctrl+Shift+S', icon: <s>가</s> },
];
const ALIGNS: { value: Align; label: string }[] = [
  { value: 'left', label: '왼쪽 정렬' },
  { value: 'center', label: '가운데 정렬' },
  { value: 'right', label: '오른쪽 정렬' },
  { value: 'justify', label: '양쪽 정렬' },
];
/** 글자 색 고르기 판의 색 (한글 기본 색 일부) */
const COLORS = [
  { value: '#000000', label: '검정' }, { value: '#ff0000', label: '빨강' }, { value: '#0000ff', label: '파랑' },
  { value: '#008000', label: '초록' }, { value: '#ff6600', label: '주황' }, { value: '#800080', label: '보라' },
  { value: '#203a7b', label: '남색' }, { value: '#808080', label: '회색' },
];

interface Props {
  editor: Editor;
  zoom: number;
  zooms: number[];
  onZoom: (z: number) => void;
  leading?: ReactNode;
  actions?: ReactNode;
}

/**
 * 일반 문서 에디터 같은 도구 막대 두 줄.
 *   1줄: 처음으로, 되돌리기, 찾기·바꾸기, 확인할 곳, 확대, 저장 상태, HWPX
 *   2줄: 글자 크기, 굵게·기울임·밑줄·취소선, 글자 색, 정렬, 들여쓰기, 첫 줄, 서식 지우기
 * (문단 역할 고르기·특수 문자·위/아래 첨자는 쓰지 않아 뺐다. 2026-10-22 사용자 요청)
 */
export default function Toolbar({ editor, zoom, zooms, onZoom, leading, actions }: Props) {
  const state = useEditorState({
    editor,
    selector: ({ editor }) => ({
      bold: editor.isActive('bold'),
      italic: editor.isActive('italic'),
      underline: editor.isActive('underline'),
      strike: editor.isActive('strike'),
      size: (editor.getAttributes('fontSize').size as number | undefined) ?? null,
      color: (editor.getAttributes('fontColor').color as string | undefined) ?? null,
      align: (editor.getAttributes('paragraph').align ?? null) as Align | null,
      firstLine: (editor.getAttributes('paragraph').firstLine ?? null) as number | null,
      canUndo: editor.can().undo(),
      canRedo: editor.can().redo(),
      uncertain: uncertainRanges(editor.state).length,
      inUncertain: editor.isActive('uncertain'),
    }),
  });
  const [panel, setPanel] = useState<'find' | 'checks' | null>(null);

  // Ctrl/⌘+F: 찾기, Ctrl/⌘+H: 바꾸기 (에디터 안에서만. 브라우저 찾기 대신 문서 찾기)
  useEffect(() => {
    const dom = editor.view.dom;
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'h')) {
        e.preventDefault();
        setPanel('find');
      }
    };
    dom.addEventListener('keydown', onKey);
    return () => dom.removeEventListener('keydown', onKey);
  }, [editor]);

  const chain = () => editor.chain().focus();

  return (
    <div className="toolbar" role="toolbar" aria-label="편집 도구">
      <div className="toolbar-row">
        {leading}
        {leading && <span className="sep" />}
        <button type="button" className="icon" disabled={!state.canUndo} title="되돌리기 (Ctrl+Z)" aria-label="되돌리기"
          onClick={() => chain().undo().run()}><Icon d="M9 14 4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11" /></button>
        <button type="button" className="icon" disabled={!state.canRedo} title="다시 하기 (Ctrl+Shift+Z)" aria-label="다시 하기"
          onClick={() => chain().redo().run()}><Icon d="m15 14 5-5-5-5M20 9H9.5a5.5 5.5 0 0 0 0 11H13" /></button>
        <span className="sep" />
        <button type="button" className={`text-btn${panel === 'find' ? ' on' : ''}`} title="찾기·바꾸기 (Ctrl+F)"
          onClick={() => setPanel(panel === 'find' ? null : 'find')}>찾기·바꾸기</button>
        {state.uncertain > 0 && (
          <>
            <span className="sep" />
            <button type="button" className="check-next" title="글자 인식(OCR)이 확실하지 않아 노랗게 표시한 곳으로 갑니다. 고치면 표시가 사라집니다"
              onClick={() => selectNextUncertain(editor)}>확인할 곳 {state.uncertain}개 <Icon d="m9 6 6 6-6 6" /></button>
            <button type="button" className="text-btn" disabled={!state.inUncertain} title="맞게 읽은 곳이면 표시를 지웁니다"
              onClick={() => clearUncertain(editor)}>확인함</button>
            <button type="button" className={`text-btn${panel === 'checks' ? ' on' : ''}`} title="확인할 곳 전체 목록"
              onClick={() => setPanel(panel === 'checks' ? null : 'checks')}>목록</button>
          </>
        )}
        <span className="sep" />
        <select value={zoom} aria-label="확대" onChange={(e) => onZoom(Number(e.target.value))}>
          {zooms.map((z) => <option key={z} value={z}>{Math.round(z * 100)}%</option>)}
        </select>
        <span className="toolbar-actions">{actions}</span>
      </div>

      <div className="toolbar-row">
        <select value={state.size ?? ''} aria-label="글자 크기" title="글자 크기 (pt). 기본은 역할 크기"
          onChange={(e) => (e.target.value
            ? chain().setMark('fontSize', { size: Number(e.target.value) }).run()
            : chain().unsetMark('fontSize').run())}>
          <option value="">크기 기본</option>
          {FONT_SIZES.map((s) => <option key={s} value={s}>{s}pt</option>)}
        </select>
        <span className="sep" />
        {MARKS.map((m) => (
          <button key={m.mark} type="button" className={`icon${state[m.mark] ? ' on' : ''}`} title={`${m.label} (${m.keys})`}
            aria-label={m.label} onClick={() => chain().toggleMark(m.mark).run()}>{m.icon}</button>
        ))}
        <ColorPicker editor={editor} color={state.color} />
        <span className="sep" />
        {ALIGNS.map((a) => (
          <button key={a.value} type="button" className={`icon${state.align === a.value ? ' on' : ''}`} title={a.label}
            aria-label={a.label}
            onClick={() => chain().updateAttributes('paragraph', { align: state.align === a.value ? null : a.value }).run()}>
            <AlignIcon align={a.value} />
          </button>
        ))}
        <span className="sep" />
        <button type="button" className="icon" title="들여쓰기 줄이기" aria-label="들여쓰기 줄이기"
          onClick={() => changeIndent(editor, -1)}><Icon d="M21 5H11M21 10H11M21 15H11M21 20H3M7 8l-4 4 4 4" /></button>
        <button type="button" className="icon" title="들여쓰기 늘리기 (10pt)" aria-label="들여쓰기 늘리기"
          onClick={() => changeIndent(editor, 1)}><Icon d="M21 5H11M21 10H11M21 15H11M21 20H3M3 8l4 4-4 4" /></button>
        <select value={state.firstLine ?? ''} aria-label="첫 줄"
          onChange={(e) => chain().updateAttributes('paragraph', { firstLine: e.target.value === '' ? null : Number(e.target.value) }).run()}>
          {FIRST_LINE.map((f) => <option key={f.label} value={f.value ?? ''}>{f.label}</option>)}
        </select>
        <span className="sep" />
        <button type="button" className="text-btn" title="고른 곳의 글자·문단 모양을 모두 지웁니다 (역할은 그대로)"
          onClick={() => clearFormatting(editor)}>서식 지우기</button>
      </div>

      {panel === 'find' && <FindPanel editor={editor} onClose={() => setPanel(null)} />}
      {panel === 'checks' && <CheckList editor={editor} onClose={() => setPanel(null)} />}
    </div>
  );
}

/**
 * 글자 색: 누르면 색 판이 열린다. 자주 쓰는 색, 기본(검정으로 되돌림), 다른 색(브라우저 색 고르기).
 * 판의 단추는 누를 때 에디터 선택이 풀리지 않게 mousedown 기본 동작을 막는다
 */
function ColorPicker({ editor, color }: { editor: Editor; color: string | null }) {
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
      <button type="button" className={`color-button${open ? ' on' : ''}`} title="글자 색" aria-label="글자 색"
        aria-expanded={open} onMouseDown={(e) => e.preventDefault()} onClick={() => setOpen(!open)}>
        <span className="color-letter" style={{ borderBottomColor: color ?? '#000000' }}>가</span>
        <span className="caret" aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="color-palette" role="dialog" aria-label="글자 색 고르기">
          <div className="swatches">
            {COLORS.map((c) => (
              <button key={c.value} type="button" className={`swatch${color === c.value ? ' on' : ''}`} title={c.label}
                aria-label={c.label} style={{ background: c.value }}
                onMouseDown={(e) => e.preventDefault()} onClick={() => apply(c.value)} />
            ))}
          </div>
          <button type="button" className="text-btn" onMouseDown={(e) => e.preventDefault()} onClick={() => apply(null)}>
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

/** 확인할 곳 전체 목록. 누르면 그 자리로 간다. 고치면 목록에서 저절로 빠진다 */
function CheckList({ editor, onClose }: { editor: Editor; onClose: () => void }) {
  const items = useEditorState({ editor, selector: ({ editor }) => uncertainList(editor.state) });
  return (
    <div className="toolbar-panel checks" role="dialog" aria-label="확인할 곳 목록">
      <div className="checks-head">
        <strong>확인할 곳 {items.length}개</strong>
        <span className="hint">글자 인식(OCR)이 확실하지 않은 곳입니다. 고치거나 "확인함"을 누르면 목록에서 빠집니다.</span>
        <button type="button" className="text-btn" disabled={items.length === 0}
          title="표시를 모두 지웁니다 (Ctrl+Z로 되돌릴 수 있습니다)" onClick={() => clearAllUncertain(editor)}>모두 확인함</button>
        <button type="button" className="text-btn" onClick={onClose}>닫기</button>
      </div>
      <ol className="checks-list">
        {items.map((item) => (
          <li key={item.from}>
            <button type="button" onClick={() => selectRange(editor, item)}>
              <span className="checks-where">{item.question > 0 ? `${item.question}번` : '정답'}</span>
              <span className="checks-text">
                {item.before && '…'}{item.before}<mark>{item.text}</mark>{item.after}{item.after && '…'}
              </span>
            </button>
          </li>
        ))}
      </ol>
    </div>
  );
}

/** 찾기·바꾸기 줄. 열려 있는 동안 일치하는 곳을 문서에 표시한다 */
function FindPanel({ editor, onClose }: { editor: Editor; onClose: () => void }) {
  const [query, setQuery] = useState('');
  const [replacement, setReplacement] = useState('');
  const [message, setMessage] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const count = query ? findMatches(editor.state, query).length : 0;

  useEffect(() => {
    input.current?.focus();
    return () => setSearch(editor, '');
  }, [editor]);
  useEffect(() => setSearch(editor, query), [editor, query]);

  const next = () => setMessage(count === 0 ? '찾는 말이 없습니다.' : `${findNext(editor, query)} / ${count}`);
  return (
    <div className="toolbar-panel find" role="search">
      <input ref={input} value={query} placeholder="찾을 말" aria-label="찾을 말"
        onChange={(e) => { setQuery(e.target.value); setMessage(''); }}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); next(); } if (e.key === 'Escape') onClose(); }} />
      <button type="button" className="text-btn" disabled={!query} onClick={next}>다음 찾기</button>
      <input value={replacement} placeholder="바꿀 말" aria-label="바꿀 말"
        onChange={(e) => setReplacement(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Escape') onClose(); }} />
      <button type="button" className="text-btn" disabled={!query}
        onClick={() => { const n = replaceNext(editor, query, replacement); setMessage(n ? `${n} / ${findMatches(editor.state, query).length}` : '더 없습니다.'); }}>바꾸기</button>
      <button type="button" className="text-btn" disabled={!query}
        onClick={() => setMessage(`${replaceAll(editor, query, replacement)}곳을 바꿨습니다.`)}>모두 바꾸기</button>
      <span className="find-count">{message || (query ? `${count}곳` : '')}</span>
      <button type="button" className="text-btn" onClick={onClose}>닫기</button>
    </div>
  );
}

/** 선으로 그린 아이콘 (24 칸 좌표, Lucide 모양을 따름). 글자 화살표(↶ ⇤)는 글꼴마다 모양·크기가 달라 SVG로 그린다 */
function Icon({ d }: { d: string }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

function AlignIcon({ align }: { align: Align }) {
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
