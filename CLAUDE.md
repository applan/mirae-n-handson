# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- 답변과 문서는 한국어로 쓴다. 코드 식별자 · 명령 · 로그 원문은 그대로 둔다.
- Claude Code 심화 과정 실습 저장소. 레거시(`legacy/`)를 현행(`modern/api` · `modern/web`)으로 옮기는 더미 에듀테크 도메인이다. Compose 프로필 · 포트 · DB 계정은 `README.md` 를 본다.
- 도메인 용어: 문항 `item` · 단원 `unit` · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 식별자 `STU-<숫자>`.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — Spring Boot 3.3 · Java 21 · Gradle wrapper
cd modern/api && ./gradlew test                                            # H2(MariaDB 모드). DB 컨테이너 없이 통과
cd modern/api && ./gradlew test --tests com.example.item.UnitServiceTest   # 클래스 하나
cd modern/api && ./gradlew test --tests 'com.example.item.ItemRepositoryTest.countsByStatus'   # 메서드 하나
cd modern/api && ./gradlew build                                           # 테스트 포함 전체 빌드
cd modern/api && ./gradlew bootRun     # 8080. 먼저 docker compose --profile modern up -d 로 MariaDB 를 띄운다

# modern/web — React 18 · TypeScript · Vite · Vitest
cd modern/web && npm ci                                          # 설치. npm install 은 package-lock.json 을 바꾸므로 쓰지 않는다
cd modern/web && npm run dev                                     # 5173. API 는 localhost:8080
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/hooks/useUnits.test.ts       # 파일 하나
cd modern/web && npm run build
```

- `modern/api` 를 바꿨으면 `./gradlew test`, `modern/web` 을 바꿨으면 `lint` · `typecheck` · `test` 를 실행하고 답변에 명령과 통과 · 실패 수를 적는다. 실행하지 않았으면 "실행하지 않음"이라고 적는다.
- 위 명령 중 하나라도 실패한 상태에서는 답변에 "완료"라고 쓰지 않는다.

## 2. 코딩 컨벤션

### modern/api 계층
- 패키지는 도메인 단위(`com.example.item`, `com.example.assignment`)다. 호출 방향은 Controller → Service → Repository → 엔티티다.
- **`*Controller` 클래스는 `*Repository` 타입을 필드 · 생성자 인자 · 메서드 인자로 받지 않고, SQL · JPQL 문자열을 포함하지 않는다.**
- `*Service` 는 다른 도메인 패키지의 `*Repository` 를 주입받지 않는다. 다른 도메인 데이터는 그 도메인의 `*Service` 로 읽는다.
- 컨트롤러 메서드의 반환 타입과 요청 본문 타입은 `record` DTO 다. `@Entity` 클래스를 반환 타입에 쓰지 않는다.
- 조회 메서드에는 `@Transactional(readOnly = true)`(클래스 또는 메서드), 쓰기 메서드에는 `@Transactional` 을 붙인다.
- OSIV 가 꺼져 있다. 엔티티 → DTO 변환은 서비스 메서드 안에서 끝내고, DTO 로 쓸 연관 엔티티는 `@EntityGraph` 나 fetch join 으로 함께 읽는다.
- 쿼리에 들어가는 값은 `:이름` 파라미터 바인딩으로 넘긴다. 문자열 연결(`+`, `format`)로 쿼리를 만들지 않는다.
- 문항 상태는 `ItemStatus` 상수로 쓴다. main 코드에 `"A"` · `"D"` · `"R"` 리터럴을 새로 쓰지 않는다.
- `*Service` 에서 현재 시각은 주입받은 `Clock`(`common/ClockConfig`)으로 얻는다. 인자 없는 `LocalDateTime.now()` 를 쓰지 않는다.

### 예외
- 비어 있는 `catch` 블록이 없어야 한다. 잡은 예외는 로그를 남기고 다시 던지거나, 원인(`cause`)을 담은 다른 예외로 던진다.
- 예외 → HTTP 상태 변환은 `common/GlobalExceptionHandler` 에서만 한다. 컨트롤러 · 서비스에는 오류 응답을 만드는 try-catch 가 없어야 한다.
- 매핑: `NotFoundException` 404 · 검증 실패와 `IllegalArgumentException` 400 · `IllegalStateException` 409 · 그 밖의 예외 500. 없는 리소스는 서비스가 `NotFoundException` 을 던진다.
- 500 응답 본문에 예외 메시지 · 스택 트레이스를 넣지 않는다(현재 고정 문구 "서버 내부 오류").

### 로깅
- **로그는 SLF4J(`org.slf4j.Logger`, `LoggerFactory.getLogger(<클래스>.class)`)로만 남긴다. `System.out` · `System.err` · `printStackTrace()` 가 코드에 없어야 한다.**
- 로그 인자에 학생 식별자(`STU-…`) · 이메일 · 토큰 · 비밀번호 값을 넣지 않는다.
- 레벨: 정상 흐름은 `DEBUG` · `INFO`, 404 · 400 은 `INFO`, 409 는 `WARN`, 500 은 `ERROR`(`GlobalExceptionHandler` 와 같게).

### 테스트 (modern/api)
- 새 public 서비스 메서드와 새 엔드포인트마다 테스트 메서드가 1개 이상 있어야 한다.
- 컨트롤러는 `@WebMvcTest` + `@MockBean` 서비스, 서비스는 `@ExtendWith(MockitoExtension.class)`, 리포지토리 쿼리는 `@DataJpaTest`(H2)로 검증한다. 테스트 클래스에는 `@ActiveProfiles("test")` 를 붙인다.
- 테스트 메서드 이름은 확인하는 동작을 담은 camelCase(예: `listUnitsMapsInOrder`)이고, `@DisplayName` 은 한국어로 쓴다.
- 테스트용 엔티티는 `ItemFixtures` 로 만든다. 테스트는 `application-test.yml` 의 H2 만 쓰고 MariaDB 에 붙지 않는다.

### modern/web
- `fetch` 호출은 `src/api/client.ts` 의 `getJson` 한 곳에만 있다. 새 API 호출도 `getJson` 을 쓴다.
- 조회 훅은 `src/hooks/useApiQuery` 위에 만들고, `src/components/` 는 `src/api/` 에서 `import type` 만 한다(`getJson` · `api/items` 함수는 훅을 거쳐 쓴다).
- 테스트 파일은 대상 옆의 `*.test.ts(x)` 이고, fetch 는 `src/test/mockFetch.ts`, 데이터는 `src/test/fixtures.ts` 를 쓴다.

### 이름 · 형식 (Java)
- 클래스 `PascalCase`, 메서드 · 필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`. 약어는 첫 글자만 대문자(`ItemDto`).
- 새로 쓰는 메서드 본문의 0 · 1 이외 숫자 리터럴은 이름 붙인 `static final` 상수로 둔다(애너테이션 속성값은 제외).
- 들여쓰기 4칸(탭 없음), 한 줄 120자 이내, 와일드카드 import(`.*`) 없음.

### 보안 검토
- **코드를 추가 · 수정한 답변에는 행정안전부 「소프트웨어 개발보안 가이드」(2021) 구현단계 보안약점 기준 검토 결과를 표(항목 · `파일:줄번호` · 판정)로 적는다. 해당하는 항목이 없으면 "해당 없음"이라고 적는다.**
- 이 검토는 사람이 읽는 수동 검토이며 Sparrow 정적 분석 결과를 대신하지 않는다고 답변에 함께 적는다.

## 3. 금지 사항

- `legacy/` 는 분석 · 이관 대상이다. 허락 없이 수정하지 않는다.
- **의존성 추가 · 버전 변경(`modern/api/build.gradle` 의 `dependencies`, `modern/web/package.json`)은 먼저 사람에게 묻는다.** 패키지 이름 · 버전 · 이유 · 대안을 적고, 승인 답변을 받은 뒤 바꾼다.
- **DB 스키마 변경은 먼저 묻는다.** 테이블 · 컬럼 · 인덱스, `@Table` · `@Column` · `@JoinColumn` 매핑, `db/` 아래 SQL, 시드 데이터가 모두 해당한다.
- `application.yml` 의 DB 접속 정보, `hikari` 설정, `open-in-view` · `ddl-auto` 값을 바꾸지 않는다.
- 비밀번호 · 토큰 · 키를 코드 · 설정 · 테스트에 리터럴로 새로 넣지 않는다. 이미 있는 실습 더미 값(`app-pass`, README 의 `readonly` 계정)만 예외다.
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령 · 설정을 만들지 않는다.
- `@Transactional` 메서드 안에서 외부 HTTP 호출과 고정 횟수 연산 루프(해시 스트레칭 등)를 실행하지 않는다. `ReportService.buildClassReport` 는 이 규칙을 어기는 장애 실습용 코드이므로 요청 없이 고치지 않는다.
- 변경 파일은 요청 기능의 main · test 파일로 한정한다. 그 밖의 파일(포맷팅 · import 정리 포함)을 바꿨으면 답변에 파일과 이유를 적는다.
- `characterization/` 스냅샷을 갱신하거나 스냅샷 비교를 실패시키는 변경은 먼저 사람에게 알린다.
- 참가자가 수업 중에 만드는 파일(`.claude/`, `hooks/`, `.mcp.json`, `docs/` 문서, `/api/items/search` 등 — README "이 저장소에 넣지 않은 것")은 요청 없이 만들지 않는다.
- `vendor-prs/*.patch` 는 `main` 에 적용하지 않는다. `review/pr-N` 브랜치에서만 적용한다.

## 4. 아키텍처 안내

```
modern/api/src/main/java/com/example/
├── item/        문항 · 단원 · 태그 — Item/Unit Controller · Service · Repository, ItemStatus
├── assignment/  과제 배포 · 재배포 · 학급 리포트 — Distribution*, Report*
├── common/      GlobalExceptionHandler, ErrorResponse, NotFoundException, ClockConfig, RootController
└── config/      WebConfig (CORS)
modern/api/src/main/resources/application.yml       기본 프로필 = 로컬 MariaDB(itembank), 커넥션 풀 설정
modern/api/src/test/resources/application-test.yml  테스트 프로필 = H2(MariaDB 모드, create-drop)
modern/web/src/   api/(getJson · 타입) → hooks/(useApiQuery · useUnits · useUnitItems · useItem) → components/
```

- 엔드포인트: `GET /`, `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`. 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.
- 새 조회 API 는 같은 도메인 패키지에 컨트롤러 · 서비스 · 리포지토리 메서드와 계층별 테스트를 만든다.
- 문항 노출은 `item.status` 코드로 판단한다(`A` 공개 / `D` 삭제 / `R` 검수중). 외부 조회 API 는 `A` 만 반환한다. `GET /api/items/{id}` 는 관리용이라 상태와 무관하다.
- 레거시 규칙을 옮길 때는 근거를 `파일:줄번호` 로 답변과 Javadoc 에 적는다(예: `legacy/item-bank-php/units.php:15-19`).
- 커넥션 풀은 운영과 같다(최대 5 · 대기 3초 · 누수 감지 10초). 커넥션을 오래 쥐는 코드는 곧바로 풀 고갈로 이어진다(`incident-logs/a-connection-pool`).
- CORS 는 `WebConfig` 가 `http://localhost:5173` 의 `GET /api/**` 만 허용한다. web 의 API 주소는 `VITE_API_BASE`(기본 `http://localhost:8080`, 빈 문자열이면 Vite 프록시 경유)다.
- `php` · `thymeleaf` · `modern` Compose 프로필은 MariaDB `itembank` 하나를 공유한다(시드 `db/mariadb/init`, 영구 볼륨 없음).
- 그 밖의 폴더: `characterization/`(레거시 응답 스냅샷 비교, 대상 주소 · `PATH_ALIASES` 는 `lib/target.mjs`), `templates/`(팀 템플릿 · 체크리스트), `vendor-prs/`(외주 패치), `specs/`(신규 스펙), `mcp-skeleton/`(MCP 서버 골격), `incident-logs/` · `pipeline-samples/` · `ci-ports/`(읽기 자료).

## 5. 완료 기준

- **이관 · 리팩토링 작업은 `characterization/` 의 `npm test` 가 전부 통과하기 전에는 "완료"라고 보고하지 않는다.**
- 테스트가 실패하면 실패한 케이스 이름과 스냅샷 차이를 그대로 보고한다. "거의 됐다" 식으로 요약하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일(`__snapshots__/`)을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. 답변에 "의심 동작" 목록으로 따로 보고한다.
