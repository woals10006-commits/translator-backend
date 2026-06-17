# 중국어 → 한국어 번역기

Word(.docx) 파일을 업로드하면 챕터 단위로 중국어를 한국어로 번역해 주는 웹앱입니다.

- **백엔드**: Spring Boot 3 + Apache POI(.docx) + Claude API (`claude-haiku-4-5`)
- **프론트엔드**: React + Vite (별도 저장소/폴더 `translator-frontend`)

번역 흐름: 파일 업로드 → 단락을 5개씩 묶어 병렬로 Claude에 번역 요청(구조화 출력) → 진행률 폴링 → 번역된 .docx 다운로드.

## 사전 준비

- **JDK 17**
- **Node.js 18+** (프론트엔드)
- **Anthropic API 키** — https://console.anthropic.com 에서 발급

## 1. API 키 설정 (필수)

키는 코드에 넣지 않고 **환경변수 `CLAUDE_API_KEY`** 로 주입합니다.

```bash
# macOS / Linux
export CLAUDE_API_KEY="sk-ant-..."

# Windows (PowerShell, 현재 세션만)
$env:CLAUDE_API_KEY="sk-ant-..."

# Windows (영구 설정 — 새 터미널부터 적용)
setx CLAUDE_API_KEY "sk-ant-..."
```

## 2. 백엔드 실행

```bash
# Windows
gradlew.bat bootRun
# macOS / Linux
./gradlew bootRun
```

→ http://localhost:8080 에서 실행됩니다. (포트는 `application.properties`의 `server.port`)

## 3. 프론트엔드 실행

```bash
cd ../translator-frontend
npm install
npm run dev
```

→ http://localhost:5173 접속 → .docx 업로드 → 번역할 화 수 입력 → 번역 시작.

## 비용 참고

`claude-haiku-4-5` 기준, 100화(약 1만 단락) 번역 시 대략 **$6 / 약 48분**. 작업 완료 시 백엔드 로그에 입력/출력 토큰과 비용(`[JOB] 비용: ...`)이 찍힙니다. 콘솔에서 **자동 충전 + 월 지출 한도**를 설정하면 끊김 없이 쓸 수 있습니다.

## 주의

- `application.properties`에 API 키를 다시 넣지 마세요. 환경변수만 사용합니다.
- `server.port`(8080)와 프론트엔드의 백엔드 주소가 일치해야 합니다.
