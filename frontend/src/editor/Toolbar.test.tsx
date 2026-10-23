// @vitest-environment jsdom
// 도구 막대의 지금 동작을 고정하는 특성 테스트 (리팩토링 안전망).
// testing-library를 더하지 않고 react-dom + act로 그린다. 에디터는 화면에 붙이지 않는다 (formatting.test와 같음)
import { Editor, type JSONContent } from '@tiptap/react';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { examExtensions } from './extensions';
import Toolbar from './Toolbar';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const plain: JSONContent = {
  type: 'doc',
  content: [{ type: 'paragraph', attrs: { role: 'body' }, content: [{ type: 'text', text: 'fake news and fake views' }] }],
};
const withUncertain: JSONContent = {
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
let root: Root;
let box: HTMLDivElement;
const onZoom = vi.fn();

function render(content: JSONContent) {
  editor = new Editor({ extensions: examExtensions, content });
  box = document.createElement('div');
  document.body.append(box);
  root = createRoot(box);
  act(() => root.render(<Toolbar editor={editor} zoom={1} zooms={[0.8, 1]} onZoom={onZoom} />));
}

afterEach(() => {
  act(() => root.unmount());
  box.remove();
  editor.destroy();
  onZoom.mockClear();
});

const byLabel = <T extends HTMLElement = HTMLElement>(label: string) =>
  box.querySelector<T>(`[aria-label="${label}"]`);
const byText = (text: string) =>
  [...box.querySelectorAll('button')].find((b) => b.textContent?.trim() === text) ?? null;
const click = (el: HTMLElement | null) => act(() => el!.click());

/** React가 바뀐 값을 알아채도록 원래 setter로 값을 넣고 이벤트를 보낸다 */
function change(el: HTMLInputElement | HTMLSelectElement | null, value: string) {
  const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  act(() => {
    Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(el, value);
    el!.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}
const key = (el: HTMLElement | null, init: KeyboardEventInit) =>
  act(() => { el!.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, ...init })); });
const selectAll = () => act(() => { editor.commands.setTextSelection({ from: 1, to: 5 }); });

describe('도구 막대: 글자 모양', () => {
  it('굵게·기울임·밑줄·취소선은 고른 곳에 넣고 단추를 켠다', () => {
    render(plain);
    selectAll();
    for (const [label, mark] of [['굵게', 'bold'], ['기울임', 'italic'], ['밑줄', 'underline'], ['취소선', 'strike']]) {
      expect(byLabel(label)!.className).toBe('icon');
      click(byLabel(label));
      expect(editor.isActive(mark)).toBe(true);
      expect(byLabel(label)!.className).toBe('icon on');
    }
  });

  it('글자 크기를 고르면 넣고, 기본을 고르면 지운다', () => {
    render(plain);
    selectAll();
    change(byLabel<HTMLSelectElement>('글자 크기'), '12');
    expect(editor.getAttributes('fontSize').size).toBe(12);
    expect(byLabel<HTMLSelectElement>('글자 크기')!.value).toBe('12');
    change(byLabel<HTMLSelectElement>('글자 크기'), '');
    expect(editor.isActive('fontSize')).toBe(false);
  });

  it('글자 색: 판에서 고르면 넣고 판을 닫는다. 기본은 색을 지운다', () => {
    render(plain);
    selectAll();
    click(byLabel('글자 색'));
    expect(byLabel('글자 색 고르기')).not.toBeNull();
    click(byLabel('빨강'));
    expect(editor.getAttributes('fontColor').color).toBe('#ff0000');
    expect(byLabel('글자 색 고르기')).toBeNull();

    click(byLabel('글자 색'));
    expect(byLabel('빨강')!.className).toBe('swatch on');
    click(byText('기본 (검정)'));
    expect(editor.isActive('fontColor')).toBe(false);
  });
});

describe('도구 막대: 문단 모양', () => {
  it('정렬 단추는 누르면 켜고, 한 번 더 누르면 끈다', () => {
    render(plain);
    click(byLabel('가운데 정렬'));
    expect(editor.getAttributes('paragraph').align).toBe('center');
    expect(byLabel('가운데 정렬')!.className).toBe('icon on');
    click(byLabel('가운데 정렬'));
    expect(editor.getAttributes('paragraph').align).toBeNull();
  });

  it('첫 줄을 고르면 넣고, 기본을 고르면 지운다', () => {
    render(plain);
    change(byLabel<HTMLSelectElement>('첫 줄'), '-10');
    expect(editor.getAttributes('paragraph').firstLine).toBe(-10);
    change(byLabel<HTMLSelectElement>('첫 줄'), '');
    expect(editor.getAttributes('paragraph').firstLine).toBeNull();
  });

  it('들여쓰기를 늘리고 줄인다', () => {
    render(plain);
    click(byLabel('들여쓰기 늘리기'));
    expect(editor.getAttributes('paragraph').indent).toBe(10);
    click(byLabel('들여쓰기 줄이기'));
    expect(editor.getAttributes('paragraph').indent).toBeNull();
  });
});

describe('도구 막대: 첫 줄', () => {
  it('되돌리기는 고친 것이 있을 때만 쓸 수 있고, 누르면 되돌린다', () => {
    render(plain);
    expect(byLabel<HTMLButtonElement>('되돌리기')!.disabled).toBe(true);
    selectAll();
    click(byLabel('굵게'));
    expect(byLabel<HTMLButtonElement>('되돌리기')!.disabled).toBe(false);
    click(byLabel('되돌리기'));
    expect(editor.isActive('bold')).toBe(false);
    expect(byLabel<HTMLButtonElement>('다시 하기')!.disabled).toBe(false);
  });

  it('확대를 고르면 숫자로 알린다', () => {
    render(plain);
    change(byLabel<HTMLSelectElement>('확대'), '0.8');
    expect(onZoom).toHaveBeenCalledWith(0.8);
  });
});

describe('도구 막대: 찾기·바꾸기', () => {
  it('찾을 말의 수를 보이고, 다음 찾기·바꾸기·모두 바꾸기 결과를 알린다', () => {
    render(plain);
    click(byText('찾기·바꾸기'));
    expect(byText('찾기·바꾸기')!.className).toBe('text-btn on');
    const count = () => box.querySelector('.find-count')!.textContent;

    change(byLabel<HTMLInputElement>('찾을 말'), 'fake');
    expect(count()).toBe('2곳');
    click(byText('다음 찾기'));
    expect(count()).toBe('1 / 2');

    change(byLabel<HTMLInputElement>('바꿀 말'), 'real');
    click(byText('바꾸기'));
    expect(editor.getText()).toBe('real news and fake views');
    expect(count()).toBe('1 / 1');

    click(byText('모두 바꾸기'));
    expect(editor.getText()).toBe('real news and real views');
    expect(count()).toBe('1곳을 바꿨습니다.');
  });

  it('찾는 말이 없으면 알리고, Enter는 다음 찾기, Escape는 닫기', () => {
    render(plain);
    click(byText('찾기·바꾸기'));
    change(byLabel<HTMLInputElement>('찾을 말'), 'none');
    key(byLabel('찾을 말'), { key: 'Enter' });
    expect(box.querySelector('.find-count')!.textContent).toBe('찾는 말이 없습니다.');
    key(byLabel('바꿀 말'), { key: 'Escape' });
    expect(box.querySelector('[role="search"]')).toBeNull();
  });

  it('에디터에서 Ctrl+F·⌘+H를 누르면 열리고, 단추를 다시 누르면 닫힌다', () => {
    render(plain);
    key(editor.view.dom, { key: 'f', ctrlKey: true });
    expect(box.querySelector('[role="search"]')).not.toBeNull();
    click(byText('닫기'));
    key(editor.view.dom, { key: 'h', metaKey: true });
    expect(box.querySelector('[role="search"]')).not.toBeNull();
    click(byText('찾기·바꾸기'));
    expect(box.querySelector('[role="search"]')).toBeNull();
  });
});

describe('도구 막대: 확인할 곳', () => {
  it('표시가 없으면 확인할 곳 단추를 보이지 않는다', () => {
    render(plain);
    expect(box.querySelector('.check-next')).toBeNull();
    expect(byText('목록')).toBeNull();
  });

  it('수를 보이고, 다음 곳으로 가서 확인함을 누르면 그 표시만 지운다', () => {
    render(withUncertain);
    expect(box.querySelector('.check-next')!.textContent).toBe('확인할 곳 2개 ');
    expect(byText('확인함')!.disabled).toBe(true);
    click(box.querySelector<HTMLElement>('.check-next'));
    expect(byText('확인함')!.disabled).toBe(false);
    click(byText('확인함'));
    expect(box.querySelector('.check-next')!.textContent).toBe('확인할 곳 1개 ');
  });

  it('목록은 곳마다 문항 번호와 앞뒤 글을 보이고, 모두 확인함을 누르면 단추가 사라진다', () => {
    render(withUncertain);
    click(byText('목록'));
    const items = [...box.querySelectorAll('.checks-list li')];
    expect(items).toHaveLength(2);
    expect(items[0].querySelector('mark')!.textContent).toBe('wa1ks');
    expect(box.querySelector('.checks-head strong')!.textContent).toBe('확인할 곳 2개');
    click(byText('모두 확인함'));
    expect(box.querySelector('.check-next')).toBeNull();
    expect(box.querySelectorAll('.checks-list li')).toHaveLength(0);
  });
});
