# legacy/assignment-thymeleaf ERD — 새 배포 화면

> 분석 범위: **"새 배포" 화면 하나**(`GET /distributions/new` · `POST /distributions`)가 읽고 쓰는 테이블만 다룬다.
> 근거는 `파일:줄번호`로 적는다. 경로는 `legacy/assignment-thymeleaf/` 기준이고 Java 파일은 `src/main/java/com/example/assign/` 아래 경로로 줄여 쓴다. 스키마 · 시드는 저장소 루트의 `db/mariadb/init/` 기준이다.
> 코드를 읽기만 했고 실행해 확인하지 않았다. 직접 열어 확인하지 못한 것은 "미확인"으로 둔다.

## 1. 테이블 목록

모든 테이블은 InnoDB, `utf8mb4_unicode_ci` 콜레이션이다(`01-schema.sql:5`, `:18`, `:80`, `:90`, `:103`).

| 테이블 | 컬럼 (타입 · 제약) | 범위 안 사용 | 근거 |
|---|---|---|---|
| `assignment` | `id INT PK`(자동 증가 아님) · `title VARCHAR(200) NN` · `unit_id INT NN FK→unit` · `due_at DATETIME NN` · `status CHAR(1) NN DEFAULT 'O'` | 읽기 | `01-schema.sql:82-90` |
| `unit` | `id INT PK` · `code VARCHAR(16) NN UNIQUE` · `name VARCHAR(100) NN` · `grade TINYINT NN` | 읽기(`assignment` 과 조인) | `01-schema.sql:11-18` |
| `class` | `id INT PK` · `name VARCHAR(50) NN` · `teacher_id VARCHAR(20) NN` | 읽기 | `01-schema.sql:75-80` |
| `distribution` | `id INT PK`(자동 증가 아님) · `assignment_id INT NN FK→assignment` · `class_id INT NN FK→class` · `distributed_at DATETIME NN` · `redistributed TINYINT NN DEFAULT 0` | 읽기 · **쓰기(INSERT)** | `01-schema.sql:92-103` |

- `assignment.status` 의 DDL 주석은 `O=진행 C=마감` 둘만 적는다(`01-schema.sql:87`). 코드는 그 밖에 `X`(`web/DistributionController.java:61`, `distribution_form.html:16`) · `D`(`service/DistributionService.java:56`)를 쓴다. CHECK 제약은 없다.
- `distribution` 에는 `(assignment_id, class_id)` UNIQUE 제약이 없고, 두 컬럼에 각각 일반 인덱스만 있다(`01-schema.sql:99-100`). 중복 방지는 코드가 한다(`dao/DistributionDao.java:46-52`).
- `submission` 테이블(`01-schema.sql:105-115`)은 `distribution` 을 참조하지만 새 배포 화면은 읽지도 쓰지도 않는다. 다만 과제 선택 목록 SQL 의 `class_cnt` 서브쿼리가 `distribution` 을 센다(아래 3.1).

## 2. 관계

```
unit (1) ──< assignment (N)          fk_assignment_unit          01-schema.sql:89
assignment (1) ──< distribution (N)  fk_distribution_assignment  01-schema.sql:101
class (1) ──< distribution (N)       fk_distribution_class       01-schema.sql:102
distribution (1) ──< submission (N)  fk_submission_distribution  01-schema.sql:114   (범위 밖)
```

- `assignment` ↔ `class` 는 `distribution` 을 통한 N:M 이다. 코드는 같은 (과제, 학급) 쌍을 한 건만 허용하려 하지만 DB 는 막지 않는다(1절).

## 3. 진입점별 읽기 / 쓰기

### 3.1 `GET /distributions/new`

트랜잭션 선언 없음(`web/DistributionController.java:40-47`).

| 순서 | 호출 | SQL 종류 | 테이블 · 컬럼 | 조건 · 정렬 | 근거 |
|---|---|---|---|---|---|
| 1 | `AssignmentDao.findAllForSelect` | SELECT | `assignment`(id, title, unit_id, due_at, status) LEFT JOIN `unit`(code, name) + 서브쿼리 `COUNT(DISTINCT distribution.class_id)` | `a.status IN ('O','X','C')`, `ORDER BY a.due_at ASC, a.id ASC` | `dao/AssignmentDao.java:21-24`, `:34-39` |
| 2 | `ClassDao.findAll` | SELECT | `class`(id, name, teacher_id) | 조건 없음, `ORDER BY id ASC` | `dao/ClassDao.java:20-22` |

- 서브쿼리 결과 `class_cnt` 는 `AssignmentRow.classCount` 에 담기지만(`dao/AssignmentDao.java:62`) 폼 템플릿은 쓰지 않는다(`distribution_form.html:14-18` 에 `classCount` 없음).

### 3.2 `POST /distributions`

| 순서 | 호출 | 트랜잭션 | SQL 종류 | 테이블 · 컬럼 | 조건 | 근거 |
|---|---|---|---|---|---|---|
| 1 | 컨트롤러 `AssignmentDao.findById` | 없음 | SELECT | `assignment` + `unit` + `distribution` 서브쿼리 | `a.id = ?` | `web/DistributionController.java:53`, `dao/AssignmentDao.java:41-48` |
| 2 | 서비스 `AssignmentDao.findById` | `distribute` | SELECT | 위와 같음 | `a.id = ?` | `service/DistributionService.java:48` |
| 3 | `ClassDao.findById` | `distribute` | SELECT | `class` | `id = ?` | `service/DistributionService.java:52`, `dao/ClassDao.java:24-30` |
| 4 | `DistributionDao.countByAssignmentAndClass` | `distribute` | SELECT COUNT(*) | `distribution` | `assignment_id = ? AND class_id = ?` (재배포 여부 무관) | `service/DistributionService.java:59`, `dao/DistributionDao.java:47-52` |
| 5 | `DistributionDao.nextId` | `distribute` | SELECT `COALESCE(MAX(id),0)` | `distribution` | 조건 · 잠금(`FOR UPDATE`) 없음 | `service/DistributionService.java:62`, `dao/DistributionDao.java:54-57` |
| 6 | `DistributionDao.insert` | `distribute` | **INSERT** | `distribution`(id, assignment_id, class_id, distributed_at, redistributed=0) | — | `service/DistributionService.java:63`, `dao/DistributionDao.java:59-63` |

- 트랜잭션 경계: `DistributionService.distribute` 메서드 전체(`service/DistributionService.java:46-68`). 컨트롤러의 1번 조회는 경계 밖이다.
- 쓰기는 6번 INSERT 한 문장뿐이다. `distributed_at` 에는 `AppClock.now()` 값이 들어간다(`service/DistributionService.java:63`). `app.clock=system` 이 없으면 `2026-09-15 00:00:00` 이다(`AppClock.java:9,14-20`).
- 모든 SQL 은 `?` 파라미터 바인딩이다. 문자열 연결은 상수 SQL 조각끼리만 한다(`dao/AssignmentDao.java:35-37,42`).
- 격리 수준 설정이 없어 4 · 5번 조회와 6번 INSERT 사이에 다른 요청이 같은 행을 넣을 수 있다. 실제 격리 수준 · 동시 요청 결과는 미확인(실행하지 않음).

## 4. 시드 데이터 (참고)

- 학급 3개(`02-seed.sql:111-114`), 과제 6개 — 상태 `C` 2개 · `O` 4개, `X` · `D` 없음(`02-seed.sql:119-125`), 배포 8건(`02-seed.sql:130-138`).
- 고정 시각 `2026-09-15 00:00:00` 기준으로 과제 1 · 2 · 3 은 마감이 지났다(`02-seed.sql:120-122`). 이미 있는 (과제, 학급) 쌍은 (1,1) · (1,2) · (2,1) · (3,3) · (4,3) · (5,3) · (6,2) 다(`02-seed.sql:131-138`).
