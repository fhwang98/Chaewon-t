import { useEditorState, type Editor } from '@tiptap/react';
import { useEffect, useState, type ReactNode } from 'react';
import { on } from './buttons';
import CheckList from './CheckList';
import ColorPicker from './ColorPicker';
import { clearUncertain, selectNextUncertain, uncertainRanges } from './extensions';
import FindPanel from './FindPanel';
import { changeIndent, clearFormatting, type Align } from './formatting';
import { AlignIcon, Icon } from './icons';

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
  // 빈 값('기본')은 직접 넣은 값을 지워 역할 서식을 따르게 한다
  const setFontSize = (value: string) => (value
    ? chain().setMark('fontSize', { size: Number(value) }).run()
    : chain().unsetMark('fontSize').run());
  const setFirstLine = (value: string) =>
    chain().updateAttributes('paragraph', { firstLine: value === '' ? null : Number(value) }).run();

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
        <button type="button" className={on('text-btn', panel === 'find')} title="찾기·바꾸기 (Ctrl+F)"
          onClick={() => setPanel(panel === 'find' ? null : 'find')}>찾기·바꾸기</button>
        {state.uncertain > 0 && (
          <>
            <span className="sep" />
            <button type="button" className="check-next" title="글자 인식(OCR)이 확실하지 않아 노랗게 표시한 곳으로 갑니다. 고치면 표시가 사라집니다"
              onClick={() => selectNextUncertain(editor)}>확인할 곳 {state.uncertain}개 <Icon d="m9 6 6 6-6 6" /></button>
            <button type="button" className="text-btn" disabled={!state.inUncertain} title="맞게 읽은 곳이면 표시를 지웁니다"
              onClick={() => clearUncertain(editor)}>확인함</button>
            <button type="button" className={on('text-btn', panel === 'checks')} title="확인할 곳 전체 목록"
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
          onChange={(e) => setFontSize(e.target.value)}>
          <option value="">크기 기본</option>
          {FONT_SIZES.map((s) => <option key={s} value={s}>{s}pt</option>)}
        </select>
        <span className="sep" />
        {MARKS.map((m) => (
          <button key={m.mark} type="button" className={on('icon', state[m.mark])} title={`${m.label} (${m.keys})`}
            aria-label={m.label} onClick={() => chain().toggleMark(m.mark).run()}>{m.icon}</button>
        ))}
        <ColorPicker editor={editor} color={state.color} />
        <span className="sep" />
        {ALIGNS.map((a) => (
          <button key={a.value} type="button" className={on('icon', state.align === a.value)} title={a.label}
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
          onChange={(e) => setFirstLine(e.target.value)}>
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
