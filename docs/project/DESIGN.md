# 실전 프로젝트 설계 — 백엔드 / 최민수

> 2026-10-20 작성. 한 차시(약 40분) 안에 끝낼 최소 설계다. 2026-10-06 의 이전 설계는 이 파일로 대체했고, `docs/project/CHECK.md` 가 참조하는 "범위 조정 (4회차)" 절은 맨 아래 부록에 그대로 남겼다.

## 1. 목표

레거시 문항 검색 `legacy/item-bank-php/search.php` 를 옮긴 `GET /api/items/search` 가 Day 1-3 입력 케이스 16개에서 레거시와 같은 응답을 내는 것을 오늘 환경에서 다시 증명하고, 3회차 PR 자동 리뷰 코멘트와 함께 `docs/project/CHECK.md` 에 기록한다.

## 2. 재료

| 재료 | 있음/없음 | 경로 |
|---|---|---|
| 이관 대상 레거시 코드 | 있음 | `legacy/item-bank-php/search.php` (731줄) |
| 이관 코드(modern/api main) | 있음 | `modern/api/src/main/java/com/example/item/ItemSearchController.java` (`GET /api/items/search`) · `ItemSearchService.java` · `ItemSearchCondition.java` · `ItemSearchResponse.java` · `ItemSearchRow.java` · `LegacySearchParams.java` · `PhpStrings.java` · `ItemRepository.java` |
| 이관 코드 계층별 테스트 | 있음 | `modern/api/src/test/java/com/example/item/ItemSearchControllerTest.java` · `ItemSearchServiceTest.java` · `ItemSearchRepositoryTest.java` · `ItemSearchConditionTest.java` · `LegacySearchParamsTest.java` · `PhpStringsTest.java` · `ItemFixtures.java` |
| 동작 보존 입력 케이스(Day 1-3) | 있음 | `characterization/tests/item-bank.test.js` (`it` 16개) |
| 동작 보존 스냅샷(기준선) | 있음 | `characterization/__snapshots__/item-bank.test.js.snap` |
| 레거시 → 새 경로 대응표 | 있음 | `characterization/lib/target.mjs:21-24` (`'/search.php': '/api/items/search'`) |
| 동작 보존 테스트 실행 틀 · 설명 | 있음 | `characterization/README.md` · `lib/normalize.mjs` · `vitest.config.mjs` |
| 비즈니스 규칙 근거 문서 | 있음 | `docs/item-bank/BUSINESS-RULES.md` · `docs/item-bank/CROSS-CHECK.md` · `docs/item-bank/ARCHITECTURE.md` · `docs/item-bank/ERD.md` |
| 이관 검증 리포트(1차 · 2차) | 있음 | `docs/verify/migration-review-1.md` · `docs/verify/migration-review-2.md` (미해소 이슈 #2 · #4) |
| 완성 기준 점검 기록 | 있음 | `docs/project/CHECK.md` (2026-10-06 기준. 2단계에서 갱신) |
| 프로젝트 한 줄 설명 | 있음 | `docs/project/README.md` |
| Skill — 검증 판정 | 있음 | `.claude/skills/verify/SKILL.md` |
| Skill — 컨벤션 점검 | 있음 | `.claude/skills/convention-check/SKILL.md` |
| Skill — 영향도 분석 | 있음 | `.claude/skills/impact/SKILL.md` |
| Skill — 모듈 문서화 | 있음 | `.claude/skills/document-module/SKILL.md` (이미 산출물이 있어 오늘은 안 씀) |
| Skill — rca · batch-check · 4mat-tutor | 있음 | `.claude/skills/rca/` · `batch-check/` · `4mat-tutor/` (백엔드 트랙 해당 없음) |
| Sub-agent — 읽기 전용 리뷰어 | 있음 | `.claude/agents/reviewer.md` |
| Sub-agent — 테스터 | 있음 | `.claude/agents/tester.md` |
| Sub-agent — iac-reviewer | 있음 | `.claude/agents/iac-reviewer.md` (백엔드 트랙 해당 없음) |
| Hook — 위험 명령 차단 | 있음 | `hooks/block-dangerous.mjs` (`.claude/settings.json` PreToolUse(Bash) 에 연결) |
| Hook — 커밋 전 시크릿 검사 | 있음 | `hooks/secret-scan.mjs` (같은 곳에 연결, `scripts/hook-node.sh` 경유) |
| 권한 설정(deny · ask · allow) | 있음 | `.claude/settings.json` |
| 승인 체크리스트 · 검증루프 | 있음 | `templates/approval-checklist.md` · `templates/verification-loop.md` |
| PR 자동 리뷰 워크플로 | 있음(원격에만) | 포크 `origin/main` 의 `.github/workflows/pr-review.yml`. 현재 브랜치 `day03` 작업 트리에는 `.github/` 가 없다 |
| 3회차 PR(자동 리뷰 코멘트) | 있음 | https://github.com/applan/mirae-n-handson/pull/1 — `practice/bad-pr` → `main`, MERGED, 작성자 `claude` 코멘트 2개, Actions `Claude Auto Review` 성공 2회(2026-10-06) |
| CI 이식 예시 | 있음 | `ci-ports/Jenkinsfile` (읽기 자료. 오늘은 안 씀) |
| pipeline-samples | 있음 | `pipeline-samples/` (배치 로그 · Terraform. 데이터 · 인프라 트랙 자료라 백엔드 트랙 해당 없음) |
| 레거시 서버(8081) 기동 상태 | 없음 | 설계 작성 시점 `docker compose ps` 에 `mariadb` 만 Up. 1단계에서 `php` 프로필을 올린다 |
| 새 API(8080) 기동 상태 | 확인 필요 | 8080 에 프로세스가 떠 있으나 현재 소스로 빌드한 modern API 인지 확인하지 못했다(미해결 질문 2) |
| 오늘 날짜의 점검 증거 | 없음 → 새로 만들 것 | `docs/project/CHECK.md` 의 1 · 2 · 3 번 증거 줄(2단계) |

## 3. 범위

### 한다

1. `search.php` → `GET /api/items/search` 한 개만. 같은 16 케이스를 레거시(8081)와 새 API(8080) 양쪽에 돌려 스냅샷과 비교하고, 이관 모듈 단위 테스트를 한 번 돌린다(완성 기준 1 ~ 4).
2. PR #1 의 자동 리뷰 코멘트를 명령으로 확인하고, 오늘 날짜의 명령 · 수치를 `docs/project/CHECK.md` 에 적는다(완성 기준 5 ~ 7).

### 하지 않는다

- 이관 코드 수정(테스트가 실패해도) — 통과하면 완성 기준 1 ~ 4 는 코드 변경 없이 충족된다. 실패하면 실패 케이스 이름과 스냅샷 차이를 그대로 보고하고 멈추며 "완료"라고 쓰지 않는다. 수정이 들어가면 리뷰어 판정까지 한 바퀴가 길어져 40분 안에 끝까지 돌지 못한다.
- 리뷰어 판정(새 세션 `/verify` · reviewer Sub-agent) — 코드 변경이 없으면 판정할 diff 가 없고, 완성 기준 6 은 CHECK.md 3번의 "코드 변경 없음 — 판정 생략" 문구로 충족된다.
- Actions 실행 이력 확인(`gh run list`) — 완성 기준 5 는 PR 코멘트 존재만 보므로 코멘트 수를 세는 명령 하나로 충족된다.
- 별도의 환경 · 전제 확인 단계 — 서버 기동은 1단계 첫 줄에 넣었고, 꺼져 있으면 테스트가 `ECONNREFUSED` 로 바로 실패해 드러나므로 완성 기준 1 · 2 의 판정은 그대로다.
- 다른 엔드포인트 · 다른 모듈(`assignment` · `grade`) 이관 — Day 1-3 입력 케이스가 `item-bank.test.js` 하나뿐이라 새 기준선부터 찍어야 하고 40분에 들어가지 않는다.
- `characterization/` 테스트 코드 · 스냅샷 수정 — 기준선이 바뀌면 동작 보존 증명이 무너진다(CLAUDE.md 5절). 바꿔야 한다고 판단되면 멈추고 묻는다.
- 2차 검증 이슈 #2(레거시 경고 문구 이관) — 응답에 `warnings` 필드가 생겨 스냅샷에 영향을 줄 수 있어 먼저 사람에게 물어야 한다(`docs/verify/migration-review-2.md:57`).
- 2차 검증 이슈 #4(`ItemRepository` WHERE 중복 정리) — 동작이 바뀌지 않는 정리 작업이라 완성 기준 ① · ② 에 영향이 없다.
- 레거시 의심 동작 수정(빈 난이도의 `level < 5`, `%` · `_` 와일드카드, 비숫자 난이도 → 0) — 이관 중에는 레거시 버그를 고치지 않는다. 목록은 `docs/verify/migration-review-2.md:61-65` 에 있다.
- 새 PR 생성 · `day03` 에 `.github/workflows/pr-review.yml` 추가 — 완성 기준 ② 는 3회차 PR #1 코멘트로 충족하고, 워크플로 파일 추가는 요청 범위 밖이다.
- 의존성 · DB 스키마 · `application.yml` 변경 — 오늘 작업에 필요 없고 CLAUDE.md 3절이 먼저 물으라고 정한 항목이다.

## 4. 미해결 질문

1. 이관 코드가 이미 있다. 오늘 차시를 "재검증 + 기록"으로 끝낼지, 이슈 #4 같은 동작 보존 리팩터링을 한 건 넣을지는 사람이 정한다. 이 설계는 코드 변경 없는 재검증을 전제로 썼다. 테스트가 실패해도 코드를 고치지 않고 보고한다(3절 "하지 않는다").
2. 8080 에 떠 있는 프로세스가 현재 소스로 빌드한 modern API 인지 확인하지 못했다(`curl` 은 `.claude/settings.json` 에서 Claude 에게 deny 라 사람이 직접 확인). 1단계 확인 명령으로 가린다. 확실하지 않으면 `./gradlew bootRun` 을 다시 띄운다.
3. `day03` 작업 트리에 `.github/` 가 없다(워크플로는 포크 `origin/main` 에만 있음). `day03` 에서 새 PR 을 열면 자동 리뷰가 도는지는 확인하지 못했다. 오늘 범위에는 새 PR 이 없어 영향이 없다.
4. 코드 변경이 없으면 `/verify` 가 판정할 diff 가 없다. 그 경우 리뷰어 판정을 생략하고 CHECK.md 3번에 "코드 변경 없음" 으로 적을지, reviewer Sub-agent 에 실행 증거 재확인만 맡길지 정하지 못했다. 완성 기준에는 두 경우를 모두 적었다.
5. 이전 설계의 "범위 조정 (4회차)" 절을 부록으로 남겼다. CHECK.md 가 더 이상 참조하지 않게 되면 지워도 되는지.

## 5. 단계

### 1단계 — 서버 기동 · 동작 보존 테스트 2회 · 단위 테스트 (약 20분)

- 할 일: 레거시 `php` 프로필과 새 API 를 띄우고, 같은 16 케이스를 레거시(기본 주소 8081)와 새 API(`TARGET_BASE_URL` 8080)에 돌린 뒤 이관 모듈 단위 테스트를 캐시 없이 돌린다. 실패하면 (a) 규칙 차이 (b) 형식 차이 (c) 데이터 · 환경 차이로 나눠 실패 케이스 이름과 스냅샷 차이를 그대로 보고하고 멈춘다. 코드 · 테스트 · 스냅샷은 손대지 않는다.
- 만들거나 고칠 파일: 없음.
- 확인 방법:

```bash
docker compose --profile php up -d && docker compose ps                # item-bank 웹(8081) 과 mariadb 가 Up
cd modern/api && ./gradlew bootRun                                     # 다른 터미널. "Tomcat started on port 8080"
curl -s http://localhost:8080/                                         # item-bank-api
cd characterization && npm test                                        # Tests 30 passed (30), failed 0
cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test  # Tests 29 passed | 1 skipped (30), failed 0
                                                                       #   건너뜀은 example-units.test.js:21 의 레거시 전용 1건만
cd modern/api && ./gradlew test --rerun                                # BUILD SUCCESSFUL, failed 0
git status --short                                                     # 출력 없음 (코드 · 테스트 · 스냅샷 미수정)
```

### 2단계 — PR 코멘트 확인 · CHECK.md 기록 (약 15분)

- 할 일: PR #1 의 자동 리뷰 코멘트 수를 명령으로 확인한다. `docs/project/CHECK.md` 의 1 · 2 · 3 번 증거 줄에 오늘 날짜 · 실행 명령 · 통과 수를 적고, 3번에는 "코드 변경 없음 — 판정 생략" 이라고 적는다.
- 만들거나 고칠 파일: `docs/project/CHECK.md` (수정).
- 확인 방법:

```bash
gh pr view 1 --repo applan/mirae-n-handson --json comments \
  --jq '[.comments[] | select(.author.login == "claude")] | length'    # 1 이상 (설계 시점 2)
grep -n "2026-10-20" docs/project/CHECK.md                             # 1 · 2 · 3 번 증거 줄에 오늘 날짜
grep -n "판정 생략" docs/project/CHECK.md                               # 3번 줄 1건
git status --short                                                     # " M docs/project/CHECK.md" 한 줄
```

## 6. 완성 기준 (Day 4-1에서 점검)

- [ ] `cd characterization && npm test`(대상 8081) 결과가 `failed 0` 이고 `item-bank.test.js` 16 케이스가 모두 passed 다.
- [ ] `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` 결과가 `failed 0` 이고 `item-bank.test.js` 16 케이스가 모두 passed 다. 건너뜀은 `example-units.test.js:21` 1건만 허용한다.
- [ ] `cd modern/api && ./gradlew test --rerun` 이 `BUILD SUCCESSFUL` 이고 failed 0 이다.
- [ ] `git status --short characterization/` 와 `git diff --stat characterization/` 출력이 비어 있다.
- [ ] https://github.com/applan/mirae-n-handson/pull/1 에 작성자 `claude` 코멘트가 1개 이상 있고, 그 주소가 `docs/project/CHECK.md` 2번 증거에 적혀 있다.
- [ ] 1단계에서 `modern/api` 를 고쳤다면 리뷰어(새 세션 `/verify` 또는 reviewer Sub-agent) 판정이 "승인 · 치명 0" 이고 CHECK.md 3번에 기록되어 있다. 고치지 않았다면 CHECK.md 3번에 "코드 변경 없음 — 판정 생략" 이라고 적혀 있다.
- [ ] `docs/project/CHECK.md` 의 1 · 2 · 3 번 증거 줄에 `2026-10-20` 날짜와 실행 명령 · 통과 수가 적혀 있다.

---

## 부록 — 이전 설계(2026-10-06)의 "범위 조정 (4회차)" 기록

> `docs/project/CHECK.md` 가 이 절을 참조하므로 그대로 옮겨 둔다. 2026-10-06 승인. 완성 기준 ① · ② 는 이미 증거가 있다(동작 보존 테스트 레거시 30 통과 · 새 API 29 통과 1 건너뜀, PR https://github.com/applan/mirae-n-handson/pull/1). 남은 일은 2차 검증 미해소 항목(`docs/verify/migration-review-2.md:56-59`, 전부 경고 · 제안)이다.

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
