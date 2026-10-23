import type { JSONContent } from '@tiptap/core';
import type { Answer, ExamDocument, Paragraph, Question, Span } from '../api/types';

/**
 * ExamDocument(서버 JSON) ↔ 에디터 문서(TipTap JSON) 변환.
 *
 * 에디터 문서는 역할(role)이 붙은 문단의 평평한 목록이다. HWPX에 들어가는 순서와 같다.
 *   question  문항 머리 (번호는 화면·한글 모두 자동). 출처는 끝에 " [1-2]"로 보여 준다
 *   body      지문·주어진 문장·[조건] 등 본문 문단
 *   choice    선택지 ①~⑤
 *   answer    정답·해설 "N 번 - ⑤   해설" (문서 끝, "< 정 답 >" 아래). 앞머리가 없는 정답 문단은 앞 해설에 이어 붙인다
 * 문항 머리가 아닌 빈 문단은 덩어리 경계(한글에서 상자 사이 빈 줄)다.
 */

export type Role = 'question' | 'body' | 'choice' | 'answer';

/** 정답 문단 앞머리 "12 번 - " */
const ANSWER_HEAD = /^\s*(\d+)\s*번\s*-\s*/;
/** 답과 해설 사이 (서버 ExamParser.toAnswer와 같은 규칙): 공백 2칸 이상, 아니면 동그라미 번호 답 뒤 */
const ANSWER_SPLIT = /\s{2,}/;
const CIRCLED_ANSWER = /^([①-⑤](?:\s*,\s*[①-⑤])*)\s+(.+)$/;

/** 질문 끝의 [출처]. 서버 ExamParser.SOURCE와 같은 규칙 */
const SOURCE = /\s*\[([^[\]]*)\]\s*$/;

export function toEditorJson(doc: ExamDocument): JSONContent {
  const content: JSONContent[] = [];
  for (const q of doc.questions) {
    // 문항 머리는 한글에서 통째로 굵게 들어가므로 굵게 표시는 빼고 밑줄만 둔다
    const prompt = toTextNodes(q.prompt.spans, false);
    if (q.source) {
      prompt.push({ type: 'text', text: ` [${q.source}]` });
    }
    content.push(paragraph('question', prompt, q.prompt));

    q.body.forEach((block, i) => {
      if (i > 0) content.push(paragraph('body', []));
      block.paragraphs.forEach((p) => content.push(paragraph('body', toTextNodes(p.spans, true), p)));
    });
    if (q.choices.length > 0) {
      if (q.body.length > 0) content.push(paragraph('body', []));
      q.choices.forEach((c) => content.push(paragraph('choice', toTextNodes(c.spans, true), c)));
    }
  }
  for (const a of doc.answers) {
    content.push(paragraph('answer', [{ type: 'text', text: answerText(a) }]));
  }
  if (content.length === 0) {
    content.push(paragraph('question', []));
  }
  return { type: 'doc', content };
}

export function fromEditorJson(json: JSONContent, headerTitle: string): ExamDocument {
  interface Draft {
    prompt: Span[];
    promptStyle: Omit<Paragraph, 'spans'>;
    body: Paragraph[][];
    choices: Paragraph[];
    newBlock: boolean;
  }
  const drafts: Draft[] = [];
  const start = (prompt: Span[], promptStyle: Omit<Paragraph, 'spans'> = {}) => {
    const d: Draft = { prompt, promptStyle, body: [], choices: [], newBlock: true };
    drafts.push(d);
    return d;
  };

  const answers: Answer[] = [];
  let current: Draft | null = null;
  for (const node of json.content ?? []) {
    if (node.type !== 'paragraph') continue;
    const role = (node.attrs?.role ?? 'body') as Role;
    const spans = toSpans(node.content ?? []);
    const empty = spans.every((s) => s.text.trim() === '');
    const style = paragraphStyle(node.attrs);

    if (role === 'answer') {
      if (!empty) addAnswer(answers, spans.map((s) => s.text).join(''));
      continue;
    }
    if (role === 'question') {
      current = start(spans, style);
      continue;
    }
    if (empty) {
      if (current) current.newBlock = true;
      continue;
    }
    // 첫 문항 머리 앞에 쓴 글자도 잃지 않도록 빈 질문의 문항으로 받는다 (서버가 경고로 알려 준다)
    current ??= start([]);
    if (role === 'choice') {
      current.choices.push({ spans, ...style });
      continue;
    }
    if (current.newBlock || current.body.length === 0) {
      current.body.push([]);
      current.newBlock = false;
    }
    current.body[current.body.length - 1].push({ spans, ...style });
  }

  const questions: Question[] = drafts.map((d, i) => {
    const { prompt, source } = splitSource(d.prompt);
    return {
      number: i + 1,
      prompt: { spans: prompt, ...d.promptStyle },
      source,
      body: d.body.map((paragraphs) => ({ paragraphs })),
      choices: d.choices,
    };
  });
  return { headerTitle, questions, answers };
}

/** 한글에 들어가는 모양 그대로: "3 번 - ⑤   해설" */
export function answerText(a: Answer): string {
  const head = `${a.number} 번 - ${a.answer}`;
  return a.explanation ? `${head}   ${a.explanation}` : head;
}

function addAnswer(answers: Answer[], text: string) {
  const head = ANSWER_HEAD.exec(text);
  const last = answers[answers.length - 1];
  if (!head) {
    // 앞머리 없는 줄: 앞 정답의 해설에 잇는다. 첫 줄이면 번호를 비워 두지 않고 1번으로 받는다
    if (last) {
      last.explanation = last.explanation ? `${last.explanation} ${text.trim()}` : text.trim();
    } else {
      answers.push({ number: 1, answer: '', explanation: text.trim() });
    }
    return;
  }
  answers.push(splitAnswer(Number(head[1]), text.slice(head[0].length).trim()));
}

function splitAnswer(number: number, rest: string): Answer {
  const split = ANSWER_SPLIT.exec(rest);
  if (split) {
    return { number, answer: rest.slice(0, split.index), explanation: rest.slice(split.index + split[0].length) };
  }
  const circled = CIRCLED_ANSWER.exec(rest);
  if (circled) {
    return { number, answer: circled[1], explanation: circled[2] };
  }
  return { number, answer: rest, explanation: '' };
}

/** 문단 모양 중 정해진 것만 (없으면 빈 객체: 서버 JSON에 값을 붙이지 않는다) */
function paragraphStyle(attrs: Record<string, unknown> | undefined): Omit<Paragraph, 'spans'> {
  const style: Omit<Paragraph, 'spans'> = {};
  if (attrs?.align) style.align = attrs.align as Paragraph['align'];
  if (attrs?.indent) style.indent = attrs.indent as number;
  if (attrs?.firstLine != null) style.firstLine = attrs.firstLine as number;
  return style;
}

function paragraph(role: Role, content: JSONContent[], p?: Paragraph): JSONContent {
  const attrs: Record<string, unknown> = { role };
  if (p?.align) attrs.align = p.align;
  if (p?.indent) attrs.indent = p.indent;
  if (p?.firstLine != null) attrs.firstLine = p.firstLine;
  return content.length > 0
    ? { type: 'paragraph', attrs, content }
    : { type: 'paragraph', attrs };
}

function toTextNodes(spans: Span[], keepBold: boolean): JSONContent[] {
  return spans
    .filter((s) => s.text.length > 0)
    .map((s) => {
      const marks: JSONContent['marks'] = [];
      if (keepBold && s.bold) marks.push({ type: 'bold' });
      if (s.italic) marks.push({ type: 'italic' });
      if (s.underline) marks.push({ type: 'underline' });
      if (s.strike) marks.push({ type: 'strike' });
      if (s.script) marks.push({ type: 'script', attrs: { type: s.script } });
      if (s.size) marks.push({ type: 'fontSize', attrs: { size: s.size } });
      if (s.color) marks.push({ type: 'fontColor', attrs: { color: s.color } });
      if (s.uncertain) marks.push({ type: 'uncertain' });
      return marks.length > 0 ? { type: 'text', text: s.text, marks } : { type: 'text', text: s.text };
    });
}

/** 글자 노드 → 서식이 같은 이웃끼리 합친 조각 */
function toSpans(nodes: JSONContent[]): Span[] {
  const spans: Span[] = [];
  for (const node of nodes) {
    if (node.type !== 'text' || !node.text) continue;
    const marks = node.marks ?? [];
    const span: Span = {
      text: node.text,
      bold: marks.some((m) => m.type === 'bold'),
      underline: marks.some((m) => m.type === 'underline'),
    };
    // 기본값이 아닌 것만 붙인다 (서버는 없으면 기본값으로 본다)
    const mark = (type: string) => marks.find((m) => m.type === type);
    if (mark('italic')) span.italic = true;
    if (mark('strike')) span.strike = true;
    if (mark('script')) span.script = mark('script')!.attrs!.type as 'sup' | 'sub';
    if (mark('fontSize')?.attrs?.size) span.size = mark('fontSize')!.attrs!.size as number;
    if (mark('fontColor')?.attrs?.color) span.color = mark('fontColor')!.attrs!.color as string;
    if (mark('uncertain')) span.uncertain = true;
    const last = spans[spans.length - 1];
    if (last && sameFormat(last, span)) {
      last.text += span.text;
    } else {
      spans.push(span);
    }
  }
  return spans;
}

const FORMAT_KEYS = ['bold', 'underline', 'uncertain', 'italic', 'strike', 'script', 'size', 'color'] as const;

function sameFormat(a: Span, b: Span): boolean {
  return FORMAT_KEYS.every((k) => (a[k] ?? null) === (b[k] ?? null) || (!a[k] && !b[k]));
}

/** 질문 끝 " [출처]"를 떼어 낸다. 문항 머리는 통째로 굵게 들어가므로 bold는 false로 둔다 */
function splitSource(spans: Span[]): { prompt: Span[]; source: string } {
  const plain = spans.map((s) => s.text).join('');
  const m = SOURCE.exec(plain);
  const end = m ? m.index : plain.length;
  const prompt: Span[] = [];
  let offset = 0;
  for (const s of spans) {
    const take = Math.max(0, Math.min(s.text.length, end - offset));
    if (take > 0) {
      prompt.push({ ...s, text: s.text.slice(0, take), bold: false });
    }
    offset += s.text.length;
  }
  return { prompt, source: m ? m[1].trim() : '' };
}
