// @vitest-environment jsdom
import { Editor } from '@tiptap/core';
import { afterEach, describe, expect, it } from 'vitest';
import {
  clearAllUncertain, clearUncertain, examExtensions, selectNextUncertain, uncertainList, uncertainRanges,
} from './extensions';

const doc = {
  type: 'doc',
  content: [{
    type: 'paragraph', attrs: { role: 'body' },
    content: [
      { type: 'text', text: 'He ' },
      { type: 'text', text: 'wa1ks', marks: [{ type: 'uncertain' }] },
      { type: 'text', text: ' to ' },
      { type: 'text', text: 'schoo1', marks: [{ type: 'uncertain' }] },
    ],
  }],
};

let editor: Editor;
afterEach(() => editor?.destroy());

describe('확인 필요 표시', () => {
  it('표시된 단어를 고치면 그 단어의 표시만 사라진다', () => {
    editor = new Editor({ extensions: examExtensions, content: doc });
    expect(uncertainRanges(editor.state)).toHaveLength(2);

    // "wa1ks"의 "1"(문서 위치 6: 문단 시작이 0, H가 1)을 "l"로 바꾼다
    editor.chain().setTextSelection({ from: 6, to: 7 }).insertContent('l').run();

    expect(editor.getText()).toBe('He walks to schoo1');
    expect(uncertainRanges(editor.state)).toHaveLength(1);
  });

  it('목록은 문항 번호와 앞뒤 글자를 보여 주고, 모두 확인함은 표시를 다 지운다', () => {
    editor = new Editor({
      extensions: examExtensions,
      content: {
        type: 'doc',
        content: [
          { type: 'paragraph', attrs: { role: 'question' }, content: [{ type: 'text', text: 'Q one' }] },
          ...doc.content,
        ],
      },
    });
    const list = uncertainList(editor.state);
    expect(list.map((i) => [i.question, i.before, i.text, i.after])).toEqual([
      [1, 'He ', 'wa1ks', ' to schoo1'],
      [1, 'He wa1ks to ', 'schoo1', ''],
    ]);

    clearAllUncertain(editor);
    expect(uncertainRanges(editor.state)).toHaveLength(0);
  });

  it('다음 확인할 곳을 고르고, 확인함으로 표시를 지운다', () => {
    editor = new Editor({ extensions: examExtensions, content: doc });
    editor.commands.setTextSelection(1);

    selectNextUncertain(editor);
    const { from, to } = editor.state.selection;
    expect(editor.state.doc.textBetween(from, to)).toBe('wa1ks');

    clearUncertain(editor);
    expect(uncertainRanges(editor.state)).toHaveLength(1);
    expect(editor.getText()).toBe('He wa1ks to schoo1'); // 글자는 그대로
  });
});
