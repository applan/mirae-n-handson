# 실전 프로젝트 설계 — 백엔드 트랙

> 3회차 설계 초안이 저장소에 없어 4회차(2026-10-06)에 최소 범위로 작성했다.

## 1. 목표

레거시 문항 검색(`legacy/item-bank-php/search.php`)을 새 API `GET /api/items/search` 로 동작을 바꾸지 않고 이관하고, 이관 결과를 동작 보존 테스트와 PR 자동 리뷰로 검증하는 파이프라인을 끝까지 한 번 돌린다.

## 2. 완성 기준

1. 이관 모듈의 동작 보존 테스트 통과 — `characterization` 에서 `npm test` 와 `TARGET_BASE_URL=http://localhost:8080 npm test` 가 전부 통과
2. PR 자동 리뷰 코멘트 1회 — 내 포크 PR 에 Claude 자동 리뷰 코멘트가 달려 있음(Day 3-1 PR 주소)

## 3. 범위

### 한다

- 엔드포인트 1개 이관: `/search.php` → `GET /api/items/search` (`ItemSearchController` · `ItemSearchService` · `ItemRepository` 검색 쿼리와 계층별 테스트)
- `characterization/lib/target.mjs` 의 `PATH_ALIASES` 로 레거시 경로와 새 경로를 잇고, 기존 입력 케이스(`characterization/tests/item-bank.test.js`)를 그대로 사용
- 검증: `./gradlew test`, 동작 보존 테스트 2회(레거시 8081 · 새 API 8080), `/verify`
- PR 자동 리뷰: `.github/workflows/pr-review.yml` (내 포크 `main`)

### 안 한다

- 다른 레거시 화면 · 모듈(`assignment` · `grade`) 이관
- `characterization/` 테스트 코드 · 스냅샷 변경
- 레거시 의심 동작 수정(`docs/verify/migration-review-2.md` "의심 동작" 절)

## 4. 단계

| # | 단계 | 확인 방법 |
|---|---|---|
| 1 | 검색 엔드포인트 이관 + 계층별 테스트 | `cd modern/api && ./gradlew test` |
| 2 | 경로 대응표 연결 | `characterization/lib/target.mjs` 의 `'/search.php': '/api/items/search'` |
| 3 | 동작 보존 테스트 2회 | `npm test` · `TARGET_BASE_URL=http://localhost:8080 npm test` |
| 4 | 이관 검증 리포트 | `docs/verify/migration-review-2.md` |
| 5 | PR 자동 리뷰 워크플로 · 코멘트 | 내 포크 Actions `Claude Auto Review` · PR 코멘트 |
| 6 | 2차 검증 미해소 항목 정리 | `docs/verify/migration-review-2.md` 이슈 #1 ~ #4 |

## 범위 조정 (4회차)

> 2026-10-06 승인. 완성 기준 ① · ② 는 이미 증거가 있다(동작 보존 테스트 레거시 30 통과 · 새 API 29 통과 1 건너뜀, PR https://github.com/applan/mirae-n-handson/pull/1). 남은 일은 2차 검증 미해소 항목(`docs/verify/migration-review-2.md:56-59`, 전부 경고 · 제안)이다.

### 남긴다

1. 이슈 #3 — `ItemSearchService` 의 `byId::get` NPE 방어. 목록에 없는 id 는 건너뛰고, 그 경우를 서비스 테스트 1개로 고정한다.
   - 확인: `cd modern/api && ./gradlew test` 통과, 동작 보존 테스트 2회 통과
2. 트랙 확인 명령 전체 재실행
   - 확인: `cd characterization && npm test` · `TARGET_BASE_URL=http://localhost:8080 npm test` · `cd modern/api && ./gradlew test`

### 뺀다

- 이슈 #2 경고 문구 이관 — 응답에 `warnings` 필드가 필요해 스냅샷 영향이 생길 수 있다(스냅샷 변경은 먼저 사람에게 묻는 규칙). 응답 모양 · 스냅샷이 그대로라 완성 기준 ① 은 유지된다.
- 이슈 #4 WHERE 중복 리팩터링 — 동작 변화가 없는 정리 작업이라 완성 기준 ① · ② 에 영향이 없다.

### 교육 후로 미룬다

- 이슈 #1 범위 밖 파일 분리 — 1~3회차 커밋 이력 재구성이 필요하다. 오늘 검증 범위(`day4-start..HEAD`)에 들어오지 않는다.
