import type { Editor } from '@tiptap/react';
import { useEffect, useRef, useState } from 'react';
import { findMatches, findNext, replaceAll, replaceNext, setSearch } from './formatting';

/** 찾기·바꾸기 줄. 열려 있는 동안 일치하는 곳을 문서에 표시한다 */
export default function FindPanel({ editor, onClose }: { editor: Editor; onClose: () => void }) {
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
  // 바꾸면 남은 수가 줄어 count(그릴 때 센 값) 대신 다시 센다
  const replace = () => {
    const n = replaceNext(editor, query, replacement);
    setMessage(n ? `${n} / ${findMatches(editor.state, query).length}` : '더 없습니다.');
  };
  const closeOnEscape = (e: { key: string }) => {
    if (e.key === 'Escape') onClose();
  };
  return (
    <div className="toolbar-panel find" role="search">
      <input ref={input} value={query} placeholder="찾을 말" aria-label="찾을 말"
        onChange={(e) => { setQuery(e.target.value); setMessage(''); }}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); next(); } closeOnEscape(e); }} />
      <button type="button" className="text-btn" disabled={!query} onClick={next}>다음 찾기</button>
      <input value={replacement} placeholder="바꿀 말" aria-label="바꿀 말"
        onChange={(e) => setReplacement(e.target.value)} onKeyDown={closeOnEscape} />
      <button type="button" className="text-btn" disabled={!query} onClick={replace}>바꾸기</button>
      <button type="button" className="text-btn" disabled={!query}
        onClick={() => setMessage(`${replaceAll(editor, query, replacement)}곳을 바꿨습니다.`)}>모두 바꾸기</button>
      <span className="find-count">{message || (query ? `${count}곳` : '')}</span>
      <button type="button" className="text-btn" onClick={onClose}>닫기</button>
    </div>
  );
}
