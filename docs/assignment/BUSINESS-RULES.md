# legacy/assignment-thymeleaf 비즈니스 규칙 후보 — 새 배포 화면

> 분석 범위: **"새 배포" 화면 하나**(`GET /distributions/new` · `POST /distributions`)와 이 화면이 부르는 코드까지만 다룬다. 재배포 · 배포 목록 규칙은 없다.
> 근거는 `파일:줄번호`로 적는다. 경로는 `legacy/assignment-thymeleaf/` 기준이고 Java 파일은 `src/main/java/com/example/assign/` 아래, 템플릿은 `src/main/resources/templates/` 아래 경로로 줄여 쓴다. DDL 은 저장소 루트의 `db/mariadb/init/` 기준이다.
> 코드를 읽기만 했고 실행해 확인하지 않았다. 직접 열어 확인하지 못한 것은 "미확인"으로 둔다.
> 확신도: **확실** = 조건문 · SQL 로 확인 / **추정** = 이름 · 주석 · 프레임워크 · DB 기본 동작으로 짐작.

## POST /distributions 검사 순서 (요약)

| 순서 | 위치 | 조건 | 결과 | 규칙 |
|---|---|---|---|---|
| 1 | 컨트롤러 | 과제 없음 | flash `error`, `/distributions/new` | BR-04 |
| 2 | 컨트롤러 | `due_at < now` 이고 `status != "X"` | flash `error`, `/distributions/new` | BR-05 |
| 3 | 서비스 | 과제 없음 | `IllegalArgumentException` | BR-07 |
| 4 | 서비스 | 학급 없음 | `IllegalArgumentException` | BR-07 |
| 5 | 서비스 | `status == "D"` | `IllegalStateException` | BR-08 |
| 6 | 서비스 | 같은 (과제, 학급) 배포가 1건 이상 | `IllegalStateException` | BR-09 |
| 7 | 서비스 | 채번 · INSERT | 영향 행 수 ≠ 1 이면 `IllegalStateException` | BR-10, BR-11, BR-12 |
| 8 | 컨트롤러 | 성공 | flash `message`, `/distributions` | BR-14 |

---

### BR-01
- 규칙: 새 배포 폼의 과제 선택 목록은 `status` 가 `O` · `X` · `C` 인 과제만 마감 시각 오름차순, 같으면 `id` 오름차순으로 보여 준다. `D` 와 그 밖의 값은 목록에 없다.
- 근거: `web/DistributionController.java:42`, `dao/AssignmentDao.java:34-39`
- 근거 코드:
  ```java
  // web/DistributionController.java:42
  model.addAttribute("assignments", assignmentDao.findAllForSelect());
  // dao/AssignmentDao.java:36-37
  + " WHERE a.status IN ('O', 'X', 'C') "
  + " ORDER BY a.due_at ASC, a.id ASC";
  ```
- 확신도: 확실
- 비고: 목록에서 빠진 `D` 과제도 `assignmentId` 를 직접 POST 하면 서버 검사까지 간다(BR-08). 과제 목록 화면의 조건(`status <> 'D'`, `dao/AssignmentDao.java:29`)과 다르다 — `O`·`X`·`C`·`D` 이외의 값이 있으면 두 화면 결과가 달라진다. 비교 대소문자는 BR-15.

### BR-02
- 규칙: 과제 선택지는 마감 시각이 현재 시각보다 이전이고 상태가 `X` 가 아니면 `disabled` 로 그린다. 마감이 지난 선택지 이름 뒤에는 상태가 `X` 면 ` [연장]`, 아니면 ` [마감]` 을 붙인다.
- 근거: `distribution_form.html:14-18`, `web/DistributionController.java:44`
- 근거 코드:
  ```html
  <!-- distribution_form.html:16 -->
  th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
  <!-- distribution_form.html:17-18 -->
  th:text="${a.id} + '. ' + ${a.title} + ' (' + ${a.unitCode} + ', 마감 ' + ${#temporals.format(a.dueAt, 'MM-dd HH:mm')} + ')'
           + (${a.dueAt != null and a.dueAt.isBefore(now)} ? (${a.status == 'X'} ? ' [연장]' : ' [마감]') : '')"
  ```
- 확신도: 확실
- 비고: `now` 는 `AppClock.now()`(BR-06). 마감 시각과 현재 시각이 같으면 `isBefore` 가 거짓이라 선택할 수 있다. 상태 `C` 라도 마감이 지나지 않았으면 선택할 수 있다. 선택지 표시 형식은 `id. 제목 (단원코드, 마감 MM-dd HH:mm)` 이다. `disabled` 는 화면 제한일 뿐이고 서버 검사는 BR-05 다. 템플릿 주석(`distribution_form.html:13`)은 "마감 지난 과제는 선택 불가 (연장 상태 제외)"로 코드와 같다.

### BR-03
- 규칙: 새 배포 폼의 학급 선택 목록은 `class` 전체를 `id` 오름차순으로 보여 주고, 선택지 이름은 `학급명 (teacher_id)` 이다.
- 근거: `web/DistributionController.java:43`, `dao/ClassDao.java:21`, `distribution_form.html:25`
- 근거 코드:
  ```java
  // dao/ClassDao.java:21
  return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);
  ```
  ```html
  <!-- distribution_form.html:25 -->
  <option th:each="c : ${classes}" th:value="${c.id}" th:text="${c.name} + ' (' + ${c.teacherId} + ')'"></option>
  ```
- 확신도: 확실
- 비고: 담당 교사 · 로그인 사용자로 거르는 조건이 없다. 모듈에 인증 의존성이 없다(`build.gradle:24-30`).

### BR-04
- 규칙: 저장 요청의 `assignmentId` 로 과제를 찾지 못하면 flash `error` 에 "존재하지 않는 과제입니다." 를 담아 `/distributions/new` 로 리다이렉트한다.
- 근거: `web/DistributionController.java:53-57`, `dao/AssignmentDao.java:41-48`
- 근거 코드:
  ```java
  // web/DistributionController.java:53-57
  AssignmentRow a = assignmentDao.findById(assignmentId);
  if (a == null) {
      ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");
      return "redirect:/distributions/new";
  }
  ```
- 확신도: 확실
- 비고: `findById` 는 상태 조건 없이 `a.id = ?` 로만 찾는다(`dao/AssignmentDao.java:42`). 그래서 `D` 과제도 여기를 통과한다. 이 조회는 트랜잭션 밖이고 `try` 밖이라 DB 예외는 BR-13 의 `DbErrorAdvice` 로 간다.

### BR-05
- 규칙: 과제의 마감 시각이 있고 현재 시각보다 이전이며 상태가 정확히 `"X"` 가 아니면 배포를 거부하고, flash `error` 에 "마감(yyyy-MM-dd HH:mm)이 지난 과제는 배포할 수 없습니다. [과제 제목]" 을 담아 `/distributions/new` 로 리다이렉트한다.
- 근거: `web/DistributionController.java:58-65`, `AppClock.java:22-27`
- 근거 코드:
  ```java
  // web/DistributionController.java:58-65
  LocalDateTime now = AppClock.now();
  // 마감 지난 과제는 새 배포 불가. 연장(X) 상태만 예외
  if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
      if (!"X".equals(a.getStatus())) {
          ra.addFlashAttribute("error", "마감(" + AppClock.fmt(a.getDueAt()) + ")이 지난 과제는 배포할 수 없습니다. [" + a.getTitle() + "]");
          return "redirect:/distributions/new";
      }
  }
  ```
- 확신도: 확실
- 비고: 판단 기준은 `due_at` 과 `"X"` 뿐이고 상태 `C`(DDL 주석 "마감", `01-schema.sql:87`)는 쓰지 않는다 — `C` 라도 마감 전이면 통과, `O` 라도 마감 후면 거부. 마감 시각 = 현재 시각은 통과(`isBefore`). `due_at` 이 null 이면 통과하지만 DDL 이 `NOT NULL` 이다(`01-schema.sql:86`). 이 검사는 컨트롤러에만 있고 `DistributionService.distribute` 에는 없다(`service/DistributionService.java:46-68`). 마감 지난 `D` 과제는 BR-08 보다 먼저 이 메시지를 받는다. `"X"` 비교는 대소문자를 구분한다(BR-15). `due_at` 은 `DATETIME`(시간대 없음)을 `Timestamp.toLocalDateTime()` 으로 바꾼 값이고(`dao/AssignmentDao.java:59-60`), `now` 는 고정 문자열이거나 JVM 기본 시간대의 `LocalDateTime.now()` 다(`AppClock.java:17,19`). DB 저장 시간대와 컨테이너 JVM 시간대가 같은지는 미확인 — `app.clock=system` 이면 두 값의 기준 시간대가 다를 수 있다.

### BR-06
- 규칙: 화면 · 검사 · 저장에 쓰는 현재 시각은 시스템 속성 `app.clock` 이 정확히 `"system"` 이면 실제 시각(나노초 0), 그 밖에는(속성 없음 포함) 고정값 `2026-09-15 00:00:00` 이다.
- 근거: `AppClock.java:9`, `AppClock.java:14-20`, 호출 `web/DistributionController.java:44,58`, `service/DistributionService.java:63`
- 근거 코드:
  ```java
  // AppClock.java:9
  private static final String FIXED = "2026-09-15 00:00:00";
  // AppClock.java:15-19
  String sys = System.getProperty("app.clock");
  if (sys != null && sys.equals("system")) {
      return LocalDateTime.now().withNano(0);
  }
  return LocalDateTime.parse(FIXED, FMT);
  ```
- 확신도: 확실
- 비고: 컨테이너 실행 명령(`Dockerfile:11`)과 Compose 설정(`docker-compose.yml:50-66`)에 `-Dapp.clock` 이 없어 고정값이 쓰인다. 주석(`AppClock.java:8`)은 "QA 기간 임시 고정, 아직 안 되돌림" — 주석만 있음. 테스트가 고정값을 단언한다(`src/test/java/com/example/assign/AppClockTest.java:12`). 폼 표시(`:44`), 컨트롤러 검사(`:58`), 저장 시각(서비스 `:63`)이 `now()` 를 각각 따로 부른다.

### BR-07
- 규칙: 서비스는 과제를 다시 조회해 없으면 "존재하지 않는 과제입니다. (id=N)", 학급이 없으면 "존재하지 않는 학급입니다. (id=N)" 메시지로 `IllegalArgumentException` 을 던진다.
- 근거: `service/DistributionService.java:48-55`, `web/DistributionController.java:69-71`
- 근거 코드:
  ```java
  // service/DistributionService.java:48-55
  AssignmentRow a = assignmentDao.findById(assignmentId);
  if (a == null) {
      throw new IllegalArgumentException("존재하지 않는 과제입니다. (id=" + assignmentId + ")");
  }
  ClassRow c = classDao.findById(classId);
  if (c == null) {
      throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
  }
  ```
- 확신도: 확실
- 비고: 과제 없음은 컨트롤러가 먼저 걸러서(BR-04) 서비스의 과제 메시지는 두 조회 사이에 행이 사라질 때만 나온다. 학급 존재 검사는 서비스에만 있다. 메시지는 컨트롤러가 flash `error` 로 그대로 보낸다(BR-13).

### BR-08
- 규칙: 과제 상태가 정확히 `"D"` 면 "삭제된 과제는 배포할 수 없습니다." 로 `IllegalStateException` 을 던진다.
- 근거: `service/DistributionService.java:56-58`
- 근거 코드:
  ```java
  if ("D".equals(a.getStatus())) {
      throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  }
  ```
- 확신도: 확실
- 비고: 학급 검사(BR-07) 다음, 중복 검사(BR-09) 앞이다. 마감이 지난 `D` 과제는 컨트롤러 BR-05 에서 먼저 막힌다. 폼 목록에는 `D` 가 나오지 않는다(BR-01). DDL 주석에는 `D` 가 없다(`01-schema.sql:87`).

### BR-09
- 규칙: 같은 과제 · 같은 학급의 `distribution` 행이 1건이라도 있으면(재배포 여부 무관) "이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요." 로 `IllegalStateException` 을 던진다.
- 근거: `service/DistributionService.java:59-61`, `dao/DistributionDao.java:47-52`
- 근거 코드:
  ```java
  // service/DistributionService.java:59-61
  if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
      throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
  }
  // dao/DistributionDao.java:48-50
  Integer n = jdbc.queryForObject(
          "SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?",
          Integer.class, assignmentId, classId);
  ```
- 확신도: 확실
- 비고: `redistributed` · 과제 상태를 조건에 넣지 않는다. DB 에 UNIQUE 제약이 없고(`01-schema.sql:99-100`, DAO 주석 `dao/DistributionDao.java:46` 도 같은 말) 조회에 잠금이 없다. 동시 요청 두 건이 모두 0 을 읽으면 중복 행이 생길 수 있다 — 미확인(실행하지 않음). 시드에 이미 (과제 2, 학급 1) 이 2건 있다(`02-seed.sql:133-134`).

### BR-10
- 규칙: 새 배포 번호는 `distribution` 의 `MAX(id)`(없으면 0) + 1 이다.
- 근거: `service/DistributionService.java:62`, `dao/DistributionDao.java:54-57`
- 근거 코드:
  ```java
  // dao/DistributionDao.java:55-56
  Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
  return (max == null ? 0L : max.longValue()) + 1L;
  ```
- 확신도: 확실
- 비고: `FOR UPDATE` · 채번 테이블 · AUTO_INCREMENT 가 없다(`01-schema.sql:93`). 동시 요청이 같은 번호를 받으면 두 번째 INSERT 가 PK 중복으로 실패해 BR-13 의 "DB 오류" 메시지로 끝날 것으로 보이나 미확인(실행하지 않음).

### BR-11
- 규칙: 배포 행은 `distributed_at = AppClock.now()`, `redistributed = 0` 으로 INSERT 한다.
- 근거: `service/DistributionService.java:63`, `dao/DistributionDao.java:59-63`
- 근거 코드:
  ```java
  // service/DistributionService.java:63
  int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());
  // dao/DistributionDao.java:61
  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",
  ```
- 확신도: 확실
- 비고: 고정 시계(BR-06)면 모든 새 배포의 배포 시각이 `2026-09-15 00:00:00` 이 된다. 이 화면에는 로그 기록 · 이력 테이블 쓰기가 없다.

### BR-12
- 규칙: INSERT 영향 행 수가 1 이 아니면 "배포 저장에 실패했습니다." 로 `IllegalStateException` 을 던진다. 성공하면 새 배포 번호를 돌려준다.
- 근거: `service/DistributionService.java:63-67`
- 근거 코드:
  ```java
  if (n != 1) {
      throw new IllegalStateException("배포 저장에 실패했습니다.");
  }
  return id;
  ```
- 확신도: 확실(분기) / 추정(롤백)
- 비고: `distribute` 는 `@Transactional`(`service/DistributionService.java:46`)이고 롤백 규칙 지정(`rollbackFor` 등)이 없다. Spring 기본값(런타임 예외 시 롤백)에 따라 이 예외와 BR-07~09 의 예외, INSERT 중 `DataAccessException` 은 롤백될 것으로 추정한다. 트랜잭션 안의 쓰기는 INSERT 한 문장뿐이라 롤백 대상도 그 한 문장이다. 단일 행 INSERT 가 예외 없이 1 이 아닌 값을 돌려주는 경우는 미확인.

### BR-13
- 규칙: 저장 요청의 예외는 위치에 따라 세 갈래로 처리한다. (1) `distribute` 가 던진 `IllegalArgumentException` · `IllegalStateException` 은 메시지를 그대로 flash `error` 에 담아 `/distributions/new` 로, (2) `distribute` 안의 `DataAccessException` 은 고정 문구 "DB 오류로 배포에 실패했습니다." 로 `/distributions/new` 로, (3) `try` 밖(컨트롤러 `findById`)이나 폼 조회의 `DataAccessException` 은 `DbErrorAdvice` 가 `db_error` 화면에 원인 메시지를 보여 준다.
- 근거: `web/DistributionController.java:66-75`, `web/DistributionController.java:53`, `web/DbErrorAdvice.java:8-16`, `db_error.html:8`, `service/DistributionService.java:46`
- 근거 코드:
  ```java
  // web/DistributionController.java:66-75
  try {
      long id = distributionService.distribute(assignmentId, classId);
      ra.addFlashAttribute("message", "배포가 등록되었습니다. (배포 #" + id + ")");
  } catch (IllegalArgumentException | IllegalStateException e) {
      ra.addFlashAttribute("error", e.getMessage());
      return "redirect:/distributions/new";
  } catch (DataAccessException e) {
      ra.addFlashAttribute("error", "DB 오류로 배포에 실패했습니다.");
      return "redirect:/distributions/new";
  }
  // web/DbErrorAdvice.java:13-16
  System.out.println("[DB-ERROR] " + e.getClass().getSimpleName() + " : " + e.getMostSpecificCause().getMessage());
  model.addAttribute("menu", "");
  model.addAttribute("detail", e.getMostSpecificCause().getMessage());
  return "db_error";
  ```
- 확신도: 확실(세 갈래 분기) / 추정(아래 트랜잭션 시작 실패 · HTTP 상태)
- 비고: **잡히지 않는 DB 오류가 있다.** `distribute` 는 `@Transactional` 이라 메서드 본문 전에 트랜잭션을 열며 커넥션을 얻는다. 이때 실패(풀 고갈 — 최대 4, 대기 5초, `application.properties:6-7`)하면 `CannotCreateTransactionException` 이 나는데, 이 클래스는 `TransactionException` → `NestedRuntimeException` 계열이고 `DataAccessException` 이 아니다(spring-tx 6.1 `javap` 로 확인). 그래서 (2) 의 `catch` 에도, `DbErrorAdvice`(`web/DbErrorAdvice.java:11`)에도 걸리지 않는다. 커밋 실패(`TransactionSystemException`)도 같다. 반면 트랜잭션 없는 조회(폼 · 컨트롤러 `findById`)의 커넥션 실패는 `CannotGetJdbcConnectionException`(`DataAccessException` 하위)이라 (3) 으로 간다. 이 경우의 실제 응답은 미확인(실행하지 않음). `DbErrorAdvice` 에 `@ResponseStatus` 가 없어(`web/DbErrorAdvice.java:8-16`) `db_error` 화면은 HTTP 200 으로 나갈 것으로 추정한다. (1) · (2) 는 로그를 남기지 않는다. (3) 은 `System.out` 으로만 남기고, DB 원인 메시지를 화면(`db_error.html:8`)에 그대로 노출한다. 그 밖의 예외(파라미터 누락 · 형식 오류 포함)를 처리하는 코드는 이 모듈에 없어 응답은 미확인. `DataAccessException` 이 아닌 런타임 예외가 `distribute` 에서 나오면 잡히지 않는다.

### BR-14
- 규칙: 배포가 성공하면 flash `message` 에 "배포가 등록되었습니다. (배포 #번호)" 를 담아 `/distributions`(배포 목록)로 리다이렉트한다.
- 근거: `web/DistributionController.java:67-68`, `web/DistributionController.java:76`, `layout.html:29`
- 근거 코드:
  ```java
  // web/DistributionController.java:67-68
  long id = distributionService.distribute(assignmentId, classId);
  ra.addFlashAttribute("message", "배포가 등록되었습니다. (배포 #" + id + ")");
  // web/DistributionController.java:76
  return "redirect:/distributions";
  ```
- 확신도: 확실
- 비고: 메시지는 `layout :: flash` 가 `th:text` 로 출력한다(`layout.html:29-30`). 실패 메시지도 같은 프래그먼트의 `.err` 로 나온다.

### BR-15
- 규칙: 과제 상태 비교는 SQL 쪽은 대소문자를 구분하지 않고 Java · 템플릿 쪽은 구분한다. 예를 들어 상태가 소문자 `x` 인 과제는 선택 목록에 나오지만 연장으로 취급되지 않는다.
- 근거: `dao/AssignmentDao.java:36`, `01-schema.sql:87`, `01-schema.sql:90`, `distribution_form.html:16`, `web/DistributionController.java:61`, `service/DistributionService.java:56`
- 근거 코드:
  ```java
  // dao/AssignmentDao.java:36
  + " WHERE a.status IN ('O', 'X', 'C') "
  // web/DistributionController.java:61
  if (!"X".equals(a.getStatus())) {
  // service/DistributionService.java:56
  if ("D".equals(a.getStatus())) {
  ```
  ```sql
  -- 01-schema.sql:90
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
  ```
- 확신도: 추정
- 비고: `status` 컬럼에는 컬럼 단위 `COLLATE` 가 없어(`01-schema.sql:87`) 테이블 기본값 `utf8mb4_unicode_ci`(`01-schema.sql:90`)를 따른다. `_ci` 콜레이션의 비교 동작과 템플릿 `!=` 의 문자열 비교를 실행해 확인하지 않았다. 시드에는 소문자 상태가 없다(`02-seed.sql:119-125`). 소문자 `d` 과제도 목록에서는 빠지지만(`IN` 에 없음) 직접 POST 하면 BR-08 에 걸리지 않는다.

### BR-16
- 규칙: 과제 · 학급 선택은 HTML `required` 와 빈 값 기본 선택지로 브라우저에서 필수 입력으로 막는다.
- 근거: `distribution_form.html:11-12`, `distribution_form.html:23-24`, `web/DistributionController.java:50-51`
- 근거 코드:
  ```html
  <!-- distribution_form.html:11-12 -->
  <select id="assignmentId" name="assignmentId" required>
      <option value="">-- 과제 선택 --</option>
  <!-- distribution_form.html:23-24 -->
  <select id="classId" name="classId" required>
      <option value="">-- 학급 선택 --</option>
  ```
- 확신도: 확실(화면) / 추정(서버)
- 비고: 서버는 `@RequestParam("assignmentId") long` · `@RequestParam("classId") long` 선언뿐이고 직접 검증 코드는 없다(`web/DistributionController.java:50-51`). 빈 값 · 숫자 아님 요청의 서버 응답은 미확인.

---

## 검증 이력

| 날짜 | 규칙 | 조치 | 내용 |
|---|---|---|---|
| 2026-09-29 | BR-05 | 비고 보강 | Claude 재검토(사람 점검 아님): `due_at` 변환 경로(`dao/AssignmentDao.java:59-60`)와 DB · JVM 시간대 일치 여부(미확인)를 빠뜨렸음 |
| 2026-09-29 | BR-13 | 규칙 정정 · 확신도 분리 | Claude 재검토(사람 점검 아님): `@Transactional` 시작 시 커넥션 획득 실패(`CannotCreateTransactionException`)는 `DataAccessException` 이 아니라 어느 핸들러에도 안 걸림을 빠뜨렸음. `db_error` 응답 상태(200 추정) 추가 |
| 2026-09-29 | BR-15 | 근거 보강 | Claude 재검토(사람 점검 아님): 테이블 기본 콜레이션만 인용하고 컬럼 단위 `COLLATE` 부재(`01-schema.sql:87`)를 확인하지 않았음 |
| | | | |
