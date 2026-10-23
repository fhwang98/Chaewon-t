// @vitest-environment jsdom
import { Editor } from '@tiptap/core';
import { afterEach, describe, expect, it } from 'vitest';
import { examExtensions } from './extensions';
import { changeIndent, clearFormatting, findMatches, findNext, replaceAll } from './formatting';

const doc = {
  type: 'doc',
  content: [
    { type: 'paragraph', attrs: { role: 'body' }, content: [{ type: 'text', text: 'fake news and fake views' }] },
    { type: 'paragraph', attrs: { role: 'choice' }, content: [{ type: 'text', text: '① fake' }] },
  ],
};

let editor: Editor;
afterEach(() => editor?.destroy());

describe('찾기·바꾸기', () => {
  it('일치하는 곳을 차례로 고르고, 모두 바꾸면 수를 돌려준다', () => {
    editor = new Editor({ extensions: examExtensions, content: doc });
    expect(findMatches(editor.state, 'fake')).toHaveLength(3);

    editor.commands.setTextSelection(1);
    expect(findNext(editor, 'fake')).toBe(1);
    expect(findNext(editor, 'fake')).toBe(2);

    expect(replaceAll(editor, 'fake', 'real')).toBe(3);
    expect(editor.getText({ blockSeparator: '|' })).toBe('real news and real views|① real');
  });
});

describe('문단·글자 모양', () => {
  it('들여쓰기는 10pt씩, 0이면 값을 지운다', () => {
    editor = new Editor({ extensions: examExtensions, content: doc });
    editor.commands.setTextSelection(2);
    changeIndent(editor, 1);
    changeIndent(editor, 1);
    expect(editor.getJSON().content![0].attrs!.indent).toBe(20);
    changeIndent(editor, -1);
    changeIndent(editor, -1);
    expect(editor.getJSON().content![0].attrs!.indent).toBeNull();
  });

  it('서식 지우기는 글자·문단 모양을 지우고 역할은 남긴다', () => {
    editor = new Editor({ extensions: examExtensions, content: doc });
    editor.chain().setTextSelection({ from: 1, to: 5 }).toggleItalic().setMark('fontColor', { color: '#ff0000' })
      .updateAttributes('paragraph', { align: 'center' }).run();
    expect(editor.getJSON().content![0].content![0].marks).toHaveLength(2);

    clearFormatting(editor);

    const p = editor.getJSON().content![0];
    expect(p.content![0].marks).toBeUndefined();
    expect(p.attrs).toMatchObject({ role: 'body', align: null });
  });
});
