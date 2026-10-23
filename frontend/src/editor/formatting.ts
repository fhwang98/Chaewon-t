import { Extension, Mark, type Editor } from '@tiptap/core';
import { Plugin, PluginKey, type EditorState } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';

/**
 * 에디터의 글자·문단 모양과 편집 도구. 여기서 넣은 모양은 저장할 때 서버 모델(Span, Paragraph)로 가고,
 * HWPX를 만들 때 템플릿 서식을 복제한 서식으로 들어간다 (서버 render/HeaderStyles).
 *
 * 크기는 모두 pt이고 화면에는 확대 배율 --z를 곱해 보인다 (exam.css와 같은 방식).
 */

/** 위·아래 첨자. 둘 중 하나만 */
export const Script = Mark.create({
  name: 'script',
  excludes: 'script',

  addAttributes() {
    return { type: { default: 'sup', renderHTML: () => ({}) } };
  },

  parseHTML() {
    return [
      { tag: 'sup', getAttrs: () => ({ type: 'sup' }) },
      { tag: 'sub', getAttrs: () => ({ type: 'sub' }) },
    ];
  },

  renderHTML({ mark }) {
    return [mark.attrs.type === 'sub' ? 'sub' : 'sup', 0];
  },
});

/** 글자 크기 (pt) */
export const FontSize = Mark.create({
  name: 'fontSize',

  addAttributes() {
    return {
      size: {
        default: null,
        parseHTML: (el) => Number(el.getAttribute('data-size')) || null,
        renderHTML: (attrs) => (attrs.size
          ? { 'data-size': attrs.size, style: `font-size: calc(${attrs.size}pt * var(--z))` }
          : {}),
      },
    };
  },

  parseHTML() {
    return [{ tag: 'span[data-size]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return ['span', HTMLAttributes, 0];
  },
});

/** 글자 색 (#RRGGBB) */
export const FontColor = Mark.create({
  name: 'fontColor',

  addAttributes() {
    return {
      color: {
        default: null,
        parseHTML: (el) => el.getAttribute('data-color'),
        renderHTML: (attrs) => (attrs.color ? { 'data-color': attrs.color, style: `color: ${attrs.color}` } : {}),
      },
    };
  },

  parseHTML() {
    return [{ tag: 'span[data-color]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return ['span', HTMLAttributes, 0];
  },
});

export type Align = 'left' | 'center' | 'right' | 'justify';

/**
 * 문단 모양: 정렬, 들여쓰기(역할 기본 왼쪽 여백에 더하는 pt), 첫 줄(pt, 음수면 내어쓰기).
 * 값이 없으면(null) 역할의 기본 모양(exam.css, 한글 템플릿)을 따른다
 */
export const ParagraphStyle = Extension.create({
  name: 'paragraphStyle',

  addGlobalAttributes() {
    return [
      {
        types: ['paragraph'],
        attributes: {
          align: {
            default: null,
            parseHTML: (el) => el.getAttribute('data-align'),
            renderHTML: (attrs) => (attrs.align ? { 'data-align': attrs.align, style: `text-align: ${attrs.align}` } : {}),
          },
          indent: {
            default: null,
            parseHTML: (el) => Number(el.getAttribute('data-indent')) || null,
            renderHTML: (attrs) => (attrs.indent
              ? { 'data-indent': attrs.indent, style: `margin-left: calc(${attrs.indent}pt * var(--z))` }
              : {}),
          },
          firstLine: {
            default: null,
            parseHTML: (el) => {
              const v = el.getAttribute('data-first-line');
              return v === null ? null : Number(v);
            },
            renderHTML: (attrs) => (attrs.firstLine != null
              ? { 'data-first-line': attrs.firstLine, style: `text-indent: calc(${attrs.firstLine}pt * var(--z))` }
              : {}),
          },
        },
      },
    ];
  },
});

const INDENT_STEP = 10;
const INDENT_MAX = 100;

/** 고른 문단(또는 커서가 있는 문단)의 들여쓰기를 한 단계(10pt) 늘리거나 줄인다 */
export function changeIndent(editor: Editor, direction: 1 | -1): boolean {
  return editor.chain().focus().command(({ tr, state }) => {
    const { from, to } = state.selection;
    state.doc.nodesBetween(from, to, (node, pos) => {
      if (node.type.name !== 'paragraph') return true;
      const next = Math.min(INDENT_MAX, Math.max(0, (node.attrs.indent ?? 0) + direction * INDENT_STEP));
      tr.setNodeMarkup(pos, undefined, { ...node.attrs, indent: next === 0 ? null : next });
      return false;
    });
    return true;
  }).run();
}

/** 글자·문단 모양을 모두 지운다 (역할은 그대로) */
export function clearFormatting(editor: Editor): boolean {
  return editor.chain().focus().unsetAllMarks()
    .updateAttributes('paragraph', { align: null, indent: null, firstLine: null }).run();
}

/** 찾기: 찾는 말과 일치하는 곳마다 표시한다. 대소문자를 구분한다 */
const searchKey = new PluginKey<{ query: string; decorations: DecorationSet }>('search');

export const Search = Extension.create({
  name: 'search',

  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: searchKey,
        state: {
          init: () => ({ query: '', decorations: DecorationSet.empty }),
          apply: (tr, value, _old, state) => {
            const query = (tr.getMeta(searchKey) as string | undefined) ?? value.query;
            if (!tr.docChanged && query === value.query) return value;
            const decorations = DecorationSet.create(state.doc,
              findMatches(state, query).map((m) => Decoration.inline(m.from, m.to, { class: 'search-match' })));
            return { query, decorations };
          },
        },
        props: {
          decorations: (state) => searchKey.getState(state)?.decorations,
        },
      }),
    ];
  },
});

/** 문단 안 글자에서 일치하는 곳 (문단은 글자만 담고 있어 위치 = 문단 시작 + 1 + 글자 위치) */
export function findMatches(state: EditorState, query: string): { from: number; to: number }[] {
  const matches: { from: number; to: number }[] = [];
  if (!query) return matches;
  state.doc.descendants((node, pos) => {
    if (!node.isTextblock) return true;
    const text = node.textContent;
    for (let i = text.indexOf(query); i >= 0; i = text.indexOf(query, i + query.length)) {
      matches.push({ from: pos + 1 + i, to: pos + 1 + i + query.length });
    }
    return false;
  });
  return matches;
}

/** 찾는 말을 정해 일치하는 곳을 표시한다 (빈 말이면 표시를 지운다) */
export function setSearch(editor: Editor, query: string) {
  editor.view.dispatch(editor.state.tr.setMeta(searchKey, query));
}

/** 커서 다음의 일치하는 곳을 고른다 (끝까지 가면 처음부터). 몇 번째인지 돌려준다 (없으면 0) */
export function findNext(editor: Editor, query: string): number {
  const matches = findMatches(editor.state, query);
  if (matches.length === 0) return 0;
  const after = editor.state.selection.to;
  const found = matches.findIndex((m) => m.from >= after);
  const index = found >= 0 ? found : 0;
  editor.chain().focus().setTextSelection(matches[index]).scrollIntoView().run();
  return index + 1;
}

/** 고른 곳이 찾는 말이면 바꾸고 다음 곳으로 간다 */
export function replaceNext(editor: Editor, query: string, replacement: string): number {
  const { from, to } = editor.state.selection;
  if (query && editor.state.doc.textBetween(from, to) === query) {
    editor.chain().focus().insertContentAt({ from, to }, replacement).run();
  }
  return findNext(editor, query);
}

/** 모두 바꾸기. 바꾼 수를 돌려준다. 뒤에서부터 바꿔 앞쪽 위치가 밀리지 않게 한다 */
export function replaceAll(editor: Editor, query: string, replacement: string): number {
  const matches = findMatches(editor.state, query);
  if (matches.length === 0) return 0;
  editor.chain().focus().command(({ tr }) => {
    for (const m of [...matches].reverse()) {
      tr.insertText(replacement, m.from, m.to);
    }
    return true;
  }).run();
  return matches.length;
}
