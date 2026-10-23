import { describe, expect, it } from 'vitest';
import type { ExamDocument } from '../api/types';
import { fromEditorJson, toEditorJson } from './convert';

const span = (text: string, underline = false, bold = false) => ({ text, underline, bold });

const doc: ExamDocument = {
  headerTitle: '26년 가나고1 (2학기 중간)',
  questions: [
    {
      number: 1,
      prompt: { spans: [span('다음 글의 내용과 일치하지 '), span('않는', true), span(' 것은?')] },
      source: '1-2',
      body: [{ paragraphs: [{ spans: [span('(A)', false, true), span(' Fake news is '), span('deliberate', true)] },
        { spans: [span('It is common.')] }] }],
      choices: [{ spans: [span('① one')] }, { spans: [span('② two')] }],
    },
    {
      number: 2,
      prompt: { spans: [span('주어진 문장이 들어가기에 적절한 곳은?')] },
      source: '',
      body: [{ paragraphs: [{ spans: [span('Given sentence.')] }] }, { paragraphs: [{ spans: [span('Passage ( ① ).')] }] }],
      choices: [],
    },
  ],
  answers: [{ number: 1, answer: '③', explanation: '해설' }],
};

describe('ExamDocument ↔ 에디터 문서', () => {
  it('역할 문단 목록으로 펼친다', () => {
    const json = toEditorJson(doc);
    const roles = json.content!.map((p) => `${p.attrs!.role}${p.content ? '' : '(빈)'}`);
    expect(roles).toEqual([
      'question', 'body', 'body', 'body(빈)', 'choice', 'choice',
      'question', 'body', 'body(빈)', 'body',
      'answer',
    ]);
    expect(json.content!.at(-1)!.content).toEqual([{ type: 'text', text: '1 번 - ③   해설' }]);
    // 출처는 질문 끝에 붙여 보여 주고, 문항 머리의 굵게 표시는 뺀다
    expect(json.content![0].content!.at(-1)).toEqual({ type: 'text', text: ' [1-2]' });
    expect(json.content![1].content![0]).toEqual({ type: 'text', text: '(A)', marks: [{ type: 'bold' }] });
  });

  it('펼쳤다가 다시 모으면 같은 문서가 된다', () => {
    const back = fromEditorJson(toEditorJson(doc), doc.headerTitle);
    expect(back).toEqual(doc);
  });

  it('번호는 순서대로 다시 매기고, 첫 문항 앞 글자도 잃지 않는다', () => {
    const back = fromEditorJson({
      type: 'doc',
      content: [
        { type: 'paragraph', attrs: { role: 'body' }, content: [{ type: 'text', text: 'stray' }] },
        { type: 'paragraph', attrs: { role: 'question' }, content: [{ type: 'text', text: 'Q [9-9]' }] },
      ],
    }, '');
    expect(back.questions.map((q) => [q.number, q.prompt.spans.map((s) => s.text).join(''), q.source]))
      .toEqual([[1, '', ''], [2, 'Q', '9-9']]);
    expect(back.questions[0].body[0].paragraphs[0].spans[0].text).toBe('stray');
  });

  it('확인 필요 표시는 uncertain 표시로 오가고, 없으면 값을 붙이지 않는다', () => {
    const marked: ExamDocument = {
      headerTitle: '',
      questions: [{
        number: 1, prompt: { spans: [span('Q')] }, source: '',
        body: [{ paragraphs: [{ spans: [span('He '), { ...span('wa1ks'), uncertain: true }, span(' home.')] }] }],
        choices: [],
      }],
      answers: [],
    };
    const json = toEditorJson(marked);
    expect(json.content![1].content![1]).toEqual({ type: 'text', text: 'wa1ks', marks: [{ type: 'uncertain' }] });
    expect(fromEditorJson(json, '')).toEqual(marked);
  });

  it('글자 모양(기울임·취소선·첨자·크기·색)과 문단 모양(정렬·들여쓰기·첫 줄)이 오간다', () => {
    const styled: ExamDocument = {
      headerTitle: '',
      questions: [{
        number: 1, prompt: { spans: [span('Q')], align: 'center' }, source: '',
        body: [{ paragraphs: [{
          spans: [
            { ...span('a'), italic: true },
            { ...span('b'), strike: true, color: '#ff0000' },
            { ...span('2'), script: 'sup', size: 12 },
          ],
          indent: 10, firstLine: -10,
        }] }],
        choices: [{ spans: [span('① one')], align: 'right' }],
      }],
      answers: [],
    };
    const json = toEditorJson(styled);
    expect(json.content![1].attrs).toEqual({ role: 'body', indent: 10, firstLine: -10 });
    expect(json.content![1].content![2].marks).toEqual([
      { type: 'script', attrs: { type: 'sup' } }, { type: 'fontSize', attrs: { size: 12 } }]);
    expect(fromEditorJson(json, '')).toEqual(styled);
  });

  it('서버가 보낸 null 값은 기본값으로 본다', () => {
    const fromServer: ExamDocument = {
      headerTitle: '',
      questions: [{
        number: 1, prompt: { spans: [{ ...span('Q'), italic: null, size: null, color: null }], align: null }, source: '',
        body: [], choices: [],
      }],
      answers: [],
    };
    expect(fromEditorJson(toEditorJson(fromServer), '').questions[0].prompt).toEqual({ spans: [span('Q')] });
  });

  it('정답 문단을 번호·답·해설로 나누고, 앞머리 없는 줄은 앞 해설에 잇는다', () => {
    const answer = (text: string) => ({ type: 'paragraph', attrs: { role: 'answer' }, content: [{ type: 'text', text }] });
    const back = fromEditorJson({
      type: 'doc',
      content: [
        answer('1 번 - ④   해설 첫 줄'),
        answer('이어지는 해설'),
        answer('2 번 - ② 공백 한 칸 해설'),
        answer('3 번 - (A) for mothers (B) to prevent'),
        { type: 'paragraph', attrs: { role: 'answer' } },
      ],
    }, '');
    expect(back.answers).toEqual([
      { number: 1, answer: '④', explanation: '해설 첫 줄 이어지는 해설' },
      { number: 2, answer: '②', explanation: '공백 한 칸 해설' },
      { number: 3, answer: '(A) for mothers (B) to prevent', explanation: '' },
    ]);
    expect(back.questions).toEqual([]);
  });
});
