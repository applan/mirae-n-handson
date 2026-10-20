# 완성 기준 점검 — 백엔드 / 최민수

1. 트랙 완성 기준 ①: 충족
   증거(실행한 명령과 결과 한 줄): 2026-10-20 재실행 — `cd characterization && npm test` → Tests 30 passed (30), failed 0 · `TARGET_BASE_URL=http://localhost:8080 npm test` → Tests 29 passed | 1 skipped (30), failed 0 (건너뜀 1건은 `characterization/tests/example-units.test.js:21` 의 레거시 전용 `skipIf`) · `item-bank.test.js` 16 케이스는 8081 · 8080 모두 16 passed · `cd modern/api && ./gradlew test --rerun` → BUILD SUCCESSFUL, 64개 · failures 0 · errors 0 · skipped 0
2. 트랙 완성 기준 ②: 충족
   증거(파일 경로 또는 PR 주소): https://github.com/applan/mirae-n-handson/pull/1 — 2026-10-20 `gh pr view 1 --repo applan/mirae-n-handson --json comments --jq '[.comments[] | select(.author.login == "claude")] | length'` → 2 (MERGED, 작성자 `claude` 코멘트 2026-10-06 07:46Z · 07:48Z. Day 3-1 에서 일부러 문제를 심은 `practice/bad-pr` 라 치명 지적이 정상)
3. /verify 판정: 승인
   증거(판정 요약 한 줄, 치명 건수): 2026-10-20 코드 변경 없음 — 판정 생략. 이전 판정 2026-10-06: 별도 세션 `/verify day4-start..HEAD` → 승인 · 치명 0 · 경고 1(`ItemSearchServiceTest.java:67` 테스트 숫자 리터럴) · 제안 2(`ItemSearchServiceTest.java:73-74` count 미검증, `ItemSearchService.java:53` 건너뛴 건수 로그). 이어서 tester Sub-agent 가 건수 · 메시지 단정을 보강(da53214)하고, reviewer Sub-agent 가 `./gradlew test --rerun` 64 통과 · 8080 29+1 건너뜀을 증거로 판정 → 승인 · 치명 0 · 제안 2

범위를 줄였는가: 예 — 줄였다면 뺀 항목과 이유는 DESIGN.md "범위 조정 (4회차)" 절에 있음
팀 저장소에 가져갈 파일: .claude/skills/rca (장애 로그 RCA Skill + 마스킹 규칙, a-connection-pool · b-deploy-5xx 두 시나리오에서 사용)
