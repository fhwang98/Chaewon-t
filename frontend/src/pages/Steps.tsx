const STEPS = ['PDF 올리기', '읽기', '고치고 내려받기'];

/** 지금 몇 번째 단계인지 (업로드·읽기 화면 위). current는 0부터 */
export default function Steps({ current }: { current: number }) {
  return (
    <ol className="steps" aria-label="진행 단계">
      {STEPS.map((label, i) => (
        <li key={label} className={i < current ? 'done' : i === current ? 'current' : undefined}
          aria-current={i === current ? 'step' : undefined}>
          <span className="step-no" aria-hidden="true">{i + 1}</span>
          {label}
        </li>
      ))}
    </ol>
  );
}
