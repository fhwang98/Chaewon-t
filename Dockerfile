# chaewon-t 백엔드 이미지 (멀티 스테이지). 화면은 frontend/Dockerfile, 앞단 nginx는 nginx/nginx.conf (docker-compose.yml)
#   1) build : JDK 21로 jar 빌드. 화면은 따로 배포하므로 Gradle의 npm 단계는 건너뛴다(-PskipFrontend)
#   2) 실행  : JRE 21 + Tesseract(OCR) + 한국어·영어 언어 데이터(tessdata_best)
#
# 템플릿(template.hwpx)은 학원 로고가 있어 이미지에 넣지 않는다. docker-compose.yml이 data/templates를 붙인다

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -PskipFrontend

FROM eclipse-temurin:21-jre
# tesseract-ocr: OCR 엔진 (apt 기본 언어 데이터는 쓰지 않고 아래에서 tessdata_best를 받는다)
# fontconfig: PDF를 그림으로 그릴 때(PDFBox → Java AWT) 글꼴 설정이 필요하다
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr fontconfig curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY scripts/download-tessdata.sh scripts/
RUN sh scripts/download-tessdata.sh /app/data/tessdata
COPY data/templates/exam/v1/mapping.json data/templates/exam/v1/mapping.json
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
# 작업 폴더(업로드·결과)는 컨테이너가 다시 만들어져도 1시간 보관이 이어지도록 볼륨으로 둔다 (docker-compose.yml)
VOLUME /app/data/tmp
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
