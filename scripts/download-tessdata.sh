#!/usr/bin/env sh
# Tesseract 언어 데이터(한국어·영어)를 data/tessdata/ 에 받는다.
#   scripts/download-tessdata.sh [폴더]                 기본: 둘 다 tessdata_best (가장 정확, 느림)
#   FAST_ENG=1 scripts/download-tessdata.sh [폴더]      영어만 tessdata_fast (CPU 시간 27% 덜 씀, 영어 단어 오류 조금 늘어남)
#
# 고른 근거 (2026-10-21, sample.pdf 8쪽을 OCR해 원문과 비교, 단 이미지 4장 CPU 시간):
#   한·영 best        22.8초  영어 단어 96.4%  한글 단어 94.3%   ← 기본
#   한 best + 영 fast  16.6초  영어 95.9%       한글 96.7%
#   한·영 fast        12.3초  영어 95.7%       한글 92.3%
#   apt/brew 기본 패키지는 한글 문항 머리를 자주 틀렸다 ("다음 글의 FAME 가장 적절한 AL? 136)")
# 버전 4.1.0 태그로 고정한다 (Tesseract 4·5에서 쓰는 LSTM 모델)
set -eu
dir="${1:-data/tessdata}"
best="https://github.com/tesseract-ocr/tessdata_best/raw/4.1.0"
fast="https://github.com/tesseract-ocr/tessdata_fast/raw/4.1.0"
mkdir -p "$dir"
for lang in kor eng; do
  base="$best"
  if [ "$lang" = eng ] && [ "${FAST_ENG:-0}" = 1 ]; then
    base="$fast"
    rm -f "$dir/eng.traineddata" # 이미 받은 best를 fast로 바꾼다
  fi
  if [ ! -s "$dir/$lang.traineddata" ]; then
    echo "받는 중: $lang.traineddata ($base)"
    curl -fsSL -o "$dir/$lang.traineddata.part" "$base/$lang.traineddata"
    mv "$dir/$lang.traineddata.part" "$dir/$lang.traineddata"
  fi
done
ls -l "$dir"
