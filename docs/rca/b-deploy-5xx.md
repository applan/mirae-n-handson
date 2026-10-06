# 장애 원인 분석: b-deploy-5xx

대상 로그: `incident-logs/b-deploy-5xx/` (2026-09-17 09:00 ~ 14:39 KST)

> **작성 범위:** 요청에 따라 "1. 수집 범위", "2. 타임라인", "3. 가설과 검증"의 가설 표까지만 작성했다.
> 가설 판정(검증 실행) · 코드 대조 · 요약 · 4절 이후(원인 · 수정안 · 모니터링)는 쓰지 않았다.

### 읽는 법 — 역할 별칭

| 별칭 | 가리키는 것 | 근거 |
|---|---|---|
| `API-1` | 배포 대상 호스트 `api-1` | `deploy-history.md:3` |
| `API-2` | 대기 호스트 `api-2` (평소 정지) | `deploy-history.md:3` |
| `교사단말A` | 학급 1 · 2 · 3 의 배포 `1` `2` `3` `8` `41` `42` `43` `44` 재배포를 보낸 단말. 500 응답 46건 전부의 요청자 | `nginx-access.log` 집계(1.2) |
| `교사단말B` | 학급 3 의 배포 `5` `6` `7` 재배포를 보낸 단말 | `nginx-access.log` 집계(1.2) |
| `프로브` | `kube-probe/1.29` 헬스체크 클라이언트 (`GET /`) | `nginx-access.log:5` |

그 밖의 IP 는 앞 두 옥텟만 남긴다(`10.20.x.x`). 학생 식별자는 리포트 등장 순서대로 `학생A` · `학생B` · `학생C` 로 바꿨다.
부록 · 본문의 명령 안 값도 치환했으므로, IP · 학생 식별자가 들어간 명령은 그대로 다시 실행할 수 없다.

## 1. 수집 범위

> 이 절은 범위만 정한다. 원인 추정은 하지 않는다.
> 모든 수치는 `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 집계로 얻었고, 로그 파일 전체를 열어 읽지 않았다(`deploy-history.md` 39줄만 전체를 읽었다).

### 1.1 파일별 기록 기간 · 형식 · 타임존

| 파일 | 줄 수 | 기록 기간 (원문) | 기록 기간 (KST) | 시각 형식 (예) | 타임존 |
|---|---:|---|---|---|---|
| `app.log` | 2,938 (타임스탬프로 시작하는 줄 1,275 · 스택 트레이스 등 이어지는 줄 1,663) | `2026-09-17T09:00:04.878+09:00` ~ `2026-09-17T14:39:57.905+09:00` | 09:00:04 ~ 14:39:57 | ISO-8601, 밀리초, 오프셋 포함 | KST (+09:00, 1,275줄 모두 동일) |
| `nginx-access.log` | 20,365 | `[17/Sep/2026:09:00:00 +0900]` ~ `[17/Sep/2026:14:39:58 +0900]` | 09:00:00 ~ 14:39:58 | Nginx combined, 초 단위 | KST (+0900, 전 줄 동일) |
| `deploy-history.md` | 39 | 2026-09-01 ~ 2026-09-17 (배포 4건) | 같음 | `YYYY-MM-DD HH:MM:SS`, 초 단위 | **표기 없음 → 문서 선언으로 KST** (아래 근거) |

타임존 판정 근거:

- `deploy-history.md`: 각 행에 오프셋이 없지만 `deploy-history.md:3` 에 "시각은 모두 KST(한국 표준시)" 라고 적혀 있다. 다른 파일과도 맞춰 보았다.
  - v1.4.2 배포 시작 13:40:05 · 완료 13:42:31(`deploy-history.md:7`) 사이에 `app.log` 의 종료 · 기동 줄이 들어간다: `Commencing graceful shutdown` 13:41:48.214(`app.log:1240`), `Starting ItemBankApplication v1.4.2` 13:41:55.902(`app.log:1245`), `Started ItemBankApplication` 13:42:00.304(`app.log:1264`).
  - 같은 구간에 `nginx-access.log` 의 502 8건이 13:41:51 ~ 13:41:59 에 몰려 있다(`nginx-access.log:16865` ~ `16872`).
  - 배포 이력이 UTC 였다면 사건은 22:40 KST 이후여야 하는데 두 로그는 14:39 에 끝난다. 따라서 KST 로 판정한다.
- `deploy-history.md` 는 사람이 적은 문서라 초 단위 시각이 수동 기록일 수 있다. 앱 기동 완료(13:42:00)와 "배포 완료"(13:42:31) 사이 31초는 파이프라인 후속 단계로 보이나 로그로 확인 불가.

KST 변환 규칙: 세 파일 모두 변환 없음.

### 1.2 분 단위 이상 신호 집계

집계 대상 정의:

| 파일 | 센 줄 |
|---|---|
| `app.log` | 타임스탬프로 시작하는 줄 중 레벨 `WARN` · `ERROR` (스택 트레이스 이어지는 줄 제외) |
| `nginx-access.log` | 상태 코드 `5xx`. 이 파일에는 500 · 502 만 있다(503 · 504 는 0건) |

레벨 · 상태 총계: `app.log` INFO 1,194 · WARN 35 · ERROR 46 / `nginx-access.log` 200 19,278 · 400 29 · 404 969 · 409 35 · 500 46 · 502 8.

**평소 수준** (09:00 ~ 13:40, KST):

| 신호 | 평소 수준 |
|---|---|
| 요청량 (`nginx-access.log` 전체 줄) | 분당 최소 40 · 중앙값 59 · 최대 82(11:14 · 13:35). 10분당 516 ~ 682건 |
| `app.log` WARN | 10분당 0 ~ 4건. 35건 모두 `GlobalExceptionHandler` 의 `state conflict: 마감된 과제는 재배포할 수 없습니다`(409) 한 종류. nginx 409 35건과 건수가 같다 |
| `app.log` ERROR · nginx 500 | 7건씩, 시각 1:1 일치. 모두 `교사단말A` 의 배포 `41` 요청: 09:52 3건 · 10:31 1건 · 11:17 ~ 11:18 3건 |
| nginx 502 | 0건 |

**배포 이후** (13:40 ~ 14:39, KST):

| 10분 구간 | 요청 | nginx 5xx | app ERROR | app WARN | 재배포 POST |
|---|---:|---:|---:|---:|---:|
| 13:30 | 663 | 0 | 0 | 0 | 0 |
| 13:40 | 625 | 11 (502 8 · 500 3) | 3 | 0 | 5 |
| 13:50 | 549 | 11 | 11 | 1 | 15 |
| 14:00 | 613 | 7 | 7 | 0 | 7 |
| 14:10 | 665 | 6 | 6 | 0 | 6 |
| 14:20 | 605 | 6 | 6 | 1 | 9 |
| 14:30 | 581 | 6 | 6 | 2 | 8 |

- 13:40 ~ 14:39 의 분당 요청은 45 ~ 79건으로 평소 범위(40 ~ 82) 안이다.
- 500 은 배포 전 7건 · 배포 후 39건, 합 46건으로 `app.log` ERROR 46건과 같다. 502 8건은 13:41:51 ~ 13:41:59 에만 있다.
- 재배포 POST 를 배포 번호 · 단말 · 상태로 나누면(배포 시작 13:41:48 기준 전 / 후):

| 배포 | 단말 | 기동 전 | 기동 후 |
|---|---|---|---|
| `41` | 교사단말A | 500 × 6 | 요청 없음 |
| `42` | 교사단말A | 200 × 5 | **500 × 19** |
| `43` | 교사단말A | 200 × 13 | **500 × 20** |
| `8` · `44` | 교사단말A | 200 × 8 · 200 × 17 | 200 × 1 · 200 × 1 |
| `1` · `2` · `3` | 교사단말A | 409 × 31 | 409 × 4 |
| `5` · `6` · `7` | 교사단말B | 200 × 23 | 200 × 4 |

**확정한 수집 범위:**

- 주 구간: **2026-09-17 13:30:00 ~ 14:39:58 KST** (배포 시작 10분 전 ~ 로그 끝). 마지막 500 은 14:34:45(`nginx-access.log:20084`)이고, 그 뒤 배포 `42` · `43` 재배포 요청이 없어 이상이 끝났는지는 로그로 확인 불가.
- 보조 구간: **09:52:17 ~ 11:18:02 KST** — 배포 전에 같은 예외(`Assignment ... identifier value 9`)가 난 7건. 주 구간 오류와 예외 메시지가 같아 함께 본다.

## 2. 타임라인

> 이 절은 수집 범위 안의 사건을 KST 한 시간축에 늘어놓기만 한다. 원인 해석은 쓰지 않는다.
> 정밀도: `app.log` 밀리초 · `nginx-access.log` 초 · `deploy-history.md` 초(수동 기록 가능). 정밀도가 다른 파일끼리 2초 안에 찍힌 사건은 "(선후 불확실)"로 표시한다.

**가장 많이 나온 오류:** `app.log` ERROR `unhandled exception on /api/distributions/{id}[/redistribute]` + `JpaObjectRetrievalFailureException: Entity com.example.assignment.Assignment with identifier value 9 does not exist` — 46건(전부 같은 메시지 · 같은 id `9`). **첫 시각 09:52:17.335**(`app.log:194`).

- 09:00:04 ~ 09:52:17 사이 `app.log` 메시지 종류는 평소와 같은 10종(404 · 400 · 409 · 리포트 생성 · 재배포 INFO)뿐이고, 배포 `41` 에 대한 요청도 `nginx-access.log:3150` 이 처음이다. **첫 발생보다 앞선 [선행] 사건은 수집한 로그 안에 없다.** 로그가 09:00 부터라 그 이전은 확인 불가.
- 배포 후 오류는 같은 예외지만 던진 위치가 다르다(배포 전 `DistributionService.loadOrThrow` 7건 / 배포 후 `DistributionService.listByClass` 39건, 3절 증상 표). 배포 후 첫 `listByClass` 오류(13:46:38) 기준으로는 아래 배포 · 재기동 사건이 **[선행]** 이다.

### 2.1 보조 구간 (09:52 ~ 11:18)

| KST | 파일:줄 | 사건 |
|---|---|---|
| 09:52:17 | `nginx-access.log:3150` | `교사단말A` `POST /api/distributions/41/redistribute` → 500. 이후 09:52:41 까지 2건(`:3162` · `:3174`) |
| 09:52:17.335 | `app.log:194` | ERROR `unhandled exception on /api/distributions/41/redistribute`, `Assignment ... identifier value 9 does not exist`, 스택 `DistributionService.loadOrThrow(DistributionService.java:65)` ← `DistributionService.redistribute(:51)` ← `DistributionController.redistribute(:38)`(`app.log:195` ~ `:214`). 이후 09:52 에 2건(`:232` · `:270`) |
| 09:52:42.235 | `app.log:308` | INFO `redistributed distribution 44 (assignment 5, class 2)` — 같은 단말의 다른 배포 재배포는 성공 |
| 10:31:05 | `nginx-access.log:5563` · `app.log:451` | `교사단말A` `GET /api/distributions/41` → 500, 같은 예외 |
| 11:17:40 | `nginx-access.log:8442` · `app.log:655` | 배포 `41` 재배포 500. 이후 11:18:02 까지 2건(`nginx-access.log:8466` · `:8468`, `app.log:695` · `:733`) |

### 2.2 주 구간 (13:30 ~ 14:39)

| KST | 파일:줄 | 사건 |
|---|---|---|
| 13:35 | `nginx-access.log` 분 집계 | 분당 요청 82건(하루 최대와 같음). 11:14 에도 82건 |
| 13:38 | `deploy-history.md:21` | **[선행]** v1.4.2 배포 승인 (`***@example.com`, 분 단위) |
| 13:40:05 | `deploy-history.md:7` | **[선행]** v1.4.2 배포 시작. 변경: "재배포 응답에 학급 배포 이력(history) 포함 — `DistributionController.redistribute`". 배포 방식 "단일 호스트 재기동(무중단 아님)"(`deploy-history.md:18`), 대상 `API-1` |
| 13:41:20.994 | `app.log:1238` | INFO `redistributed distribution 43 (assignment 6, class 1)` — 구버전에서 배포 `43` 재배포 성공(`nginx-access.log:16829`, 200, 응답 183바이트) |
| 13:41:48.214 | `app.log:1240` | **[선행]** `Commencing graceful shutdown` |
| 13:41:50.144 | `app.log:1241` | `Graceful shutdown complete`. 이어 Hikari 풀 종료(`app.log:1244`, 13:41:50.224) |
| 13:41:50 | `nginx-access.log:16862` ~ `:16864` | `프로브` `GET /` 200 외 2건 200 (선후 불확실 — `app.log:1241` 과 1초 안) |
| 13:41:51 | `nginx-access.log:16865` | 첫 502: `GET /api/units/M5-2/items?student=학생A`. 이후 13:41:59 까지 7건(`:16866` ~ `:16872`, `프로브` `GET /` 502 포함, `/api/distributions/8?student=학생B` · `/api/units/M6-1/items?student=학생C` 등) |
| 13:41:55.902 | `app.log:1245` | **[선행]** `Starting ItemBankApplication v1.4.2 using Java 21.0.4` |
| 13:41:58.535 | `app.log:1260` | `itembank-pool - Start completed.` |
| 13:41:59 | `nginx-access.log:16872` | 마지막 502 (선후 불확실 — `app.log:1264` 와 2초 안) |
| 13:42:00.304 | `app.log:1264` | `Started ItemBankApplication in 4.921 seconds` |
| 13:42:01 | `nginx-access.log:16873` | 기동 후 첫 200 (`GET /api/units`) |
| 13:42:10 | `nginx-access.log:16884` · `app.log:1266` | `교사단말B` 배포 `7`(class 3) 재배포 200, 응답 802바이트 |
| 13:42:31 | `deploy-history.md:7` | 배포 완료 기록 |
| 13:46:38.107 | `app.log:1274` | INFO `redistributed distribution 42 (assignment 4, class 1)` |
| 13:46:38.113 | `app.log:1275` | **배포 후 첫 ERROR** `unhandled exception on /api/distributions/42/redistribute`, `Assignment ... identifier value 9 does not exist`, 스택 `findByClassRoomIdOrderByDistributedAtAsc` ← `DistributionService.listByClass(DistributionService.java:38)` ← `DistributionController.redistribute(DistributionController.java:40)`(`app.log:1276` ~ `:1294`) |
| 13:46:38 | `nginx-access.log:17155` | `교사단말A` `POST /api/distributions/42/redistribute` → 500 |
| 13:46:58 ~ 14:34:45 | `app.log:1313` ~ `:2887` · `nginx-access.log:17170` ~ `:20084` | 이후 같은 ERROR · 500 38건. 배포 `42` 18건 · `43` 20건, 전부 `교사단말A`. 각 ERROR 바로 앞(같은 스레드, 6 ~ 553ms 앞)에 같은 배포의 `redistributed distribution` INFO 가 있다(예: `app.log:1312` → `:1313`, `:1395` → `:1396`) |
| 13:50:00 | `nginx-access.log:17353` · `app.log:1393` | `교사단말A` 배포 `8`(class 2) 재배포 200, 응답 908바이트 |
| 13:50:12 ~ 13:50:43 | `nginx-access.log:17365` ~ `:17400` | 배포 `43` 재배포 500 4건 (13:50 에만 4건, 하루 중 분당 최다 500) |
| 13:56:43 | `nginx-access.log:17734` · `app.log:1754` | `교사단말A` 배포 `44`(class 2) 재배포 200, 응답 640바이트 |
| 13:59:30 | `nginx-access.log:17867` · `app.log:1837` | 배포 `3` 재배포 409 (평소와 같은 WARN) |
| 14:34:45 | `nginx-access.log:20084` · `app.log:2887` | 마지막 500 (배포 `43`). 이후 14:39:58 로그 끝까지 5xx 0건, 배포 `42` · `43` 요청 0건 |

### 2.3 찾았지만 없었던 사건

| 찾은 사건 | 검색어 · 방법 | 결과 |
|---|---|---|
| 13:41 외의 재시작 · 롤백 | `grep -cE 'Starting ItemBankApplication'` · `'Commencing graceful shutdown'` · `'rollback\|v1\.4\.1'` (`app.log`) | 1 · 1 · 0. v1.4.1 로 되돌린 흔적 없음 |
| 설정 재적재 | `grep -cE 'refresh\|reload\|Refreshing'` (`app.log`) | 0 |
| 커넥션 풀 고갈 · 타임아웃 | `grep -cE 'timed out\|timeout\|Connection is not available'` · `'HikariPool.*(WARN\|ERROR)\|leak'` (`app.log`), nginx 503 · 504 | 0 · 0 · 0 |
| 메모리 | `grep -cE 'OutOfMemory\|GC overhead'` (`app.log`) | 0 |
| 트래픽 급증 | 분당 요청 집계 (1.2) | 배포 후 45 ~ 79건, 평소 최대 82 이하 |
| 배포 `41` 의 정상 응답 | `grep -n '/api/distributions/41'` (`nginx-access.log`) | 7건 모두 500, 2xx 0건. 13:41 이후 요청 없음 |
| 배포 후 학급 1 재배포 성공 | 재배포 POST 집계 (1.2) | 배포 `42` · `43` 기동 후 2xx 0건 |
| 배포 후 학급 1 리포트 | `awk '$7=="/api/classes/1/report"'` (`nginx-access.log`) | 17건 모두 200, 마지막은 13:24:45(`app.log:1191`). 기동 후 요청 없음 |
| `API-2` 사용 흔적 | nginx access log 에 upstream 주소 필드 없음 | 로그로 확인 불가 |
| 학생 화면 오류 응답 증가 | 404 · 400 분 집계 | 평소와 같은 종류(`/api/unit` · `/favicon.ico` 등)만 반복 |

## 3. 가설과 검증

> 이 절은 가설 · 지지 근거 · 반증 조건 · 확인 방법만 적는다. **판정(유지 / 기각)과 확인 명령 실행, 코드 대조는 하지 않는다**(요청 범위).
> 가장 많이 나온 오류는 원인이 아니라 증상으로 보고 아래 증상 표에 따로 둔다.

### 3.1 증상 표 (판정하지 않음)

| 증상 | 건수 | 기간 | 던진 위치 (스택 최상단 앱 프레임) | 요청 | 근거 |
|---|---:|---|---|---|---|
| ERROR + 500 `Assignment ... identifier value 9 does not exist` | 7 | 09:52:17 ~ 11:18:02 | `DistributionService.loadOrThrow(:65)` ← `redistribute(:51)` 6건 · ← `getDistribution(:32)` 1건 | 배포 `41` 재배포 6 · 단건 조회 1, `교사단말A` | `app.log:194` · `:451`, `nginx-access.log:3150` · `:5563` |
| ERROR + 500 같은 예외 | 39 | 13:46:38 ~ 14:34:45 | `DistributionService.listByClass(:38)` ← `DistributionController.redistribute(:40)` | 배포 `42` 19 · `43` 20 재배포, `교사단말A` | `app.log:1275` ~ `:2887`, `nginx-access.log:17155` ~ `:20084` |
| 502 | 8 | 13:41:51 ~ 13:41:59 | 앱 로그 없음 | 학생 · `프로브` 조회 등 8개 단말, 경로 무관 | `nginx-access.log:16865` ~ `:16872` |

### 3.2 가설

**H1 [코드 · 배포 변경] v1.4.2 가 재배포 뒤 학급 배포 목록(`listByClass`)을 함께 읽도록 바뀌었고, 학급 1 목록에 깨진 배포가 들어 있어 학급 1 의 재배포 응답이 전부 500 이 됐다.**

| 항목 | 내용 |
|---|---|
| 지지 근거 | 변경 내용 `deploy-history.md:7` · `:15` · `:16`(`listByClass(classId)` 호출 추가). 배포 후 오류 스택이 `listByClass(:38)` ← `DistributionController.redistribute(:40)`(`app.log:1276` ~ `:1294`), 배포 전에는 이 위치 0건. 기동 후 학급 1 배포 `42` · `43` 재배포 39건 모두 500, 학급 2 · 3(`8` · `44` · `7` · `5`)은 200 이고 응답 크기 640 ~ 908바이트로 기동 전 183바이트보다 커졌다(`nginx-access.log:16829` · `:16884` · `:17353`) |
| 반증 조건 | (a) 기동 전(13:41:48 이전)에 `listByClass` 스택이 1건 이상 있다. (b) 기동 후 학급 1 배포 재배포가 2xx 를 1건 이상 받았다. (c) 기동 후 학급 2 · 3 재배포에서 같은 예외가 1건 이상 난다 |
| 확인 방법 | (a) `awk 'NR<1240' incident-logs/b-deploy-5xx/app.log \| grep -c 'listByClass'` (b) `awk '$4>="[17/Sep/2026:13:41:48" && $6=="\"POST" && $7~/distributions\/4[23]\/redistribute/ && $9<300' nginx-access.log \| wc -l` (c) ERROR 직전 INFO 의 `class N` 집계. 열어 볼 파일: `modern/api/src/main/java/com/example/assignment/DistributionController.java` · `DistributionService.java` (코드 대조는 이번 범위 밖) |

**H2 [DB · 데이터] 배포 `41` 이 존재하지 않는 과제(id 9)를 가리키는 고아 행이며, 이 행이 배포 전부터 있었다. 배포 `41` 은 학급 1 소속이다.**

| 항목 | 내용 |
|---|---|
| 지지 근거 | 배포 전 배포 `41` 요청 7건이 모두 같은 예외 `identifier value 9`(`app.log:194` · `:451` · `:655`), 2xx 0건. 46건 전부 과제 id 가 `9` 하나. 학급 1 리포트가 `3 distributions`(`app.log:6` · `:1191`)인데, 로그에서 학급 1 로 확인된 배포는 `42` · `43` 둘뿐 |
| 반증 조건 | (a) 46건 중 과제 id 가 `9` 가 아닌 예외가 있다. (b) 배포 `41` 이 2xx 로 응답한 줄이 있다. (c) DB 에서 배포 `41` 의 학급이 1 이 아니거나, 과제 9 가 존재한다 |
| 확인 방법 | (a) `grep -oE 'identifier value .[0-9]+.' app.log \| sort \| uniq -c` (b) `grep '/api/distributions/41' nginx-access.log \| awk '$9<300' \| wc -l` (c) 로그로 확인 불가 — 운영 DB 에 직접 붙지 말고 DBA 에 `distribution` id 41 의 학급 · 과제 id, `assignment` id 9 존재 여부, 두 테이블 간 FK 유무 조회를 요청. 열어 볼 파일: `db/mariadb/init/` 시드 |

**H3 [배포 · 설정] 무중단이 아닌 단일 호스트 재기동 때문에 13:41:51 ~ 13:41:59 의 502 8건이 났다(500 과는 별개 사건).**

| 항목 | 내용 |
|---|---|
| 지지 근거 | 배포 방식 "단일 호스트 재기동(무중단 아님). 예상 중단 10초 안팎"(`deploy-history.md:18`). 종료 13:41:48.214(`app.log:1240`) ~ 기동 완료 13:42:00.304(`app.log:1264`) 안에 502 8건(`nginx-access.log:16865` ~ `:16872`), 그 밖의 시각 502 0건. 대기 호스트 `API-2` 는 "평소 정지"(`deploy-history.md:3`) |
| 반증 조건 | 13:41:48 이전 또는 13:42:01 이후에 502 가 1건 이상 있다 |
| 확인 방법 | `awk '$9==502{print substr($4,14,8)}' nginx-access.log \| sort -u` 결과가 모두 13:41:48 ~ 13:42:00 안인지 본다 |

**H4 [트래픽] 배포 후 5xx 증가는 요청량 급증이 아니라 `교사단말A` 한 대의 반복 재시도로 건수가 커진 것이다.**

| 항목 | 내용 |
|---|---|
| 지지 근거 | 배포 후 분당 요청 45 ~ 79건으로 평소 범위(40 ~ 82, 1.2). 500 46건 전부 `교사단말A`. 같은 배포를 수 초 ~ 수십 초 간격으로 3 ~ 4번씩 다시 보냄(예: 13:50:12 · 13:50:13 · 13:50:19 · 13:50:43, `nginx-access.log:17365` ~ `:17400`) |
| 반증 조건 | (a) 13:41 ~ 14:39 중 분당 요청이 82건을 넘는 분이 있다. (b) 500 을 받은 IP 가 `교사단말A` 외에 있다 |
| 확인 방법 | (a) `awk '{c[substr($4,14,5)]++} END{for(k in c) if(c[k]>82) print k,c[k]}' nginx-access.log` (b) `awk '$9==500{print $1}' nginx-access.log \| sort \| uniq -c` |

**H5 [코드 · 트랜잭션 경계] 500 응답을 받은 재배포도 쓰기는 이미 반영돼, 교사의 재시도마다 재배포가 중복 실행됐다.**

| 항목 | 내용 |
|---|---|
| 지지 근거 | 기동 후 500 39건마다 같은 스레드에서 바로 앞에 `redistributed distribution 42/43 (…, class 1)` INFO 가 찍혔다(`app.log:1274` → `:1275`, `:1312` → `:1313`, `:2886` → `:2887`). 스택에서 예외가 `DistributionService.redistribute` 가 아니라 그 뒤에 호출된 `listByClass`(`DistributionController.java:40`)에서 났다 |
| 반증 조건 | (a) 기동 후 500 중 직전 `redistributed` INFO 가 없는 건이 있다. (b) 코드에서 `redistribute` 와 `listByClass` 가 한 트랜잭션으로 묶여 함께 롤백된다. (c) DB 의 배포 `42` · `43` 재배포 횟수 · 시각이 기동 후 500 시각과 맞지 않는다 |
| 확인 방법 | (a) `grep -B1 -E 'ERROR.*distributions/4[23]/redistribute' app.log \| grep -c 'redistributed distribution'` 이 39 인지 (b) 열어 볼 파일: `DistributionController.java` 의 `@Transactional` 유무, `DistributionService.redistribute` · `listByClass` 의 트랜잭션 선언 (c) DBA 에 배포 `42` · `43` 의 재배포 이력 조회 요청 |

**H6 [인프라] 프록시 · 상위 계층(nginx upstream, `API-2` 전환 등) 문제로 5xx 가 났다.**

| 항목 | 내용 |
|---|---|
| 지지 근거 | 로그로 확인 불가 — nginx access log 에 upstream 주소 · 응답 시간 필드가 없고, nginx error log 가 수집되지 않았다 |
| 반증 조건 | 500 46건 모두 같은 초에 `app.log` ERROR 가 1:1 로 있고, 502 는 앱 종료 ~ 기동 구간에만 있다 |
| 확인 방법 | `diff <(awk '$9==500{print substr($4,14,8)}' nginx-access.log) <(grep -E '^2026.* ERROR ' app.log \| cut -c12-19)` 출력이 비는지, H3 의 502 시각 확인 |

마스킹 적용: IP 5종 → 역할 별칭(API-1 · API-2 · 교사단말A · 교사단말B · 프로브) · 그 밖 IP 0건 인용 · 학생 식별자 3건(별칭 3명) · 토큰 0건 인용(원본 `deploy-history.md:20` 의 `Authorization` 헤더 값은 인용하지 않음) · 이메일 1건 · 비밀번호 · 접속 문자열 0건
