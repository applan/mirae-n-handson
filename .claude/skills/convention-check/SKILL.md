---
name: convention-check
description: 변경 파일(또는 지정 경로)이 CLAUDE.md 코딩 컨벤션 · 금지 사항 중 코드로 판정 가능한 8개 항목을 지키는지 점검하고 위반을 파일:줄번호로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 곳 있어?" 같은 요청이나 커밋 · PR 직전에 사용한다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일 전체)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

- 코드를 직접 고치지 않는다. 판정과 수정 방향만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기

- `$ARGUMENTS` 가 있으면 그 경로(파일 또는 폴더)만 점검한다. 폴더는 Glob 으로 파일을 펼친다.
- 없으면 `git status --porcelain` 과 `git diff --name-only HEAD` 로 변경 파일을 모은다.
  `??`(아직 add 하지 않은 새 파일)도 포함하고, 삭제된 파일(`D`)은 제외한다.
- 기존 파일은 `git diff -U0 HEAD -- <파일>` 의 추가 · 변경 줄만 판정한다. 새 파일은 전체를 판정한다.
- 대상이 없으면 "점검 대상 없음"만 출력하고 끝낸다.

## 2. 점검 항목 (각각 예 / 아니오로 판정)

| # | 항목 | 위반 조건 |
|---|---|---|
| R1 | 컨트롤러 계층 분리 | `*Controller` 가 `*Repository` 타입을 필드 · 생성자 · 메서드 인자로 받거나 SQL · JPQL 문자열을 담는다 |
| R2 | 도메인 간 리포지토리 | `*Service` 가 자기 패키지가 아닌 `com.example.<도메인>` 의 `*Repository` 를 주입받는다 |
| R3 | 파라미터 바인딩 | `@Query` · `createQuery` · `createNativeQuery` 문자열을 `+` · `format` · `formatted` 로 만든다 |
| R4 | 문항 상태 리터럴 | `src/main` 에서 상태 값으로 `"A"` · `"D"` · `"R"` 리터럴을 쓴다(`ItemStatus` 상수를 써야 함) |
| R5 | 현재 시각 | `*Service` 에 인자 없는 `LocalDateTime.now()` 가 있다(주입받은 `Clock` 을 써야 함) |
| R6 | 빈 catch | 본문이 비어 있거나 주석만 있는 `catch` 블록이 있다 |
| R7 | 로깅 수단 | `System.out` · `System.err` · `printStackTrace()` 가 있다 |
| R8 | 승인 필요 변경 | 변경 파일에 `legacy/`, `modern/api/build.gradle` 의 `dependencies`, `modern/web/package.json`, `db/` 아래 SQL 이 있다 |

- R1~R7 은 `.java` 파일에만 적용한다. 테스트 코드(`src/test`)는 R4 · R5 판정에서 제외한다.
- R8 은 위반이 아니라 "사람 승인 필요"로 판정한다.
- Grep 으로 후보를 찾은 뒤 Read 로 해당 줄 앞뒤를 읽고 확정한다. 주석 · 문자열 안의 단어만 걸린 경우는 위반이 아니다.
- 규칙 해석이 애매하면(예: `"A"` 가 상태 값인지 불분명) "확인 필요"로 둔다.

## 3. 출력 형식 (순서 고정)

### 판정 요약

- 점검 대상: 파일 N개 (지정 경로 / git 변경 파일)
- 항목별 결과를 한 줄씩 적는다: `R1 통과` · `R3 위반 2건` · `R8 사람 승인 필요 1건` · `R5 해당 파일 없음`
- 마지막 줄: `위반 N건 · 승인 필요 N건 · 확인 필요 N건`

### 위반 목록

| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/.../ItemController.java:42` | R1 컨트롤러 계층 분리 | 조회를 `ItemService` 메서드로 옮기고 컨트롤러는 서비스만 주입 |

- 위반 · 승인 필요 · 확인 필요를 모두 이 표에 적고, 확인 필요는 규칙 칸에 `(확인 필요)`를 붙인다.
- 위반이 없으면 표 대신 "위반 없음"만 적는다.
