import { combineTransactionSteps, Extension, getChangedRanges, getMarkRange, Mark, type Editor } from '@tiptap/core';
import type { Node as PmNode } from '@tiptap/pm/model';
import { Plugin, PluginKey, type EditorState } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import StarterKit from '@tiptap/starter-kit';
import { FontColor, FontSize, ParagraphStyle, Script, Search } from './formatting';

/** 한글 템플릿의 정답 제목 (mapping.json styles.answerHeading). 화면에만 보이고 문서 내용은 아니다 */
const ANSWER_HEADING = '< 정 답 >';

/**
 * 모든 문단에 역할(role) 속성을 붙인다. 화면에서는 data-role로 나타나고 exam.css가 모양을 정한다.
 * Enter를 누르면 새 문단은 같은 역할을 이어받는다(지문 → 지문, 선택지 → 선택지).
 * 단 문항 머리 끝에서 Enter를 누르면 새 문단은 지문이 된다.
 */
const ParagraphRole = Extension.create({
  name: 'paragraphRole',

  addGlobalAttributes() {
    return [
      {
        types: ['paragraph'],
        attributes: {
          role: {
            default: 'body',
            parseHTML: (element) => element.getAttribute('data-role') ?? 'body',
            renderHTML: (attributes) => ({ 'data-role': attributes.role }),
          },
        },
      },
    ];
  },

  addKeyboardShortcuts() {
    return {
      Enter: ({ editor }) => {
        const { $from, empty } = editor.state.selection;
        const atEndOfQuestion = empty
          && $from.parent.attrs.role === 'question'
          && $from.parentOffset === $from.parent.content.size;
        if (!atEndOfQuestion) return false;
        return editor.chain().splitBlock().updateAttributes('paragraph', { role: 'body' }).run();
      },
    };
  },
});

/**
 * 지문 상자 표시용 클래스를 문단에 붙인다 (문서 내용은 바꾸지 않는 화면 장식).
 *   blank      빈 문단 = 덩어리 경계
 *   box        글자가 있는 지문 문단 (좌우 테두리)
 *   box-first  상자의 첫 문단 (위 테두리) / box-last 마지막 문단 (아래 테두리)
 * CSS만으로는 "빈 문단"과 "다음 문단이 지문이 아님"을 고를 수 없어 이렇게 한다.
 * 첫 정답 문단 앞에는 "< 정 답 >" 제목을 끼워 보여 준다 (한글 결과와 같은 모양).
 */
const BoxDecorations = Extension.create({
  name: 'boxDecorations',

  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: new PluginKey('boxDecorations'),
        props: {
          decorations: (state) => {
            const nodes: { node: PmNode; pos: number }[] = [];
            state.doc.forEach((node, pos) => nodes.push({ node, pos }));
            const isBox = (i: number) => {
              const n = nodes[i]?.node;
              return !!n && n.attrs.role === 'body' && n.textContent.trim() !== '';
            };
            const decorations = nodes.map(({ node, pos }, i) => {
              const classes: string[] = [];
              if (node.textContent.trim() === '' && node.attrs.role !== 'question') classes.push('blank');
              if (isBox(i)) {
                classes.push('box');
                if (!isBox(i - 1)) classes.push('box-first');
                if (!isBox(i + 1)) classes.push('box-last');
              }
              return Decoration.node(pos, pos + node.nodeSize, { class: classes.join(' ') });
            });
            const firstAnswer = nodes.find(({ node }) => node.attrs.role === 'answer');
            if (firstAnswer) {
              decorations.push(Decoration.widget(firstAnswer.pos, () => {
                const heading = document.createElement('div');
                heading.className = 'answer-heading';
                heading.contentEditable = 'false';
                heading.textContent = ANSWER_HEADING;
                return heading;
              }, { side: -1, key: 'answer-heading' }));
            }
            return DecorationSet.create(state.doc, decorations);
          },
        },
      }),
    ];
  },
});

/**
 * "확인 필요" 표시: OCR이 자신 없게 읽은 글자 (서버 Span.uncertain). 노란 바탕으로 보인다.
 * 그 단어를 고치면(표시된 글자나 바로 옆을 바꾸면) 표시가 저절로 사라진다. 맞게 읽었으면 도구 막대의 "확인함"으로 지운다.
 * inclusive: false — 표시된 단어 끝에 이어 쓴 글자에는 표시가 번지지 않는다
 */
const Uncertain = Mark.create({
  name: 'uncertain',
  inclusive: false,

  parseHTML() {
    return [{ tag: 'span[data-uncertain]' }];
  },

  renderHTML() {
    return ['span', { 'data-uncertain': '', class: 'uncertain', title: '글자 인식(OCR)이 확실하지 않은 곳입니다' }, 0];
  },

  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: new PluginKey('uncertainClearOnEdit'),
        appendTransaction: (transactions, oldState, newState) => {
          if (!transactions.some((tr) => tr.docChanged)) return null;
          const type = newState.schema.marks.uncertain;
          const tr = newState.tr;
          const size = newState.doc.content.size;
          for (const { newRange } of getChangedRanges(combineTransactionSteps(oldState.doc, [...transactions]))) {
            // 바뀐 자리와 맞닿은 글자까지 본다 (표시된 단어 끝에 글자를 더한 경우)
            const from = Math.max(0, newRange.from - 1);
            const to = Math.min(size, newRange.to + 1);
            newState.doc.nodesBetween(from, to, (node, pos) => {
              if (node.isText && type.isInSet(node.marks)) tr.removeMark(pos, pos + node.nodeSize, type);
            });
          }
          // 표시 지우기 단계는 글자 위치를 바꾸지 않아 다시 불려도 바뀐 구간이 없다 (되풀이되지 않는다)
          return tr.steps.length > 0 ? tr : null;
        },
      }),
    ];
  },
});

/** 확인 필요 표시가 붙은 구간들 (이어진 글자는 한 구간) */
export function uncertainRanges(state: EditorState): { from: number; to: number }[] {
  const type = state.schema.marks.uncertain;
  const ranges: { from: number; to: number }[] = [];
  state.doc.descendants((node, pos) => {
    if (!node.isText || !type.isInSet(node.marks)) return;
    const last = ranges[ranges.length - 1];
    if (last && last.to === pos) {
      last.to = pos + node.nodeSize;
    } else {
      ranges.push({ from: pos, to: pos + node.nodeSize });
    }
  });
  return ranges;
}

/** 확인할 곳 목록의 한 줄: 어느 문항(정답이면 0)인지와 앞뒤 글자 */
export interface UncertainItem {
  from: number;
  to: number;
  question: number;
  before: string;
  text: string;
  after: string;
}

const CONTEXT = 12;

/** 확인 필요 구간 전체를 문항 번호·앞뒤 글자와 함께 */
export function uncertainList(state: EditorState): UncertainItem[] {
  const ranges = uncertainRanges(state);
  const questionStarts: number[] = [];
  state.doc.forEach((node, pos) => {
    if (node.attrs.role === 'question') questionStarts.push(pos);
  });
  return ranges.map((r) => {
    const $from = state.doc.resolve(r.from);
    const paragraph = $from.parent;
    const start = $from.start();
    const text = paragraph.textContent;
    const offset = r.from - start;
    const end = r.to - start;
    let question = 0;
    while (question < questionStarts.length && questionStarts[question] < r.from) question++;
    return {
      from: r.from,
      to: r.to,
      question: paragraph.attrs.role === 'answer' ? 0 : question,
      before: text.slice(Math.max(0, offset - CONTEXT), offset),
      text: text.slice(offset, end),
      after: text.slice(end, end + CONTEXT),
    };
  });
}

/** 커서 뒤의 다음 확인 필요 구간을 골라 보여 준다 (끝까지 가면 처음부터) */
export function selectNextUncertain(editor: Editor): boolean {
  const ranges = uncertainRanges(editor.state);
  if (ranges.length === 0) return false;
  const after = editor.state.selection.to;
  const next = ranges.find((r) => r.from >= after) ?? ranges[0];
  return editor.chain().focus().setTextSelection(next).scrollIntoView().run();
}

/** 구간 하나를 골라 화면에 보여 준다 */
export function selectRange(editor: Editor, range: { from: number; to: number }): boolean {
  return editor.chain().focus().setTextSelection(range).scrollIntoView().run();
}

/** 문서의 확인 필요 표시를 모두 지운다 (되돌리기로 되살릴 수 있다) */
export function clearAllUncertain(editor: Editor): boolean {
  return editor.chain().focus().command(({ tr, state }) => {
    tr.removeMark(0, state.doc.content.size, state.schema.marks.uncertain);
    return true;
  }).run();
}

/** 고른 곳(또는 커서가 있는 단어)의 확인 필요 표시를 지운다 */
export function clearUncertain(editor: Editor): boolean {
  const { state } = editor;
  const type = state.schema.marks.uncertain;
  const { from, to, empty, $from } = state.selection;
  const range = empty ? getMarkRange($from, type) : { from, to };
  if (!range) return false;
  return editor.chain().focus().command(({ tr }) => {
    tr.removeMark(range.from, range.to, type);
    return true;
  }).run();
}

/** 시험지에 필요한 것: 문단, 글자 모양(굵게·기울임·밑줄·취소선·첨자·크기·색), 문단 모양, 찾기, 되돌리기 */
export const examExtensions = [
  StarterKit.configure({
    blockquote: false,
    bulletList: false,
    code: false,
    codeBlock: false,
    hardBreak: false,
    heading: false,
    horizontalRule: false,
    link: false,
    listItem: false,
    listKeymap: false,
    orderedList: false,
    trailingNode: false,
  }),
  Script,
  FontSize,
  FontColor,
  ParagraphStyle,
  Search,
  ParagraphRole,
  BoxDecorations,
  Uncertain,
];
