레거시 문항 검색(`search.php`)을 새 API `GET /api/items/search` 로 옮긴 결과가 레거시와 같은 응답을 내는지 동작 보존 테스트로 확인하고, 변경은 `/verify` 와 PR 자동 리뷰(`.github/workflows/pr-review.yml`)로 검증한다.
`docker compose --profile php up -d` 와 `cd modern/api && ./gradlew bootRun` 을 띄운 뒤 저장소 루트에서 `(cd characterization && npm test && TARGET_BASE_URL=http://localhost:8080 npm test) && (cd modern/api && ./gradlew test)`
레거시 경고 문구(100자 초과 · 한 글자 키워드 · 와일드카드)는 이관하지 않았고 상세 조회에서 빠진 문항은 건너뛰므로, 현업에 옮길 때는 대상 주소(`characterization/lib/target.mjs`)와 PR 리뷰 인증(개인 구독 토큰 → 조직 API 키)을 바꿔야 한다.
