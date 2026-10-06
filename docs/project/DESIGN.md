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
