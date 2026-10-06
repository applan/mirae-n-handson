# 이관 검증 리포트 2 — 문항 검색 (`/search.php` → `/api/items/search`)

- 검증 대상: `git diff upstream/main...HEAD` (브랜치 `day1`, 커밋 4개, 28개 파일). 1차 리포트 이후 추가된 커밋은 `9fbf36b`(리포트 1 파일 추가)뿐이다.
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준: `templates/approval-checklist.md` v0. 코드는 수정하지 않았다.

판정: 반려

## 기준별 판정

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew cleanTest test` → 63개 실행, 통과 63 · 실패 0 · 건너뜀 0. `cd characterization && npm test`(레거시 8081) → 30개 통과 · 실패 0. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API) → 29개 통과 · 실패 0 · 건너뜀 1. 건너뜀 1건은 `characterization/tests/example-units.test.js:21` 의 `it.skipIf(legacyOnly)`(단원 목록 예제, 이번 변경 파일이 아니며 레거시 전용으로 설계됨)이고 `item-bank.test.js` 는 건너뜀이 없다. 8080 은 `GET /` 응답 `item-bank-api` 로 modern 임을 확인했다. |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 쿼리 값은 모두 `:이름` 바인딩(`ItemRepository.java:34-55`), 비밀값 · 데이터 삭제 코드 없음. |
| 요청 범위 이탈 없음 | 미충족 | 이슈 #1. 요청과 무관한 파일이 같은 diff 에 남아 있다(1차 이슈 #2 미해결). |
| 컨벤션 준수 | 충족 | 아래 "컨벤션 점검" 참고. 위반 이슈 없음. |

## 1차 리포트 대비 변화

| 1차 항목 | 상태 |
|---|---|
| #1 새 API 대상 테스트 미실행 | **해소** — 서버 기동 후 29 통과 · 0 실패 |
| #2 범위 밖 파일 | **미해소** — 이번 이슈 #1 |
| #3 경고 문구 미이관 | 미해소 — 이번 이슈 #2 |
| #4 `byId::get` NPE | 미해소 — 이번 이슈 #3 |
| #5 쿼리 WHERE 중복 | 미해소 — 이번 이슈 #4 |

## 요청 문장 대비 변경 파일

| 분류 | 파일 | 요청으로 설명되는가 |
|---|---|---|
| `modern/api` main 8개 | `ItemSearch{Controller,Service,Response,Row,Condition}` · `LegacySearchParams` · `PhpStrings` 신규, `ItemRepository` 수정 | 예 |
| `modern/api` test 7개 | `ItemSearch{Condition,Controller,Repository,Service}Test` · `LegacySearchParamsTest` · `PhpStringsTest` 신규, `ItemFixtures` 수정 | 예 |
| `characterization` 2개 | `tests/item-bank.test.js`, `__snapshots__/item-bank.test.js.snap` | 예 — 동작 보존 기준선. 이후 커밋에서 수정되지 않았다. |
| `docs/item-bank/*` 4개 | ARCHITECTURE · BUSINESS-RULES · CROSS-CHECK · ERD | 예(근거 문서) |
| `docs/verify/migration-review-1.md` | 1차 검증 리포트 | 검증 루프 산출물. 이관 자체는 아니다. |
| `docs/assignment/*` 3개 | 다른 모듈 분석 문서 | **아니오** |
| `CLAUDE.md` | 신규 | **아니오** |
| `.claude/skills/{convention-check,document-module}/SKILL.md` | 신규 | **아니오** |

- 의존성(`build.gradle`), `db/`, `application.yml`, `legacy/` 변경 없음(`git diff --stat` 확인).
- 작업 트리의 미추적 파일 `.claude/skills/verify/`, `CLAUDE.OLD.md` 는 diff 에 포함되지 않았다.
- 범위 밖 파일 7개(`docs/assignment/*`, `CLAUDE.md`, 스킬 2개)는 커밋 `75ce757` 에 들어 있다. 비교 기준을 `75ce757...HEAD` 로 바꾸면 변경은 이관 코드 · 테스트 · 근거 문서 · 리포트 1 로 좁혀진다.

## 컨벤션 점검 (grep · 읽기)

- `System.out` · `printStackTrace` · `LocalDateTime.now()` · 빈 `catch` · `@Disabled` · 와일드카드 import 가 이번 변경 파일에 없다. `"A"` 리터럴은 기존 테스트(`ItemRepositoryTest:58`, `ItemControllerTest:34,69,70`, `ItemServiceTest:48,74`)와 `ItemStatus.java` 에만 있고 이번 main 변경에는 없다(서비스는 `ItemStatus.ACTIVE` 사용, `ItemSearchService.java:66`).
- 120자 초과 줄 없음. 컨트롤러는 Repository · SQL 을 쓰지 않고 `record` 를 반환한다(`ItemSearchController.java:25`).
- 서비스는 클래스에 `@Transactional(readOnly = true)`(`ItemSearchService.java:47`), 다른 도메인 Repository 주입 없음. 로그는 SLF4J `DEBUG` 이며 학생 식별자 · 비밀값을 남기지 않는다(`:81`).
- 새 public 서비스 메서드 `search` 와 새 엔드포인트에 계층별 테스트가 있다(`ItemSearchServiceTest`, `ItemSearchControllerTest`, `ItemSearchRepositoryTest`).

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `docs/assignment/ARCHITECTURE.md:1`, `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1` | 요청("레거시 엔드포인트 1개 이관") 밖의 파일 7개가 같은 diff 에 있다. 체크리스트 3 의 "모든 파일이 요청 문장으로 설명된다"를 채우지 못한다. 1차에서 지적했으나 `9fbf36b` 는 리포트만 추가했다. | 이 파일들을 이관 PR 에서 분리하거나(별도 브랜치), 요청에 포함된 작업임을 근거로 남긴다. 아니면 `75ce757...HEAD` 로 범위를 좁혀 재검증한다. 코드 변경은 필요 없다. |
| 2 | 경고 | `ItemSearchCondition.java:9`, `legacy/item-bank-php/search.php:84-104` | 레거시의 경고 문구(100자 초과 · 한 글자 키워드 · `%` `_` 와일드카드)가 이관본에 없다. 응답 모양(`rows, count, message`)과 스냅샷에는 없어 테스트는 통과하지만, "동작을 바꾸지 않음"에서 의도적 제외인지 승인 기록이 없다. | 의도된 제외면 `docs/item-bank/CROSS-CHECK.md` 에 명시해 승인받는다. 추가하려면 `warnings` 필드가 필요하며, 스냅샷 영향 여부를 먼저 사람에게 알린다. |
| 3 | 제안 | `ItemSearchService.java:79` | `ids.stream().map(byId::get)` 은 id 조회와 상세 조회 사이에 문항이 사라지면 null 이 되어 `ItemSearchRow::from`(`ItemSearchRow.java:115-121`)에서 NPE 가 난다. 이 서비스에는 삭제 경로가 없고 한 읽기 전용 트랜잭션 안이라 발생 가능성은 낮다. | 목록에 없는 id 는 건너뛰거나 명시적으로 예외 처리한다. |
| 4 | 제안 | `ItemRepository.java:34-55` | `query` 와 `countQuery` 의 WHERE 가 같은 문장으로 중복돼, 한쪽만 고치면 건수와 목록이 어긋난다. | 조건 문자열을 공통 상수(텍스트 블록 연결 대신 상수 조합)로 두거나 Specification 으로 합친다. 의존성 추가는 필요 없다. |

## 의심 동작 (레거시 버그로 보이지만 이관 중 고치지 않음)

- 난이도를 비우면 `level < 5` 로 조회해 난이도 5 문항이 빠진다. 주석은 "1~5 모두" 라고 적혀 있다(`legacy/item-bank-php/search.php:211-215`). 이관본도 같다(`ItemSearchCondition.java:32-33`).
- `level=abc` 처럼 숫자가 아닌 값은 `(int)` 변환으로 0 이 되어 0건이다(`ItemSearchCondition.java:56-62`). 오류가 아니라 빈 결과를 돌려준다.
- `%` · `_` 를 이스케이프하지 않아 와일드카드로 동작한다(`ItemSearchCondition.java:42-44`, `search.php:94-97`).

## 확인 필요 (근거 줄번호를 댈 수 없음)

- H2(단위 테스트)와 MariaDB 의 `LIKE` 대소문자 · collation 차이는 새 API 대상 `characterization` 29개 통과로 스냅샷 범위 안에서는 확인됐다. 스냅샷에 없는 입력의 차이는 확인하지 못했다.
- 새 API 대상 실행에서 건너뛴 1건(`example-units.test.js:21`)이 이번 이관 범위와 무관한 레거시 전용 케이스라는 판단은 파일 주석 · 이름에 근거한 것이며, 건너뛴 케이스 본문은 이번 검증에서 대조하지 않았다.
- 이 검증은 서버 두 개(8080 modern, 8081 legacy)가 떠 있는 상태에서 실행했다. 서버 기동 상태(DB 시드 포함)가 달라지면 결과가 달라질 수 있다.

## 교차 검증 (다른 터미널의 Claude 세션)

- 다른 터미널 세션의 출력(일부만 전달됨)에 따르면 `modern/api` 테스트 63건 통과, 레거시 대상 `characterization` 30건 통과를 실행했다. 이 리포트의 수치와 같다.
- 전달된 출력은 "나머지 변경분(CLAUDE.md · characterization · docs)을 확인합니다"에서 끝난다. 새 API(8080) 대상 실행 여부, 이슈 목록, 판정은 없다. 따라서 이 세션의 판정에 대한 일치 · 불일치는 확인하지 못했다.
- 교차 검증으로 확정된 것은 두 세션이 같은 테스트 수치를 얻었다는 점뿐이다.

## 판정 사유

반려 사유는 한 가지다. 요청 범위 밖의 파일(`docs/assignment/*`, `CLAUDE.md`, 스킬 2개)이 diff 에 남아 있다(이슈 #1). 1차의 테스트 미실행 사유는 해소됐다: 이관 코드는 modern 단위 테스트 63개와 레거시 · 새 API 대상 동작 보존 테스트를 모두 통과했고, 치명 이슈와 컨벤션 위반은 없다. 범위를 이관 커밋(`75ce757...HEAD`)으로 정리하거나 범위 밖 파일이 요청에 포함됐음을 기록하면 승인할 수 있다. 이슈 #2 는 승인 여부를 리뷰어가 판단한다.

> 이 검증은 사람이 읽는 수동 검토이며 Sparrow 정적 분석 결과를 대신하지 않는다.
