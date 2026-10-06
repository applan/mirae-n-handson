# 외주사 PR 판정 — pr-1 (`vendor-prs/pr-1-missing-tests.patch`)

- 요청 범위(발주 측 확인): `GET /api/units/item-count` — 단원별 문항 수 통계 API 추가
- 기준: `templates/approval-checklist.md` v0. 네 기준이 모두 충족돼야 승인, 하나라도 미충족이면 반려.
- 방식: patch 본문만 읽고 판정했다. patch 는 적용하지 않았고, 테스트는 실행하지 않았으며, 코드는 수정하지 않았다.
- 줄번호: `patch:N` 은 patch 파일의 줄. `파일:N` 은 patch 를 적용했을 때의 줄로, hunk 머리(`@@`)를 기준으로 계산했다.

```
판정: 반려
```

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 확인 필요 | 지시에 따라 테스트를 실행하지 않았다. 커밋 메시지에 "기존 테스트 전부 통과 확인(./gradlew test)"(`patch:14`)이라고 적혀 있지만 리뷰어가 검증한 결과는 아니다. 그리고 patch 에 테스트 파일이 하나도 없어서 "테스트가 충분하다" 항목은 실행 결과와 관계없이 충족할 수 없다(#2). |
| 치명 이슈 0건 | 충족 | 비밀값 리터럴 없음. 쿼리는 파생 메서드 `countByUnitIdAndStatus`(`ItemRepository.java:28`, 파라미터 바인딩)만 쓴다. 삭제 · 덮어쓰기 코드 없음. 조회 전용 엔드포인트다. |
| 요청 범위 이탈 없음 | 미충족 | 요청 경로는 `/api/units/item-count` 인데 구현 경로는 `/api/units/item-counts` 다(#1). 변경 파일 3개(`UnitController` · `UnitItemCountResponse` · `UnitService`)는 모두 요청 기능에 속하고, 의존성 · 스키마 · 설정 변경은 없다. |
| 컨벤션 준수 | 미충족 | CLAUDE.md §2 "테스트" 위반: 새 public 서비스 메서드와 새 엔드포인트에 테스트가 없다(#2). 나머지 항목은 지켰다. 컨트롤러는 서비스만 호출하고, 응답은 `record` DTO 이며, 클래스에 `@Transactional(readOnly = true)` 가 그대로 있고(`UnitService.java:10`), `ItemStatus.ACTIVE` 상수와 SLF4J 를 쓴다. 같은 도메인 패키지(`item`)의 `ItemRepository` 를 주입하므로 도메인 간 주입 규칙에도 걸리지 않는다. |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/java/com/example/item/UnitController.java:26` (`patch:32`) · Javadoc `UnitController.java:25` (`patch:31`) · `UnitItemCountResponse.java:3` (`patch:45`) | 요청 경로는 `GET /api/units/item-count` 인데 매핑은 `@GetMapping("/item-counts")` 다. 요청서대로 호출하는 클라이언트는 이 API 를 찾지 못한다. patch Subject(`patch:4`)에는 경로가 없고, 커밋 본문(`patch:10`)이 `item-counts` 라고 적혀 있을 뿐이다. | 매핑과 Javadoc 2곳을 `/item-count` 로 고친다. `item-counts` 가 맞다고 보면 요청서를 바꿔 달라고 근거와 함께 회신한다. |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/UnitService.java:36` (`patch:78`) · `UnitController.java:27` (`patch:33`) | CLAUDE.md §2 "새 public 서비스 메서드와 새 엔드포인트마다 테스트 메서드가 1개 이상"을 어겼다. patch 의 변경 파일은 main 3개뿐이다(`patch:16-20`). 기존 `UnitServiceTest` 는 `@InjectMocks`(`UnitServiceTest.java:22`)로 서비스를 만들기 때문에 생성자 변경 뒤에도 깨지지 않을 수 있지만, 새 메서드를 검증하지는 않는다. `UnitControllerTest` 는 저장소에 없다. | ① `UnitServiceTest` 에 `countActiveItemsByUnit` 테스트를 추가한다(`@Mock ItemRepository`). 학년 · 코드 순서 유지, 문항 0개 단원을 0으로 포함, `ItemStatus.ACTIVE` 로 조회하는지를 검증한다. ② `UnitControllerTest` 를 새로 만든다(`@WebMvcTest` + `@MockBean UnitService`). 200 응답과 JSON 필드 `code` · `name` · `grade` · `activeItemCount` 를 확인한다. ③ `@ActiveProfiles("test")` · 한국어 `@DisplayName` · `ItemFixtures` 를 쓴다. |
| 3 | 경고 | `modern/api/src/main/java/com/example/item/UnitService.java:37-39` (`patch:79-81`) | 단원을 모두 읽은 뒤 단원마다 `countByUnitIdAndStatus` 를 한 번씩 호출한다(N+1 쿼리). 한 트랜잭션 안에서 커넥션 하나를 단원 수 + 1번의 쿼리 동안 쥐고 있다. 커넥션 풀은 최대 5 · 대기 3초다(CLAUDE.md §4). 단원이 늘거나 호출이 몰리면 응답이 느려지고 풀 대기가 길어진다. | `ItemRepository` 에 단원별 `GROUP BY` 집계 쿼리 하나를 만들고(상태는 `:status` 바인딩), 서비스에서 단원 목록과 합친다. 문항 0개 단원은 0으로 채운다. 이 쿼리는 `@DataJpaTest` 로 검증한다. |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/UnitService.java:32-35` (`patch:74-77`) | Javadoc 에 응답 경로가 없어서 #1 처럼 경로가 어긋나도 서비스 쪽에서는 드러나지 않는다. | #1 을 고칠 때 Javadoc 에 엔드포인트를 적거나 컨트롤러 Javadoc 만 근거로 삼도록 정리한다. |

## 확인 필요

- 테스트 통과 여부: patch 를 적용하지 않고 판정하라는 지시에 따라 `./gradlew test` 결과를 판정 근거로 쓰지 않았다. 판정하려면 `review/pr-1` 브랜치에 적용한 뒤 `cd modern/api && ./gradlew test` 를 실행해야 한다.
- 단원 수 규모: #3 이 실제로 얼마나 영향을 줄지는 운영 단원 수와 호출 빈도에 달려 있다. patch 만으로는 판단할 수 없다.
- 새 API 를 대상으로 한 `characterization` 테스트는 해당 없음(레거시 이관 변경이 아님).

## 보안 검토 (행정안전부 「소프트웨어 개발보안 가이드」(2021) 구현단계)

| 항목 | 파일:줄번호 | 판정 |
|---|---|---|
| SQL 삽입 | `UnitService.java:39` (파생 메서드 `ItemRepository.java:28`) | 해당 없음. 파라미터 바인딩을 쓴다 |
| 하드코드된 중요정보 | patch 전체 | 해당 없음 |
| 오류 메시지 정보노출 · 오류 상황 대응 부재 | patch 전체 | 해당 없음. try-catch 가 없고 `GlobalExceptionHandler` 에 맡긴다 |
| 로그를 통한 정보노출 | `UnitService.java:41` | 해당 없음. 건수만 로그에 남긴다 |
| 부적절한 인가 | `UnitController.java:26-29` | 해당 없음(공개 문항 수만 조회한다). 저장소에 인증 계층이 따로 없다 |

이 검토는 사람이 읽는 수동 검토다. Sparrow 정적 분석 결과를 대신하지 않는다.
