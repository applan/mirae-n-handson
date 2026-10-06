# 이관 검증 리포트 1 — 문항 검색 (`/search.php` → `/api/items/search`)

- 검증 대상: `git diff upstream/main...HEAD` (브랜치 `day1`, 커밋 3개, 27개 파일, +4064 / -1)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준: `templates/approval-checklist.md` v0. 코드는 수정하지 않았다.

판정: 반려

## 기준별 판정

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 미충족 | `cd modern/api && ./gradlew cleanTest test` → 63개 실행, 통과 63 · 실패 0 · 건너뜀 0. `cd characterization && npm test`(레거시 8081 대상) → 3개 파일, 30개 통과 · 실패 0. **새 API 대상**(`TARGET_BASE_URL=http://localhost:8080 npm test`)은 8080 서버가 꺼져 있어 **실행하지 않음**. 이관 완료 기준(`CLAUDE.md` §5)과 "동작을 바꾸지 않음"의 직접 증거가 비어 있다. |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 쿼리 값은 모두 `:이름` 바인딩(`ItemRepository.java:34-55`), 비밀값 리터럴 · 데이터 삭제 코드 없음. |
| 요청 범위 이탈 없음 | 미충족 | 이슈 #2. 이관 코드(커밋 `21d38a0`, 16개 파일)는 요청으로 설명된다. 나머지 두 커밋의 파일은 설명되지 않는다. |
| 컨벤션 준수 | 충족 | `System.out` · `printStackTrace` · `LocalDateTime.now()` · 상태 리터럴 · 빈 `catch` · `@Disabled` · 와일드카드 import 없음(grep). 120자 초과 줄 없음. 신규 테스트 클래스 전부 `@ActiveProfiles("test")`. 컨트롤러는 Repository · SQL 을 쓰지 않고 `record` 를 반환한다. 위반 이슈 없음. |

## 요청 문장 대비 변경 파일

| 분류 | 파일 | 요청으로 설명되는가 |
|---|---|---|
| `modern/api` main 8개 | `ItemSearchController` · `ItemSearchService` · `ItemSearchResponse` · `ItemSearchRow` · `ItemSearchCondition` · `LegacySearchParams` · `PhpStrings` 신규, `ItemRepository` 수정 | 예 |
| `modern/api` test 7개 | `ItemSearch{Condition,Controller,Repository,Service}Test` · `LegacySearchParamsTest` · `PhpStringsTest` 신규, `ItemFixtures` 수정 | 예 |
| `characterization` 2개 | `tests/item-bank.test.js`, `__snapshots__/item-bank.test.js.snap` (커밋 `9a819fd`) | 예 — 동작 보존 기준선. 이후 커밋에서 수정되지 않았다. |
| `docs/item-bank/*` 4개 | ARCHITECTURE · BUSINESS-RULES · CROSS-CHECK · ERD | 예(근거 문서) |
| `docs/assignment/*` 3개 | ARCHITECTURE · BUSINESS-RULES · ERD | **아니오** — 문항 검색과 무관한 다른 모듈 |
| `CLAUDE.md` | 신규 | **아니오** |
| `.claude/skills/{convention-check,document-module}/SKILL.md` | 신규 | **아니오** |

- 의존성(`build.gradle`), DB 스키마 · 시드(`db/`), `application.yml`, `legacy/` 는 변경 없음(`git diff --stat` 확인).
- 비교 기준이 `upstream/main` 이라 1회차 준비 커밋(`75ce757`)과 베이스라인 커밋(`9a819fd`)까지 한 diff 에 들어왔다. 이관 커밋만 보려면 `75ce757...HEAD` 로 다시 검증하는 편이 맞을 수 있다.

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `characterization/lib/target.mjs:19-22` (`PATH_ALIASES`) | 새 API 대상 동작 보존 테스트를 실행하지 못했다. H2 단위 테스트 63개가 통과해도 MariaDB 에서 레거시와 같은 응답이 나오는지는 확인되지 않았다. 이관 완료 기준(`CLAUDE.md` §5)을 채우지 못한다. | `docker compose --profile modern up -d` 후 `./gradlew bootRun`, 이어서 `TARGET_BASE_URL=http://localhost:8080 npm test` 를 실행해 30개가 모두 통과하는지 확인한다. 서버 기동은 사람이 한다. |
| 2 | 경고 | `docs/assignment/ARCHITECTURE.md:1`, `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1` | 요청 범위("레거시 엔드포인트 1개 이관") 밖의 파일이 같은 diff 에 있다. `docs/assignment/*` 는 다른 모듈의 분석 문서다. | 이 파일들을 이관 PR 에서 분리하거나, 요청에 포함된 작업임을 근거로 남긴다. 아니면 비교 기준을 `75ce757` 로 좁혀 다시 검증한다. |
| 3 | 경고 | `ItemSearchCondition.java:9`, `legacy/item-bank-php/search.php:84-104` | 레거시는 경고 문구(100자 초과, 한 글자 키워드, `%` · `_` 와일드카드 등)를 화면에 보여 주는데 이관본은 옮기지 않았다. 응답 모양(`rows, count, message`)에는 없어 스냅샷은 통과하지만, "동작을 바꾸지 않음"에서 의도된 제외인지 승인이 필요하다. | 제외가 의도라면 `docs/item-bank/CROSS-CHECK.md` 에 명시해 사람이 승인한다. 필요하면 `warnings` 필드를 추가한다. 이 경우 스냅샷 영향 여부를 먼저 알린다. |
| 4 | 제안 | `ItemSearchService.java:51` | `byId::get` 은 id 목록을 읽은 뒤 문항이 사라지면 null 을 돌려주고, `ItemSearchRow::from` 에서 NPE 가 난다. 이 서비스에는 삭제 경로가 없어 지금은 거의 발생하지 않는다. | 목록에 없으면 건너뛰거나(`filter(Objects::nonNull)`) 명시적으로 예외 처리한다. |
| 5 | 제안 | `ItemRepository.java:34-55` | `query` 와 `countQuery` 의 WHERE 가 같은 문장으로 중복돼 한쪽만 고치면 건수와 목록이 어긋난다. | 조건 문자열을 공통 상수로 두거나 Specification 으로 합친다. 의존성 추가는 필요 없다. |

## 의심 동작 (레거시 버그로 보이지만 이관 중 고치지 않음)

- 난이도를 비우면 `level < 5` 로 조회해 난이도 5 문항이 빠진다. 주석은 "1~5 모두" 라고 적혀 있다(`legacy/item-bank-php/search.php:211-215`). 이관본도 같다(`ItemSearchCondition.java:32-33`).
- `level=abc` 처럼 숫자가 아닌 값은 `(int)` 변환으로 0 이 되어 0건이다(`ItemSearchCondition.java:56-62`). 레거시와 같지만 오류가 아니라 빈 결과를 돌려준다.
- `%` · `_` 를 이스케이프하지 않아 와일드카드로 동작한다(`ItemSearchCondition.java:42-44`, `search.php:94-97`).

## 확인 필요 (근거 줄번호를 댈 수 없음)

- MariaDB 와 H2 의 차이로 응답이 달라질 수 있다. `LIKE` 의 대소문자 구분, `title` 정렬 순서(collation), 널 파라미터 바인딩(`:levelEquals is null`)이 해당한다. 단위 테스트는 H2 만 쓰므로 위 이슈 #1 의 새 API 대상 테스트로만 확인된다.
- 태그 순서는 `Item.tags` 의 `@OrderBy("id ASC")`(`Item.java:59`)로 레거시 `tag_names` 순서와 같게 맞춘 것으로 보인다. 실제 MariaDB 응답은 확인하지 않았다.

## 판정 사유

반려 사유는 두 가지다. (1) 새 API 대상 `characterization` 테스트를 실행하지 못해 이관 완료 기준이 비어 있다. (2) 요청 범위 밖의 파일(`docs/assignment/*`, `CLAUDE.md`, 스킬 2개)이 diff 에 섞여 있다. 이관 코드 자체에는 치명 이슈나 컨벤션 위반이 없다. 서버를 띄워 새 API 대상 30개가 통과하고, 범위 기준이 이관 커밋으로 정리되면 승인할 수 있다.
