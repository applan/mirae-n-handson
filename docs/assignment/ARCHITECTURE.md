# legacy/assignment-thymeleaf 아키텍처 — 새 배포 화면

> 분석 범위: **"새 배포" 화면 하나**(`GET /distributions/new` 폼 · `POST /distributions` 저장)와 이 화면이 부르는 코드까지만 다룬다.
> 배포 목록(`GET /distributions`) · 재배포(`POST /distributions/{id}/redistribute`) · 과제 목록(`GET /assignments`)은 범위 밖이다.
> 근거는 `파일:줄번호`로 적는다. 경로는 `legacy/assignment-thymeleaf/` 기준이고, Java 파일은 `src/main/java/com/example/assign/` 아래, 템플릿은 `src/main/resources/templates/` 아래 경로로 줄여 쓴다. `db/…` · `docker-compose.yml` 은 저장소 루트 기준이다.
> 코드를 읽기만 했고 실행해 확인하지 않았다. 직접 열어 확인하지 못한 것은 "미확인"으로 둔다.

## 1. 모듈 개요

- Spring Boot 3.3.4 · Java 17 · Thymeleaf 서버 렌더링 모듈이다(`build.gradle:3,11-12,24-30`).
- DB 접근은 JPA 없이 `JdbcTemplate` 과 직접 쓴 SQL 이다(`build.gradle:27`, `dao/AssignmentDao.java:15,21-24`).
- MariaDB `itembank` 를 문항 은행 모듈과 함께 쓴다(`src/main/resources/application.properties:2`, `db/mariadb/init/01-schema.sql:73-74`).
- Spring Security 등 인증 의존성이 없다(`build.gradle:24-30`). 새 배포 화면에 로그인 · 권한 검사가 없다.
- 현재 시각은 `AppClock.now()` 로 얻는데, 시스템 속성 `app.clock=system` 이 없으면 `2026-09-15 00:00:00` 고정값을 돌려준다(`AppClock.java:9,14-20`). `Dockerfile:11` · `docker-compose.yml:50-66` 에는 이 속성이 없다.
- 컨테이너는 `thymeleaf` Compose 프로필, 호스트 포트 8082 로 뜬다(`docker-compose.yml:50-54`).

## 2. 폴더 구조 (범위 안 파일만)

```
legacy/assignment-thymeleaf/
├── build.gradle                                   의존성 (web, thymeleaf, jdbc, mariadb)
├── Dockerfile                                     java -jar 실행 (app.clock 속성 없음)
└── src/main/
    ├── java/com/example/assign/
    │   ├── AppClock.java                  28줄    now() 고정 시각, fmt()
    │   ├── web/
    │   │   ├── DistributionController.java 101줄  form() · create()  ← 범위의 진입점
    │   │   └── DbErrorAdvice.java          18줄   DataAccessException → db_error 화면
    │   ├── service/
    │   │   └── DistributionService.java   701줄   distribute() 46-68 만 범위
    │   ├── dao/
    │   │   ├── AssignmentDao.java          66줄   findAllForSelect(), findById()
    │   │   ├── ClassDao.java               42줄   findAll(), findById()
    │   │   └── DistributionDao.java       108줄   countByAssignmentAndClass(), nextId(), insert()
    │   └── model/
    │       ├── AssignmentRow.java          79줄
    │       └── ClassRow.java               32줄
    └── resources/
        ├── application.properties          14줄   DataSource · Hikari(최대 4, 대기 5초)
        └── templates/
            ├── distribution_form.html      34줄   새 배포 폼
            ├── layout.html                 37줄   head · nav · flash · footer 프래그먼트
            └── db_error.html               11줄   DB 오류 화면
```

## 3. 진입점

| 요청 | 핸들러 | 뷰 / 응답 | 근거 |
|---|---|---|---|
| `GET /distributions/new` | `DistributionController.form(Model)` | `distribution_form` 렌더링 | `web/DistributionController.java:40-47` |
| `POST /distributions` (form: `assignmentId`, `classId`) | `DistributionController.create(long, long, RedirectAttributes)` | 성공 `redirect:/distributions`, 실패 `redirect:/distributions/new` + flash `error` | `web/DistributionController.java:49-77` |

- 폼은 `method="post"`, `th:action="@{/distributions}"` 이고 `assignmentId` · `classId` 두 `select` 를 보낸다(`distribution_form.html:8,11,23`). 둘 다 HTML `required` 다(`distribution_form.html:11,23`).
- 두 요청 파라미터는 `@RequestParam` 에 `required` 지정이 없어 기본값(필수)이고 타입은 `long` 이다(`web/DistributionController.java:50-51`). 값이 없거나 숫자가 아닐 때의 응답은 미확인(실행하지 않음, 이 모듈에 해당 예외 핸들러 없음 — `web/DbErrorAdvice.java:11` 은 `DataAccessException` 만 처리).
- 폼 화면 모델 속성: `assignments`, `classes`, `now`, `menu="new"`(`web/DistributionController.java:42-45`).
- 공통 레이아웃: `head` · `nav` · `flash` · `footer` 프래그먼트를 `th:replace` 로 끼운다(`distribution_form.html:3,5,7,32`, `layout.html:3,23-27,28-34,35`). `flash` 는 `message` · `error` · `warnings` 를 보여 준다(`layout.html:29-33`).
- 전역 예외 처리: `@ControllerAdvice` `DbErrorAdvice` 가 `DataAccessException` 을 잡아 `db_error` 뷰를 돌려준다(`web/DbErrorAdvice.java:8,11-16`).

## 4. 의존 관계

### 4.1 호출 그래프

```
GET /distributions/new
  DistributionController.form                         web/DistributionController.java:41
    ├─ AssignmentDao.findAllForSelect()               web/DistributionController.java:42 → dao/AssignmentDao.java:34-39
    ├─ ClassDao.findAll()                             web/DistributionController.java:43 → dao/ClassDao.java:20-22
    ├─ AppClock.now()                                 web/DistributionController.java:44 → AppClock.java:14-20
    └─ view distribution_form                         web/DistributionController.java:46
         └─ layout :: head / nav / flash / footer     distribution_form.html:3,5,7,32

POST /distributions
  DistributionController.create                       web/DistributionController.java:50
    ├─ AssignmentDao.findById(assignmentId)           web/DistributionController.java:53 → dao/AssignmentDao.java:41-48
    ├─ AppClock.now()                                 web/DistributionController.java:58
    ├─ AppClock.fmt(dueAt)  (마감 오류 메시지)          web/DistributionController.java:62 → AppClock.java:22-27
    └─ DistributionService.distribute(a, c)  @Transactional
                                                      web/DistributionController.java:67 → service/DistributionService.java:46-68
         ├─ AssignmentDao.findById                    service/DistributionService.java:48
         ├─ ClassDao.findById                         service/DistributionService.java:52 → dao/ClassDao.java:24-30
         ├─ DistributionDao.countByAssignmentAndClass service/DistributionService.java:59 → dao/DistributionDao.java:47-52
         ├─ DistributionDao.nextId                    service/DistributionService.java:62 → dao/DistributionDao.java:54-57
         ├─ AppClock.now()                            service/DistributionService.java:63
         └─ DistributionDao.insert                    service/DistributionService.java:63 → dao/DistributionDao.java:59-63

(예외) DataAccessException 이 컨트롤러 밖으로 나가면
  DbErrorAdvice.dbError → view db_error              web/DbErrorAdvice.java:11-16 → db_error.html
```

### 4.2 계층 · 주입

- `DistributionController` 는 `DistributionService` 와 함께 `AssignmentDao` · `ClassDao` 를 직접 주입받는다(`web/DistributionController.java:22-30`). 폼 목록 조회(`:42-43`)와 저장 전 과제 조회(`:53`)는 서비스를 거치지 않고 DAO 를 바로 부른다.
- `DistributionService` 는 `DistributionDao` · `AssignmentDao` · `ClassDao` 를 주입받는다(`service/DistributionService.java:28-36`).
- 세 DAO 는 모두 `@Repository` 이고 `JdbcTemplate` 하나만 주입받는다(`dao/AssignmentDao.java:12-19`, `dao/ClassDao.java:11-18`, `dao/DistributionDao.java:14-21`).
- `AppClock` 은 빈이 아니라 static 메서드 유틸이다(`AppClock.java:6,14,22`).

### 4.3 외부 라이브러리 · DB 접속 지점

| 대상 | 내용 | 근거 |
|---|---|---|
| `spring-boot-starter-web` · `-thymeleaf` · `-jdbc` | MVC · 템플릿 · `JdbcTemplate` | `build.gradle:25-27` |
| `mariadb-java-client` | JDBC 드라이버 | `build.gradle:28`, `application.properties:5` |
| DataSource | URL · 계정은 환경변수, 없으면 `localhost:3306/itembank` · `app` · `app-pass` | `application.properties:2-4` |
| Hikari | 최대 풀 4, 커넥션 대기 5000ms, 초기화 실패 대기 없음(-1) | `application.properties:6-8` |
| 트랜잭션 | `DistributionService.distribute` 에만 `@Transactional`. 폼 조회와 컨트롤러의 `findById` 에는 트랜잭션 선언이 없다 | `service/DistributionService.java:46`, `web/DistributionController.java:40-53` |

- 트랜잭션 매니저 · 격리 수준 설정 코드는 없다(`isolation` · `readOnly` 검색 결과 없음). 실제 매니저 종류와 격리 수준은 미확인(Spring Boot 자동 구성 · DB 기본값에 따름, 실행하지 않음).

### 4.4 로깅

- 범위 안 코드의 로그는 `DbErrorAdvice` 의 `System.out.println` 한 곳뿐이다. 예외 클래스 이름과 가장 안쪽 원인 메시지를 출력한다(`web/DbErrorAdvice.java:13`).
- `create` 가 직접 잡는 예외(`web/DistributionController.java:69-75`)는 로그 없이 flash 메시지로만 바뀐다.
