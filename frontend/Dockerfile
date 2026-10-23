# chaewon-t 화면 이미지 (멀티 스테이지)
#   1) build : Node 24로 React 화면 빌드 (dist/)
#   2) 실행  : nginx가 dist/를 정적 파일로 내준다. Node는 실행 이미지에 들어가지 않는다
# 빌드 위치는 frontend/ (docker-compose.yml의 build.context)

FROM node:24-bookworm-slim AS build
WORKDIR /app
# Node 24 이미지의 npm이 11.6보다 낮으면 package.json engines(engine-strict)에 걸린다. 맞는 npm을 먼저 깐다
RUN npm install -g npm@11
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
