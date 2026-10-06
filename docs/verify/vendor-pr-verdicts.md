# 외주사 PR 판정

- 기준: `templates/approval-checklist.md` v0 (테스트 통과 · 치명 이슈 0건 · 요청 범위 이탈 없음 · 컨벤션 준수). 하나라도 미충족이면 반려.
- 코드는 수정하지 않았다.

| PR | 판정 | 미충족 기준 | 근거(파일:줄번호) | 외주사에 돌려보낼 수정 요청 |
|---|---|---|---|---|
| pr-1 (`vendor-prs/pr-1-missing-tests.patch`, 브랜치 `review/pr-1`) — 요청 범위: `GET /api/units/item-count` 단원별 문항 수 통계 | 반려 | ① 테스트 통과(테스트 충분성) ② 요청 범위 이탈 없음(경로 불일치) | **테스트 없음**: 새 public 메서드 `UnitService.countActiveItemsByUnit` (`modern/api/src/main/java/com/example/item/UnitService.java:38`)와 새 엔드포인트 `UnitController.itemCounts` (`UnitController.java:26-29`)를 가리키는 테스트가 `modern/api/src/test` 에 0건이다(CLAUDE.md §2 "테스트"). 기존 63건은 0 실패 · 0 skip 이지만 새 코드를 실행하지 않는다. 기존 `UnitServiceTest` 는 `@InjectMocks` 라 생성자 변경(`UnitService.java:18`)에도 통과하지만 새 메서드 테스트는 없고, `UnitControllerTest` 는 아예 없다. **경로 불일치**: 요청은 `/api/units/item-count` 인데 구현은 `@GetMapping("/item-counts")` (`UnitController.java:26`)이며 Javadoc 도 `item-counts`(`UnitController.java:25`, `UnitItemCountResponse.java:3`). **경고(비차단)**: 단원마다 `countByUnitIdAndStatus` 를 호출하는 N+1 쿼리(`UnitService.java:36-39`, 커넥션 풀 최대 5 · 대기 3초 환경). | 1) `UnitServiceTest`(Mockito)에 `countActiveItemsByUnit` 테스트 추가: 학년 · 코드 순서, 문항 0개 단원 0 포함, `ItemStatus.ACTIVE` 만 센다. 2) `UnitControllerTest` 신설(`@WebMvcTest` + `@MockBean`)에 새 엔드포인트 테스트 추가: 200 · JSON 필드(`code` · `name` · `grade` · `activeItemCount`). 3) 테스트 클래스에 `@ActiveProfiles("test")` · 한국어 `@DisplayName` · `ItemFixtures` 사용. 4) 경로를 요청대로 `/api/units/item-count` 로 맞추거나, `item-counts` 가 맞다면 요청서 정정을 근거와 함께 회신. 5) (권장) 단원별 N+1 을 `GROUP BY unit` 단일 집계 쿼리(`:status` 바인딩)로 바꾸고 `@DataJpaTest` 로 검증. |

## pr-1 판정 상세

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 미충족 | `cd modern/api && ./gradlew test` → 63건 통과 · 0 실패 · 0 skip(UP-TO-DATE 결과 XML 집계). 단, 새 서비스 메서드 · 엔드포인트 테스트가 없어 충분성 항목 미충족 |
| 치명 이슈 0건 | 충족 | 리터럴 비밀값 없음, 쿼리는 파생 메서드(바인딩), 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | 미충족 | 변경 파일 3개(`UnitController` · `UnitItemCountResponse` · `UnitService`)는 모두 요청 기능이나 경로가 `item-counts` 로 요청과 다름 |
| 컨벤션 준수 | 충족 | 컨트롤러는 서비스만 호출, `record` DTO, 클래스 `@Transactional(readOnly = true)`, `ItemStatus.ACTIVE` 상수, SLF4J 사용 |

## 확인 필요

- 발주 요청 문장의 `item-count` 가 오타인지 의도인지 확인이 필요하다. patch 본문(`vendor-prs/pr-1-missing-tests.patch` 머리말)은 `item-counts` 로 적혀 있다.
- `review/pr-1` 브랜치에는 이전 이관(문항 검색) 변경이 함께 있어 `git diff main...HEAD` 는 33개 파일이다. 본 판정은 PR 적용 커밋 `fdac614`(3개 파일)만 대상으로 했다.
- 새 API 대상 `characterization` 테스트는 실행하지 않았다(서버 기동 안 함, 이 PR 은 레거시 이관이 아님).
- 이 검토는 사람이 읽는 수동 검토이며 Sparrow 정적 분석 결과를 대신하지 않는다.
