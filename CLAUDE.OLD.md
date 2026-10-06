# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 저장소 성격

Claude Code 심화 과정(4회차) 실습 저장소. 코드 · 데이터 · 로그는 전부 더미 에듀테크 도메인이다.
참가자가 수업 중에 직접 만들어야 하는 파일(`.claude/skills`, `.claude/agents`, `.claude/settings.json`, `hooks/*.mjs`, `.mcp.json`, `docs/` 문서, `characterization/tests/<모듈>.test.js`, `/api/items/search` 엔드포인트 등 — 전체 목록은 `README.md` "이 저장소에 넣지 않은 것")은 **요청받기 전에 미리 만들지 않는다.** 미리 있으면 실습이 성립하지 않는다.

도메인 용어: 문항 `item` · 단원 `unit` · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 식별자 `STU-<숫자>`.

## 명령

```bash
# DB · 레거시 기동 — down · build · logs 도 반드시 --profile 을 붙인다
docker compose --profile modern up -d            # 현행 API 용 MariaDB(3306)만
docker compose --profile php up -d               # 문항 은행 PHP 8081 (+MariaDB)
docker compose --profile thymeleaf up -d         # 과제 배포 8082
docker compose --profile mssql up -d             # 성적 집계 8083 + MS-SQL 1433
docker compose --profile php down && docker compose --profile php up -d   # 시드 상태로 초기화(영구 볼륨 없음)
bash scripts/check-env.sh                        # 환경 점검

# modern/api (Spring Boot 3.3 · Java 21)
cd modern/api && ./gradlew test                                          # H2 로 돈다 — DB 컨테이너 불필요
cd modern/api && ./gradlew test --tests com.example.item.UnitServiceTest # 단일 클래스
cd modern/api && ./gradlew test --tests 'com.example.item.ItemRepositoryTest.countsByStatus'  # 단일 메서드
cd modern/api && ./gradlew bootRun                                       # 8080, modern 프로필 DB 필요

# modern/web (React 18 · TS · Vite · Vitest)
cd modern/web && npm ci && npm run dev                                   # 5173
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/hooks/useUnits.test.ts              # 단일 파일

# characterization (동작 보존 스냅샷)
cd characterization && npm ci
cd characterization && npm run baseline -- item-bank                     # 모듈명: item-bank | assignment | grade (폴더명 아님)
cd characterization && npm test                                          # 대상: 레거시 기본 포트
cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test   # 대상: 새 API
```

의존성 설치는 `npm install` 이 아니라 `npm ci` — `package-lock.json` 이 바뀌면 안 된다.

## 큰 그림

레거시 3개 모듈(`legacy/`)을 현행 스택(`modern/`)으로 이관하는 시나리오다. `php` · `thymeleaf` · `modern` 프로필은 같은 MariaDB `itembank` 하나를 공유한다(시드: `db/mariadb/init`). 성적 집계는 MS-SQL `grades` + 저장 프로시저(`legacy/grade-mssql/sql`).

- **`legacy/`** 는 분석 · 이관의 근거 자료다. 허락 없이 수정하지 않는다. 이관한 규칙은 근거를 `파일:줄번호` 로 남긴다(예: 단원별 공개 문항 수 = `legacy/item-bank-php/units.php:15-19`).
- **`modern/api`** — 도메인 패키지(`item`, `assignment`) 안에서 Controller → Service → Repository → 엔티티. 컨트롤러는 서비스만 호출하고, 응답은 record DTO.
  - OSIV 가 꺼져 있다(`open-in-view: false`). 서비스(`@Transactional(readOnly = true)`) 안에서 DTO 로 바꿔 반환하고, 연관은 `@EntityGraph` 나 fetch 쿼리로 함께 로드한다.
  - 문항 노출 여부는 삭제 플래그가 아니라 `item.status` 코드(`ItemStatus`: `A` 공개 / `D` 삭제 / `R` 검수중)로 판단한다. 외부에는 `A` 만.
  - 예외 → HTTP 변환은 `common/GlobalExceptionHandler` 한 곳: `NotFoundException` 404, 검증 · `IllegalArgumentException` 400, `IllegalStateException` 409, 나머지 500. 컨트롤러 · 서비스에서 try/catch 로 상태 코드를 만들지 않는다.
  - Hikari 풀이 운영과 같게 작다(최대 5, 대기 3초, 누수 감지 10초). 트랜잭션을 오래 쥐거나 트랜잭션 안에서 외부 호출 · 긴 루프를 돌리면 곧 고갈된다. `application.yml` 의 DB · 풀 설정은 바꾸지 않는다.
  - CORS 는 `config/WebConfig` 에서 `http://localhost:5173` 의 `GET /api/**` 만 허용.
  - 테스트: 컨트롤러 `@WebMvcTest` + `@MockBean`, 서비스 Mockito, 쿼리 `@DataJpaTest`(H2 MariaDB 모드, `ItemFixtures` 로 엔티티 생성). 모두 `@ActiveProfiles("test")`, 메서드명은 camelCase + 한국어 `@DisplayName`.
- **`modern/web`** — `api/client.ts` 의 `getJson` 하나로만 호출 → `hooks/useApiQuery`(key 가 바뀌면 재조회, AbortSignal 로 취소, `QueryState` 판별 유니온) 위에 `useUnits` · `useUnitItems` · `useItem` → `components/`. API 주소는 `VITE_API_BASE`(기본 `http://localhost:8080`, 빈 문자열이면 Vite 프록시 경유). 테스트는 `src/test/mockFetch.ts` · `fixtures.ts` 로 fetch 를 목킹한다.
- **`characterization/`** — 레거시의 현재 응답(HTML · JSON)을 `lib/normalize.mjs` 로 `{status, rows, count, message}` 로 정규화해 스냅샷으로 비교한다. 기대값은 손으로 적지 않는다. 대상 주소는 `lib/target.mjs` 에만 두고 테스트는 `fetchNormalized(모듈, 레거시경로, 파라미터)` 를 쓴다. 이관으로 경로가 바뀌면 `PATH_ALIASES` 에 한 줄 추가(테스트 · 스냅샷은 그대로). 스냅샷을 깨는 변경은 먼저 사람에게 알린다.

## 그 밖의 폴더

- `templates/` — 팀별 `CLAUDE.*.md` 템플릿, 검증루프(`verification-loop.md`), 승인 · 시큐어코딩 체크리스트. 템플릿 일부(예: `CLAUDE.react.md` 의 컴포넌트 폴더 구조)는 실제 코드와 다르다 — 충돌하면 실제 코드를 따른다.
- `vendor-prs/` — 리뷰 실습용 외주 패치. `main` 에 적용하지 말고 `review/pr-N` 브랜치에서 `git apply --index` 한다. 세 건을 한 브랜치에 겹쳐 적용하지 않는다.
- `specs/` — 4회차 신규 개발 스펙과 시작 골격(`starters/java`, `starters/python`).
- `mcp-skeleton/` — 문항 은행 API 를 감싸는 TypeScript MCP 서버 골격(stdio, `npm run build && npm start`).
- `incident-logs/`, `pipeline-samples/`, `ci-ports/` — 장애 분석 · 배치 · IaC · CI 이식 실습 자료(읽기 대상).
- `scripts/hook-node.sh` — Hook 실행기. node 를 못 찾으면 명령을 막는다(fail-closed). `.claude/settings.json` 의 hook command 는 `bash "$CLAUDE_PROJECT_DIR"/scripts/hook-node.sh hooks/<이름>.mjs` 형태로 쓴다.

## DB 접근

실습 조회는 읽기 전용 계정만 쓴다: MariaDB `readonly` / `readonly-pass` (localhost:3306/itembank), MS-SQL `readonly` / `Readonly-pass1` (localhost:1433/grades). 쓰기 계정은 `docker-compose.yml` · `application.yml` 안에서만 쓴다. 스키마 · 시드 변경, `build.gradle` 의존성 추가는 먼저 묻는다.
