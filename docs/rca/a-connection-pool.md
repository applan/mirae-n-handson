# 장애 원인 분석: a-connection-pool

대상 로그: `incident-logs/a-connection-pool/` (2026-09-16 하루치)

## 요약

- **무슨 일:** 2026-09-16 14:37:42 ~ 14:51:49 KST 에 item-bank API 가 500 49건 · 502 82건 · 504 55건을 냈다. 사용자 영향은 14:40:52 ~ 14:51:47, 약 11분이다. 전체 요청량은 평소와 같았다.
- **원인:** 학급 리포트 API(`GET /api/classes/{id}/report`)가 조회와 서명 계산 전체를 한 트랜잭션으로 묶어, 요청 1건이 DB 커넥션을 15 ~ 21초 쥐었다. 교사 단말 4대가 약 20초 주기로 리포트를 호출하면서 커넥션 풀(최대 5)이 리포트로 가득 찼고, 다른 API 가 커넥션을 얻지 못했다.
- **증상:** 가장 많이 나온 502 · `no live upstreams` 는 이 고갈 뒤에 따라온 증상이다. 원인이 아니다.
- **확신 수준: 중간.** 로그 · 코드 · 설정은 일치한다. 그러나 15 ~ 21초가 어디서 쓰였는지 나누지 못했고, 운영 배포본이 이 코드와 같은지 확인하지 못했다(9절).
- **즉시 수정:** 서명 계산을 트랜잭션 밖으로 빼서 커넥션 보유를 조회 시간으로 줄인다(6절).

## 1. 수집 범위

> 이 절은 범위만 정한다. 원인 추정은 하지 않는다.
> 모든 수치는 `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 집계로 얻었고, 파일 전체를 열어 읽지 않았다.

### 1.1 파일별 기록 기간 · 형식 · 타임존

| 파일 | 줄 수 | 기록 기간 (원문) | 기록 기간 (KST) | 시각 형식 (예) | 타임존 |
|---|---:|---|---|---|---|
| `app.log` | 5,371 (타임스탬프로 시작하는 줄 1,927 · 스택 트레이스 등 이어지는 줄 3,444) | `2026-09-16T06:43:56.753+09:00` ~ `2026-09-16T15:29:51.760+09:00` | 06:43:56 ~ 15:29:51 | ISO-8601, 밀리초, 오프셋 포함 `2026-09-16T14:40:52.319+09:00` | KST (+09:00, 1,927줄 모두 동일) |
| `nginx-access.log` | 21,086 | `[16/Sep/2026:06:43:56 +0900]` ~ `[16/Sep/2026:15:29:59 +0900]` | 06:43:56 ~ 15:29:59 | Nginx combined `[16/Sep/2026:14:51:03 +0900]`, 초 단위 | KST (+0900, 전 줄 동일) |
| `nginx-error.log` | 168 | `2026/09/16 09:12:03` ~ `2026/09/16 14:51:47` | 09:12:03 ~ 14:51:47 | `YYYY/MM/DD HH:MM:SS`, 초 단위 | **표기 없음 → KST 로 판정** (아래 근거) |
| `mariadb-slow.log` | 21 (항목 3건) | `# Time: 260916  0:14:09` ~ `# Time: 260916  5:37:52` | 09:14:09 ~ 14:37:52 | `# Time: YYMMDD H:MM:SS`(쿼리 종료) + `SET timestamp=<epoch초>`(쿼리 시작) | **UTC** (아래 근거) |

타임존 판정 근거:

- `nginx-error.log`: 오프셋 표기가 없다. `14:51:03` · `14:51:47` 의 `upstream timed out` 줄(`nginx-error.log:167` · `nginx-error.log:168`, client `교사단말A` · `교사단말B`, `GET /api/classes/3/report`)이 `nginx-access.log:19351` · `nginx-access.log:19381` 의 같은 클라이언트 · 같은 요청 `504` 와 **초 단위까지 같은 시각(+0900)** 이다. 따라서 KST 로 기록된 것으로 본다.
- `mariadb-slow.log`: `SET timestamp=` 의 epoch 값을 UTC 로 바꾸면 바로 위 `# Time:` 값과 `Query_time` 만큼만 차이 난다.
  - `1789517647` → UTC 00:14:07 / `# Time: 0:14:09` (Query_time 2.3초)
  - `1789524160` → UTC 02:02:40 / `# Time: 2:02:42` (Query_time 2.0초)
  - `1789537062` → UTC 05:37:42 / `# Time: 5:37:52` (Query_time 9.8초)
  - 따라서 `# Time:` 은 UTC 이고, 쿼리 종료 시각이다.

KST 변환 규칙:

| 파일 | 규칙 |
|---|---|
| `app.log` · `nginx-access.log` · `nginx-error.log` | 변환 없음 (KST 그대로) |
| `mariadb-slow.log` `# Time: YYMMDD H:MM:SS` | **+9시간** 해서 KST. 날짜가 넘어가면 날짜도 +1 (예: `260916 15:30:00` UTC → 2026-09-17 00:30:00 KST). 이 값은 쿼리 **종료** 시각이다 |
| `mariadb-slow.log` `SET timestamp=<epoch>` | `TZ=Asia/Seoul date -d @<epoch>` 로 변환. 쿼리 **시작** 시각이다 |

### 1.2 분 단위 이상 신호 집계

집계 대상 정의:

| 파일 | 센 줄 |
|---|---|
| `app.log` | 타임스탬프로 시작하는 줄 중 레벨 `WARN` · `ERROR` (스택 트레이스 이어지는 줄은 제외). 타임아웃은 `ERROR` 줄의 `request timed out after 3000ms` |
| `nginx-access.log` | 상태 코드 `5xx` (500 · 502 · 504). 504 가 타임아웃 |
| `nginx-error.log` | 레벨 `[error]` · `[warn]` (`[info]` 제외). 타임아웃은 `upstream timed out` |
| `mariadb-slow.log` | 항목 전부 (3건) |

**평소 수준** (06:43 ~ 14:36, KST):

| 신호 | 평소 수준 |
|---|---|
| 요청량 (`nginx-access.log` 전체 줄) | 분당 중앙값 41건(최소 2 · 최대 68), 10분당 약 400 ~ 500건. 14:40 ~ 14:59 구간도 10분당 429 · 413건으로 평소 범위 안 |
| `app.log` WARN | 10분당 0 ~ 2건. 모두 `GlobalExceptionHandler` 의 `state conflict`(409) 한 종류 |
| `app.log` ERROR | 0건 |
| `nginx-access.log` 5xx | 0건 |
| `nginx-error.log` error / warn | error 0건, warn 2건(09:12:03 · 13:05:51) |
| `mariadb-slow.log` | 09:14 · 11:02 KST 에 각 1건 (Query_time 2초대) |

**14:30 ~ 14:55 분 단위 집계** (KST, 이 밖의 분은 위 평소 수준과 같음):

| 분 | app WARN | app ERROR | 500 | 502 | 504 | nginx error | nginx warn | slow |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 14:30 ~ 14:32 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:33 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:34 ~ 14:36 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:37 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |
| 14:38 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:39 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:40 | 15 | 2 | 1 | 0 | 0 | 0 | 0 | 0 |
| 14:41 | 19 | 12 | 6 | 0 | 3 | 3 | 0 | 0 |
| 14:42 | 13 | 4 | 2 | 13 | 5 | 18 | 2 | 0 |
| 14:43 | 24 | 20 | 10 | 2 | 5 | 7 | 1 | 0 |
| 14:44 | 13 | 4 | 2 | 5 | 6 | 11 | 4 | 0 |
| 14:45 | 17 | 16 | 8 | 14 | 5 | 19 | 3 | 0 |
| 14:46 | 16 | 8 | 4 | 16 | 6 | 22 | 3 | 0 |
| 14:47 | 14 | 10 | 5 | 6 | 3 | 9 | 1 | 0 |
| 14:48 | 14 | 8 | 4 | 6 | 5 | 11 | 4 | 0 |
| 14:49 | 19 | 14 | 7 | 8 | 8 | 16 | 4 | 0 |
| 14:50 | 12 | 0 | 0 | 11 | 7 | 18 | 4 | 0 |
| 14:51 | 4 | 0 | 0 | 1 | 2 | 3 | 0 | 0 |
| 14:52 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:53 ~ 14:55 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

- 14:33 · 14:52 의 WARN 각 1건은 평소와 같은 종류(`state conflict`, `app.log:1229` · `app.log:5269`)다.
- 14:37 의 slow 1건은 `mariadb-slow.log:15-21` (시작 14:37:42 KST, 종료 14:37:52 KST, Query_time 9.8초).
- 14:37 ~ 14:51 의 그 밖의 WARN 은 평소에 없던 종류다(`ProxyLeakTask` "Connection leak detection triggered" 128건 등, 아래 표).

**평소와 다른 신호의 처음 · 마지막 시각** (KST):

| 신호 | 처음 | 마지막 | 근거 |
|---|---|---|---|
| slow query (9.8초) | 14:37:42 시작 | 14:37:52 종료 | `mariadb-slow.log:15-21` |
| `app.log` `ProxyLeakTask` WARN | 14:37:52 | 14:51:37 | `app.log:1243` · `app.log:5251` |
| `app.log` `ProxyLeakTask` INFO "Previously reported leaked connection … was returned" | 14:38:01 | 14:51:49 | 134건, 마지막 `app.log:5267` |
| `app.log` ERROR (`Connection is not available, request timed out after 3000ms` 49건 · `unhandled exception` 49건) | 14:40:52 | 14:49:51 | `app.log:1522` · `app.log:4981` |
| `nginx-access.log` 5xx (500 49 · 502 82 · 504 55) | 14:40:52 | 14:51:47 | `nginx-access.log:18918` · `nginx-access.log:19381` |
| `nginx-error.log` `[error]` 137건 (`no live upstreams` 68 · `upstream timed out` 55 · `connect() failed` 14) 및 `[warn]` `upstream server temporarily disabled` 26건 | 14:41:06 | 14:51:47 | `nginx-error.log:6` · `nginx-error.log:168` |

**제안: 이상 구간 = 2026-09-16 14:37:42 ~ 14:51:49 KST**

- 시작 14:37:42: 평소와 다른 신호 중 가장 이른 것(9.8초 slow query 시작). 로그상 첫 `ProxyLeakTask` WARN(14:37:52)과 10초 차이다. 사용자 영향(5xx)의 시작은 14:40:52.
- 끝 14:51:49: 마지막 5xx(14:51:47) · 마지막 nginx error(14:51:47) 직후 마지막 leak 반환 INFO(14:51:49). 14:52 이후로는 WARN(평소 종류 1건 제외) · ERROR · 5xx 가 없다.
- 요청량은 이 구간에도 평소 범위(분당 34 ~ 58건)여서, 요청량 변화로 구간을 나누지는 않았다.

### 1.3 앞뒤 여유

| 구분 | 제안 | KST | 이유 |
|---|---|---|---|
| 앞쪽 | **30분** | 14:07:42 부터 | 첫 이상 신호가 나오기 전에 이미 진행 중이던 요청 · 커넥션이 있을 수 있다. leak 감지 WARN 은 커넥션을 빌린 뒤 일정 시간이 지나야 찍히므로 실제 시작은 로그 시각보다 앞선다. 평소 패턴(요청량 · 409 빈도)을 비교할 기준 구간도 함께 확보한다 |
| 뒤쪽 | **10분** | 15:01:49 까지 | 회복 후 같은 신호가 다시 나타나지 않는지 확인하는 용도. 14:52 ~ 15:29 로그가 모두 평소 수준이므로 그 이상은 필요 없어 보인다 |

**최종 수집 범위: 2026-09-16 14:07 ~ 15:02 KST** (분 단위로 내림 · 올림)

파일별로 같은 범위를 고르는 기준:

| 파일 | 원문 시각 기준 범위 |
|---|---|
| `app.log` | `2026-09-16T14:07` ~ `2026-09-16T15:02` (`+09:00`). 스택 트레이스 이어지는 줄을 함께 가져온다 |
| `nginx-access.log` | `16/Sep/2026:14:07` ~ `16/Sep/2026:15:02` |
| `nginx-error.log` | `2026/09/16 14:07` ~ `2026/09/16 15:02` |
| `mariadb-slow.log` | `# Time: 260916  5:07` ~ `260916  6:02` (UTC). 다만 파일이 3건뿐이므로 비교용으로 **전체 3건을 모두 포함**한다 |

참고: 수집 범위 밖이지만 기준선 비교용으로 남겨 둘 것

- `mariadb-slow.log` 09:14 · 11:02 KST 항목 (평소 slow query 수준 비교)
- `nginx-error.log` 09:12:03 · 13:05:51 `[warn]` (둘 다 `an upstream response is buffered to a temporary file`, 평소 warn 수준 비교)

## 2. 타임라인

> 대상: 1절에서 정한 수집 범위 2026-09-16 14:07 ~ 15:02 KST. 모든 시각은 KST 다(`mariadb-slow.log` 는 UTC +9시간으로 바꿨다).
> 원인 해석은 쓰지 않는다. 로그에 찍힌 사건만 적는다.

읽는 법

- 출처는 모두 `파일:줄번호`(범위는 `파일:시작-끝`)로 적었다. 파일은 모두 `incident-logs/a-connection-pool/` 아래에 있다.
- 시각 정밀도: `app.log` 는 밀리초, `nginx-access.log` · `nginx-error.log` · `mariadb-slow.log` 는 초 단위다. 그래서 **서로 다른 파일에서 같은 초 또는 2초 안에 찍힌 사건은 선후를 단정하지 않고 "(선후 불확실)"로 표시**했다.
- `mariadb-slow.log` 는 한 항목에 시각이 두 개다. `SET timestamp` 는 쿼리 시작, `# Time` 은 쿼리 종료다.
- 원문의 학생 식별자는 리포트 안 등장 순서대로 `학생A` · `학생B` … 별칭으로 바꿨다. 같은 학생은 끝까지 같은 별칭이다. IP 는 역할 별칭(`API-1` 응답 서버 · `API-2` 연결 거부 서버 · `교사단말A~D` 리포트 반복 호출 단말)이나 `10.20.x.x` 로 가렸다.
- 반복되는 메시지는 첫 발생 줄에 "이후 N건, 분당 a~b건"으로 묶었다. 분당 건수는 1.2절 표를 따른다.
- **가장 많이 나온 오류**는 `nginx-access.log` 502(82건)와 `nginx-error.log` `no live upstreams`(68건)로, 둘 다 14:42:01 · 14:42:04 에 처음 나온다. 그보다 앞선 평소와 다른 사건은 **[선행]** 으로 표시했다.

### 2.1 14:07 ~ 14:37:41 — 평소 수준

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:07 ~ 14:37 | `nginx-access.log:17405-18784` | 이 범위 전체 집계. 분당 요청 34 ~ 58건(평소 범위). 5xx 0건 | `"GET /api/units/M5-1/items HTTP/1.1" 200` (첫 줄) |
| 14:07 ~ 14:37 | `app.log:1135-1242` | 이 범위 전체 집계. WARN 은 409 `state conflict` 뿐이고 ERROR 는 0건. INFO(404 · `no handler` · `built class report` 등)는 평소와 같은 종류 | `not found: 배포가 없습니다: id=…` (첫 줄) |
| 14:10:05 ~ 14:18:13 | `nginx-access.log:17539` ~ `nginx-access.log:17914` | `GET /api/classes/{1,2,3}/report` 5건, 모두 200. 그 뒤 14:38:01 까지 report 요청 없음 | `"GET /api/classes/3/report HTTP/1.1" 200 254` |
| 14:11:38 | `nginx-access.log:17605` · `app.log:1154` | 재배포 409 (distribution 3). 이후 같은 409 가 14:14:03 · 14:33:45 에 나옴 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=3` |
| 14:19:38 | `nginx-access.log:17977` · `app.log:1182` | 재배포 성공 (distribution 7, class 3) | `redistributed distribution 7 (assignment 5, class 3) reason=학생A …` |

### 2.2 14:37:42 ~ 14:42:00 — 첫 이상 신호부터 가장 많은 오류 직전까지 [선행]

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:37:42 | `mariadb-slow.log:20` | **[선행]** 느린 쿼리 시작. `submission` 테이블을 `distribution_id=5` 조건으로 조회 | `SET timestamp=1789537062;` |
| 14:37:52 | `mariadb-slow.log:15` · `mariadb-slow.log:18` · `mariadb-slow.log:21` | **[선행]** 느린 쿼리 종료. 9.8초, 128만 행 검사, 4행 반환. 이날 가장 긴 slow query 이고, 수집 범위 안에서는 유일한 slow 항목 | `Query_time: 9.812417 … Rows_sent: 4  Rows_examined: 1284310` |
| 14:37:52.199 | `app.log:1243` | **[선행]** 커넥션 누수 감지 WARN 첫 발생. `exec-6` 스레드, 스택은 `ReportController.classReport` → `ReportService.buildClassReport`. 이후 이 WARN 은 14:51:37 까지 133건 더 나옴(분당 1~14건, 14:40 부터 분당 9~14건). 134건 모두 스택이 `ReportController.classReport` (선후 불확실: 위 `mariadb-slow.log:15` 와 같은 초) | `leak detection triggered for …@6e8f14d3 on thread http-nio-8080-exec-6` |
| 14:37:52 | `nginx-access.log:18796` | `GET /api/distributions/5?student=…` 200 (선후 불확실: 위 두 줄과 같은 초) | `"GET /api/distributions/5?student=학생B HTTP/1.1" 200` |
| 14:38:01 | `nginx-access.log:18801` | **[선행]** 20분 만에 report 요청 재개. `교사단말A` 의 `GET /api/classes/3/report` 200. 이후 report 요청이 늘어남(14:38 · 14:39 분당 3건, 14:40 10건, 14:41 ~ 14:49 분당 12~19건). 평소에는 10분당 1~6건이었음 | `"GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:38:01.900 | `app.log:1258` | `exec-6` 에서 class 3 리포트 생성 완료 (선후 불확실: `nginx-access.log:18801` 과 같은 초) | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:38:01.902 | `app.log:1259` | **[선행]** 누수 감지된 커넥션 `@6e8f14d3` 이 풀에 반환됨. 이후 같은 INFO 가 14:51:49 까지 133건 더 나옴(분당 3~15건) | `Previously reported leaked connection …@6e8f14d3 … was returned to the pool` |
| 14:38:29 | `nginx-access.log:18817` · `app.log:1278` | **[선행]** 재배포 성공 (distribution 5, class 3) (선후 불확실: 두 파일 시각 차이 1초 이내) | `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:38 ~ 14:51 | `nginx-access.log:18801-19381` (report 요청 줄만 집계) | **[선행]** report 요청 클라이언트는 4개 IP(`교사단말C` · `교사단말A` · `교사단말B` · `교사단말D`). IP · 경로별로 평균 19~21초 간격(최소 0~1초, 최대 40~42초)으로 반복 요청함(`교사단말A`→class 3 40건, `교사단말B`→class 3 35건, `교사단말C`→class 1 35건 · class 2 32건, `교사단말D`→class 1 29건) | `"GET /api/classes/3/report HTTP/1.1" 200 266` (첫 줄) |
| 14:39:54 ~ 14:40:52 | `nginx-access.log:18881` ~ `nginx-access.log:18919` | **[선행]** report 요청 12건 연속 200 (클라이언트가 2개에서 4개 IP 로 늘어남) | `"GET /api/classes/1/report HTTP/1.1" 200 271` |
| 14:40:52.319 | `app.log:1521` · `app.log:1522` | **[선행]** 커넥션 획득 타임아웃 ERROR 첫 발생. 풀 상태는 total 5 · active 5 · idle 0 · waiting 4. 이후 14:49:51 까지 48건 더 나옴(분당 1~10건, waiting 1~7). 이 ERROR 49건 모두 `total=5, active=5, idle=0`. 직전에 같은 시각으로 `SQL Error: 0, SQLState: null` WARN 이 1건씩 짝지어 찍힘 | `Connection is not available, request timed out after 3000ms (… waiting=4)` |
| 14:40:52.320 | `app.log:1523` | **[선행]** 500 처리 로그 첫 발생. 대상은 `/api/units/M6-2/items`. 이후 48건 더 나옴. 경로별로는 units/{code}/items 19 · units 14 · items/{id} 7 · classes/{id}/report 5 · distributions/{id} 4 | `unhandled exception on /api/units/M6-2/items` |
| 14:40:52 | `nginx-access.log:18918` | **[선행]** 5xx 첫 발생. `/api/units/M6-2/items` 500. 이후 500 은 14:49:51(`nginx-access.log:19305`)까지 48건 더 나옴 (선후 불확실: `app.log:1522` 와 같은 초) | `"GET /api/units/M6-2/items HTTP/1.1" 500` |
| 14:40:52 | `nginx-access.log:18919` · `app.log:1556` · `app.log:1557` | 같은 초에 report 요청 하나는 200 으로 끝나고, 커넥션 `@3f6c2a91` 반환 INFO 가 찍힘 (선후 불확실) | `built class report for class 3 …` |
| 14:40:53 ~ 14:40:58 | `app.log:1558` · `app.log:1573` · `app.log:1588` · `app.log:1603` | **[선행]** 누수 감지 WARN 이 1~2초 간격으로 연속 발생(서로 다른 커넥션 `@6e8f14d3` · `@2ac19f07` · `@5b0e9e0c` · `@71d4b8e2`) | `Connection leak detection triggered for …@2ac19f07` |
| 14:41:06 | `nginx-error.log:6` · `nginx-access.log:18933` | **[선행]** upstream 응답 대기 타임아웃 첫 발생. `교사단말A` 의 `/api/classes/3/report` 가 504. 이후 `nginx-error.log` 타임아웃 54건 · `nginx-access.log` 504 54건이 더 나옴(분당 2~8건). 504 55건 모두 `/api/classes/{id}/report` (선후 불확실: 두 파일 같은 초) | `upstream timed out (110: Connection timed out) while reading response header` |

### 2.3 14:42:01 ~ 14:51:49 — 오류 다발 구간

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:42:01 | `nginx-error.log:10` | upstream 서버 일시 비활성화 warn 첫 발생. 이후 14:50:52(`nginx-error.log:158`)까지 25건 더 나옴(분당 1~4건) (선후 불확실: 아래 두 줄과 같은 초) | `upstream server temporarily disabled while reading response header` |
| 14:42:01 | `nginx-error.log:11` | upstream 연결 거부 첫 발생. 이후 14:50:52(`nginx-error.log:159`)까지 13건 더 나옴(분당 1~2건) | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:01 | `nginx-access.log:18970` | 502 첫 발생 (`/api/classes/2/report`). 이후 502 가 14:51:02(`nginx-access.log:19347`)까지 81건 더 나옴. 경로별로 report 32 · items/{id} 13 · units/{code}/items 12 · units 12 · distributions 9 · 기타 4 | `"GET /api/classes/2/report HTTP/1.1" 502 157` |
| 14:42:04 | `nginx-error.log:12` | 가능한 upstream 이 없다는 오류 첫 발생 (`/api/units/M6-2/items`). 이후 14:51:02(`nginx-error.log:166`)까지 67건 더 나옴(분당 1~14건, 14:42 · 14:45 · 14:46 에 11~14건) | `no live upstreams while connecting to upstream` |
| 14:42:42 · 14:50:22 · 14:51:52 | `nginx-access.log:18998` · `nginx-access.log:19321` · `nginx-access.log:19386` | 재배포 409 (평소와 같은 종류) | `POST /api/distributions/1/redistribute … 409` |
| 14:44:19 | `nginx-access.log:19078` · `app.log:2929` | 재배포 성공 (distribution 8, class 2) (선후 불확실) | `redistributed distribution 8 (assignment 6, class 2) reason=…` |
| 14:41 ~ 14:50 | `nginx-access.log:18926-19346` | 이 범위 전체 집계. 분당 전체 요청 34 ~ 58건으로 평소 범위. 그중 report 요청이 분당 10~19건 | `"GET /api/classes/2/report HTTP/1.1" 200` (첫 줄) |
| 14:49:51.552 | `app.log:4980` · `app.log:4981` · `nginx-access.log:19305` | 커넥션 획득 타임아웃 ERROR · 500 의 마지막 발생 (선후 불확실) | `request timed out after 3000ms` |
| 14:50:52 | `nginx-error.log:158` · `nginx-error.log:159` | upstream 비활성화 warn · 연결 거부의 마지막 발생 | `connect() failed (111: Connection refused)` |
| 14:51:02 | `nginx-error.log:166` · `nginx-access.log:19347` | `no live upstreams` · 502 의 마지막 발생 (선후 불확실) | `no live upstreams while connecting to upstream` |
| 14:51:37.674 | `app.log:5251` | 누수 감지 WARN 마지막 발생 | `Connection leak detection triggered for …` |
| 14:51:47 | `nginx-error.log:168` · `nginx-access.log:19381` | upstream 타임아웃 · 504 의 마지막 발생 (`교사단말B`, `/api/classes/3/report`) (선후 불확실) | `upstream timed out (110: Connection timed out)` |
| 14:51:49.148 | `app.log:5267` | 커넥션 반환 INFO 마지막 발생 (선후 불확실: 위 `nginx-error.log:168` 과 2초 차이) | `Previously reported leaked connection … was returned to the pool` |

### 2.4 14:51:50 ~ 15:02 — 평소 수준으로 돌아온 뒤

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:52:09 | `nginx-access.log:19401` · `app.log:5269` | 재배포 409 (평소와 같은 종류). 14:52 이후 `app.log` WARN 은 이 1건뿐 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=4` |
| 14:52 ~ 15:02 | `nginx-access.log:19393-19842` · `app.log:5269-5288` · `nginx-error.log:168` | 이 범위 전체 집계. 5xx 0건, `app.log` ERROR 0건. `nginx-error.log` 는 168번 줄(14:51:47)이 파일의 마지막 줄이라 이 범위에 줄이 없음 | `"GET /api/distributions/1?student=학생C HTTP/1.1" 200` (첫 줄) |
| 14:59:15 · 14:59:52 · 15:01:20 | `nginx-access.log:19701` · `nginx-access.log:19724` · `nginx-access.log:19781` | report 요청은 14:59 에 2건, 15:01 에 1건으로 모두 200(평소 빈도) | `"GET /api/classes/3/report HTTP/1.1" 200` |

### 2.5 수집 범위 안에서 찾았지만 해당 사건이 없었던 항목

- **설정 재적재 · 재시작 · 종료:** `app.log` · `nginx-error.log` 에서 `reload|refresh|signal|started|starting|shutdown|config|HikariPool-N|gracefully` 를 찾았지만 0건이었다.
- **전체 트래픽 급증:** 없었다. 전체 요청은 평소 범위였고, 늘어난 것은 `/api/classes/{id}/report` 요청뿐이다(2.2절).
- **`mariadb-slow.log`:** 수집 범위 안에는 14:37 항목 1건뿐이다.

## 3. 가설과 검증

> 각 표의 "내용" 열은 가설을 세울 때(판정 전) 적은 것이다. "검증 결과" 열은 2026-10-06 에 **로그로 확인할 수 있는 확인 방법만** 실제로 실행한 결과다. 실행한 명령 원문은 3.7절(C1 ~ C9)에 있다.
> 판정은 `유지`(반증 조건이 로그에서 확인되지 않음) · `기각`(반증 조건이 로그에서 확인됨) · `판단 불가(로그 부족)` 셋 중 하나다. `유지` 는 "맞다고 확정"이 아니라 "아직 반증되지 않았다"는 뜻이다. 가설끼리 서로 배타적이지 않다.
> 지지 근거의 `a-connection-pool.md:줄번호` 는 이 문서 2절 타임라인의 줄이다. 원본 로그 줄은 `incident-logs/a-connection-pool/` 기준으로 함께 적었다.

### 3.0 증상과 원인 후보의 구분

아래는 **증상**이다. 이 절의 가설은 이 증상들이 왜 생겼는지를 설명하려는 것이며, 증상 자체를 원인으로 부르지 않는다.

| 증상 | 건수 | 근거 |
|---|---:|---|
| `nginx-access.log` 502 · `nginx-error.log` `no live upstreams` (가장 많이 나온 오류) | 82 · 68 | `a-connection-pool.md:178-179` |
| `Connection is not available, request timed out after 3000ms` → 500 | 49 · 49 | `a-connection-pool.md:165-167` |
| `upstream timed out` → 504 (모두 `/api/classes/{id}/report`) | 55 · 55 | `a-connection-pool.md:170` |
| `ProxyLeakTask` 커넥션 누수 감지 WARN (모두 `ReportController.classReport` 스택) | 134 | `a-connection-pool.md:157` |

### 3.1 가설 H1 — 애플리케이션 코드: 리포트 생성이 트랜잭션(커넥션)을 쥔 채 오래 걸린다

| 항목 | 내용 | 검증 결과 (2026-10-06 실행) |
|---|---|---|
| 가설 | `ReportService.buildClassReport` 가 `@Transactional` 안에서 DB 조회 뒤 서명 루프(`sign`, SHA-256 200,000회)까지 돌아 요청 하나가 커넥션을 10초 넘게 쥐고, 동시에 들어온 리포트 요청 몇 건이 풀(최대 5)을 다 차지해 다른 API 가 3초 안에 커넥션을 얻지 못했다. | **유지** — 타임아웃 49건 중 48건은 대기 3초 내내 풀 5개를 모두 리포트 요청이 쥐고 있었고, 리포트 외 경로의 장기 보유는 0건이다. |
| 지지 근거 | `a-connection-pool.md:157` — 누수 감지 WARN 134건 모두 스택이 `ReportController.classReport` → `ReportService.buildClassReport`(원본 `app.log:1243-1256`). 감지 임계값이 10초(`application.yml:18`)이므로 각 커넥션을 10초 이상 쥐었다는 뜻이다.<br>`a-connection-pool.md:160-161` — `@6e8f14d3` 은 14:37:52.199 에 감지(빌린 시각 ≈ 14:37:42)되고 14:38:01.902 에 `built class report` 직후 반환됐다. 약 20초 보유.<br>`a-connection-pool.md:165` — 타임아웃 ERROR 49건 모두 `total=5, active=5, idle=0`.<br>`a-connection-pool.md:170` — 504 55건이 모두 report 경로.<br>코드: `ReportService.java:41`(`@Transactional`) · `ReportService.java:69-71`(TODO 주석: DB 작업이 끝난 뒤에도 커넥션을 쥔 채 루프) · `ReportService.java:85-93`. | — |
| 반증 조건 | ① 타임아웃 ERROR 가 찍힌 시각(예: 14:40:52.319)에 "감지됐지만 아직 반환되지 않은" 커넥션이 5개 미만이거나, 그 커넥션을 쥔 스레드가 report 처리 스레드가 아니다(= 풀을 차지한 것이 리포트가 아니다).<br>② 운영과 같은 JVM 에서 `sign()` 200,000회가 1초 미만으로 끝나고, report 요청의 커넥션 보유 시간 대부분이 조회 단계(아래 H2)에서 소비된다(= 루프가 보유 시간의 원인이 아니다).<br>③ 누수 감지 → 반환 간격이 report 요청마다 짧고(대부분 수 초), 리포트 외 경로의 요청이 오래 쥔 커넥션이 있다. | ① **확인되지 않음.** 감지 시각 − 10초를 빌린 시각으로 놓고 [빌림, 반환] 구간을 만들었다(C2). 감지 134건 모두 반환과 짝지어짐(미반환 0). 타임아웃 ERROR 49건의 대기 3초 동안 리포트가 계속 쥐고 있던 커넥션 수: **5개 48건 · 4개 1건**. (처음 쓴 C1 "그 순간 미반환 수" 방식은 10초 미만 보유를 못 세는 하한값이라 C2 로 바꿨다. C1 결과는 3.7절에 그대로 남겼다)<br>② **실행하지 않음** — 로그로 확인할 수 없다(코드 실행 측정 필요). 참고: 첫 커넥션 `@6e8f14d3` 은 9.8초 slow query 가 끝난(14:37:52) 뒤에도 14:38:01.902 까지 약 9.7초 더 쥐어졌고, 그 사이 slow log 항목은 없다(C2 · C4).<br>③ **확인되지 않음.** 리포트 보유 시간(빌림 추정 ~ 반환) 최소 **15.5초** · 중앙값 **18.9초** · 최대 **21.5초**(C2). 하루 전체 누수 감지 WARN **134건 중 리포트 외 스택 0건**(C3). |
| 확인 방법 | ① 커넥션별 감지 · 반환을 짝지어 시각별 미반환 수를 센다:<br>`awk -v T="2026-09-16T14:40:52.319" '$1>=T{for(k in o) print k, o[k]; exit} /leak detection triggered/{match($0,/@[0-9a-f]+/); o[substr($0,RSTART,RLENGTH)]=$1} /was returned to the pool/{match($0,/@[0-9a-f]+/); delete o[substr($0,RSTART,RLENGTH)]}' incident-logs/a-connection-pool/app.log` (출력 = 그 시각까지 감지 후 미반환인 커넥션과 감지 시각. 10초 미만 보유는 감지되지 않으므로 하한값이다)<br>② `sign()` 실행 시간을 잰다(코드 변경 없이): `cd modern/api && ./gradlew compileJava` 후 `jshell --class-path build/classes/java/main` 에서 `long t=System.nanoTime(); com.example.assignment.ReportService.sign("3\|학생A"); (System.nanoTime()-t)/1e6` (패키지 전용 메서드이므로 접근이 막히면 같은 패키지의 테스트 클래스에서 측정).<br>③ `grep -n 'exec-6' incident-logs/a-connection-pool/app.log \| sed -n '1,40p'` 처럼 스레드별로 감지 WARN · `built class report` · 반환 INFO 시각을 나란히 놓고 간격을 본다. | 실행: C1 · C2 · C3. ②는 실행하지 않음. |

### 3.2 가설 H2 — DB · 쿼리: `submission` 조회가 인덱스를 제대로 타지 못해 느리다

| 항목 | 내용 | 검증 결과 (2026-10-06 실행) |
|---|---|---|
| 가설 | `submission` 을 `distribution_id` 로 거르고 `student_id` 로 정렬하는 쿼리가 128만 행을 훑을 만큼 느려, 이 쿼리를 실행하는 리포트 요청의 트랜잭션이 길어지고 커넥션이 풀에 늦게 돌아왔다. | **기각** (14분 동안 이어진 풀 고갈의 원인으로서) — slow log 임계값은 2.04초 미만인데 14:38 ~ 14:51 slow 항목이 0건이어서, 15 ~ 21초씩 쥔 리포트 133건의 보유 시간을 느린 쿼리로 설명할 수 없다. 14:37:42 의 9.8초 쿼리 1건 자체는 사실로 남는다. |
| 지지 근거 | `a-connection-pool.md:155-156` — 14:37:42 시작 · 14:37:52 종료, 9.8초, `Rows_examined: 1284310` · `Rows_sent: 4`(원본 `mariadb-slow.log:15-21`). 이날 가장 긴 slow query.<br>`a-connection-pool.md:157` — 첫 누수 감지 WARN(14:37:52.199)의 커넥션을 빌린 시각(≈ 14:37:42)이 slow query 시작 시각과 같다.<br>스키마에 `idx_submission_distribution` 과 `idx_submission_student` 가 둘 다 있다(`db/mariadb/init/01-schema.sql:112-113`). `ORDER BY student_id` 때문에 옵티마이저가 정렬용 인덱스를 고를 여지가 있다. | — |
| 반증 조건 | ① slow log 임계값(`long_query_time`)이 2초 이하인데(평소 항목이 2.0 · 2.3초로 찍혔다) 14:38 ~ 14:51 에 slow 항목이 하나도 없다(`a-connection-pool.md:202`). 그렇다면 이 14분 동안 누수 감지 133건을 일으킨 리포트 요청들의 쿼리는 임계값보다 빨랐다는 뜻이다.<br>② 같은 쿼리의 `EXPLAIN` 이 `idx_submission_distribution` 을 쓰고 예상 행 수가 작다.<br>③ 14:37:42 의 slow query 를 보낸 것이 리포트 요청이 아니라 같은 시각에 끝난 `GET /api/distributions/5?student=…`(`a-connection-pool.md:158`)이다. | ① **확인됨.** slow 항목은 하루 **3건**: `Query_time` 2.318446(UTC 0:14:09) · **2.041903**(UTC 2:02:42) · 9.812417(UTC 5:37:52)(C4). 2.041903초 쿼리가 기록됐으므로 `long_query_time` < 2.04초다. 14:38 ~ 14:51 KST(UTC 5:38 ~ 5:51) 항목은 **0건**. 단, `log_slow_rate_limit` 같은 표본 추출 설정이 켜져 있었다면 이 추론은 깨진다(설정값은 로그에 없음).<br>② **실행하지 않음** — 로그가 아니라 DB 실행 계획 확인이다.<br>③ **판단 불가.** 14:37:40 ~ 14:37:53 `app.log` 에는 `no handler for /api/item/12`(14:37:43) 와 누수 감지 WARN 만 있고, `nginx-access.log` 에는 report 요청 없이 `GET /api/distributions/5?student=학생B` 200(14:37:52, `nginx-access.log:18796`)이 있다(C5). 두 로그 모두 쿼리를 보낸 스레드 · 요청을 MariaDB `Thread_id 48211` 과 이어 줄 값이 없다. |
| 확인 방법 | ① 운영 DB 설정값을 DBA 에게 요청: `SHOW GLOBAL VARIABLES LIKE 'long_query_time';` (이 저장소에서 운영 DB 에 직접 접속하지 않는다).<br>② 로컬에서 실행 계획 확인: `docker compose --profile modern up -d` 후 README 의 `readonly` 계정으로 접속해 `EXPLAIN SELECT s1_0.id,s1_0.distribution_id,s1_0.score,s1_0.student_id,s1_0.submitted_at FROM submission s1_0 WHERE s1_0.distribution_id=5 ORDER BY s1_0.student_id;` 로컬 시드는 운영보다 작으므로 운영 통계(`SHOW INDEX FROM submission;` · `SELECT COUNT(*) FROM submission;`)도 함께 요청한다.<br>③ 리포트가 부르는 쿼리 확인: `modern/api/src/main/java/com/example/assignment/SubmissionRepository.java` 의 `findByDistributionIdOrderByStudentIdAsc`(리포트 경로, `ReportService.java:53-54`)와 `DistributionService` 가 `distributions/{id}` 에서 쓰는 쿼리를 비교한다. | 실행: C4 · C5(①은 설정값 대신 slow log 의 최소 `Query_time` 으로 임계값 상한을 구했다). ②는 실행하지 않음.<br>③을 판단하려면: MariaDB general log 나 `performance_schema` 의 스레드 ↔ 접속 정보, 또는 앱 로그에 요청별 trace id · `Thread_id` 가 남아 있어야 했다. |

### 3.3 가설 H3 — 트래픽 · 클라이언트: 리포트 요청이 특정 클라이언트에서 반복적으로 몰렸다

| 항목 | 내용 | 검증 결과 (2026-10-06 실행) |
|---|---|---|
| 가설 | 전체 요청량은 평소와 같았지만, 교사 화면 4개 IP 가 `/api/classes/{id}/report` 를 약 20초 간격으로 반복 호출(자동 새로고침 또는 재시도)해 무거운 요청이 동시에 겹쳤고, 이 집중이 풀 고갈을 일으켰다. | **유지** — report 분당 10건 이상은 하루 중 장애 구간(14:40 ~ 14:50)에만 있었고, 반복 간격은 직전 응답이 2xx 든 5xx 든 약 20초로 같아 "장애 뒤 재시도"가 아닌 고정 주기 호출로 보인다. |
| 지지 근거 | `a-connection-pool.md:159` — 20분 만에 report 요청 재개, 평소 10분당 1 ~ 6건에서 14:41 ~ 14:49 분당 12 ~ 19건으로 늘었다.<br>`a-connection-pool.md:163` — 4개 IP 가 IP · 경로별 평균 19 ~ 21초 간격으로 반복(최대 40건).<br>`a-connection-pool.md:164` — 14:39:54 ~ 14:40:52 report 요청 클라이언트가 2개에서 4개로 늘어난 직후 첫 타임아웃(14:40:52).<br>`a-connection-pool.md:182` · `a-connection-pool.md:201` — 전체 요청은 평소 범위이고 늘어난 것은 report 요청뿐. | — |
| 반증 조건 | ① 같은 날(또는 다른 날) 다른 시간대에 report 요청이 분당 10건 이상이었는데도 누수 감지 WARN · 커넥션 타임아웃이 없었다.<br>② report 요청이 늘기 전(14:37:42 ~ 14:38:01)에 이미 풀의 커넥션 대부분이 report 가 아닌 요청에 묶여 있었다.<br>③ 반복 간격이 고정 주기가 아니라 직전 응답 실패(5xx) 직후에만 나타난다 — 이 경우 요청 증가는 원인이 아니라 장애의 결과(재시도)다. | ① **확인되지 않음.** report 분당 10건 이상인 분은 **14:40 ~ 14:50 의 11개 분뿐**(10 · 16 · 15 · 19 · 14 · 15 · 19 · 12 · 14 · 16 · 10건). 14:38 ~ 14:51 밖의 분당 최대는 **2건**(12:20 · 14:59 · 15:20)(C6). 다른 날 로그는 없다.<br>② **확인되지 않음.** 하루 전체 누수 감지 WARN 중 리포트 외 스택 **0건**(C3), 14:40:52 이전 5xx **0건**(C7). 다만 10초 미만으로 쥔 커넥션은 로그에 남지 않으므로 "짧게 쥔 일반 요청"이 풀을 얼마나 차지했는지는 알 수 없다.<br>③ **확인되지 않음.** 14:38 ~ 14:51, 같은 IP · 같은 경로의 다음 report 요청까지 간격: 직전 응답 **2xx 뒤 77건, 중앙값 20초(최소 0 · 최대 37)** / 직전 응답 **5xx 뒤 89건, 중앙값 19초(최소 0 · 최대 42)**(C8). |
| 확인 방법 | ① 하루 전체 분당 report 요청 수: `awk '/\/api\/classes\/[0-9]+\/report/{print substr($4,14,5)}' incident-logs/a-connection-pool/nginx-access.log \| sort \| uniq -c \| sort -k1,1nr \| head -20`<br>② H1 확인 방법 ①의 awk 를 14:37:42 · 14:38:01 시점으로 바꿔 실행하고, 그 시각 `app.log` 의 스레드별 처리 경로를 본다.<br>③ IP별 요청 시각 · 상태를 나란히 본다: `grep -n '교사단말A .*\/api\/classes\/3\/report' incident-logs/a-connection-pool/nginx-access.log \| awk '{print $4, $9}'` → 200 뒤에도 같은 간격인지 확인. 교사 화면(referrer `https://lms.example.com/teacher/classes/N/report`)의 자동 새로고침 · 재시도 설정은 이 저장소에 없으므로 프런트 담당에게 확인을 요청한다(`modern/web/src` 에는 `setInterval` · `refetchInterval` 이 없다). | 실행: C3 · C6 · C7 · C8(③은 IP 하나 대신 4개 IP · 경로 전체를 집계했다). 교사 화면 새로고침 설정은 로그 밖이라 확인하지 않음. |

### 3.4 가설 H4 — 인프라: 두 번째 API 인스턴스(API-2)가 요청을 받지 못해 한 대가 모든 트래픽을 받았다

| 항목 | 내용 | 검증 결과 (2026-10-06 실행) |
|---|---|---|
| 가설 | Nginx upstream 의 두 번째 인스턴스 `API-2:8080` 이 연결을 거부하는 상태여서 `API-1` 한 대(풀 5)가 모든 요청을 처리했고, `API-1` 이 응답 시간 초과로 일시 비활성화되자 살아 있는 upstream 이 없어 502 가 쏟아졌다. | **판단 불가(로그 부족)** — `API-2` 의 연결 거부는 14:42:01 에 처음 나오고 그 전 요청 18,917건 동안은 0건이지만, `API-2` 가 그동안 정상 처리 중이었는지 원래 트래픽을 받지 않는 구성이었는지를 로그로 가를 수 없다. |
| 지지 근거 | `a-connection-pool.md:177` — 14:42:01 `connect() failed (111: Connection refused)` 첫 발생. 원본 `nginx-error.log:11` 의 upstream 이 `http://API-2:8080`, 이후 14건 모두 같은 주소.<br>`a-connection-pool.md:176` — 같은 초에 `upstream server temporarily disabled`(원본 `nginx-error.log:10` 의 upstream 은 `API-1`).<br>`a-connection-pool.md:179` · `a-connection-pool.md:178` — 그 직후 `no live upstreams` · 502 다발.<br>`app.log` 는 `API-1` 한 대 것만 수집됐다(`mariadb-slow.log:16` 의 접속 호스트도 `API-1`). | — |
| 반증 조건 | ① `API-2` 가 14:42 이전에는 요청을 정상 처리하고 있었다(예: `API-2` 의 앱 로그 · 헬스체크 기록에 오전 ~ 14:41 정상 응답이 있다). 그렇다면 "장애 전부터 한 대만 받았다"는 전제가 틀리다.<br>② Nginx 는 실패한 서버를 `fail_timeout` 뒤 다시 시도하므로, `API-2` 가 하루 종일 꺼져 있었다면 14:42 이전에도 `API-2` `connect() failed` 가 찍혀야 한다. 14:42 이전 `API-2` 오류가 0건이고 ①도 확인되면 이 가설은 틀리다.<br>③ upstream 설정에 `API-2` 가 `backup` 이거나 원래 트래픽을 받지 않는 구성이다. | ① **판단 불가** — `API-2` 로그가 없다.<br>② **앞부분만 확인됨.** `nginx-error.log` 에서 `API-2` 는 **14건**, 첫 줄이 **14:42:01**(`nginx-error.log:11`). 14:41 이전 줄은 **5건**뿐이고 모두 `API-2` 와 무관(`[warn]` 임시 파일 버퍼링 2 · `[info]` 긴 헤더 2 · `[info]` keepalive 종료 1, 1.3절 · 2.5절과 같음). 06:43 ~ 14:40:51 요청은 **18,917건**(C9). 하루 종일 꺼져 있던 `API-2` 를 Nginx 가 순번에 넣고 있었다면 오전부터 `connect() failed` 가 찍혔어야 하므로 "장애 전부터 `API-2` 가 연결을 거부했다"는 부분은 로그와 맞지 않는다. 그러나 ①이 없어 조건 전체는 충족되지 않았다.<br>③ **판단 불가** — Nginx 설정이 없다. `connect() failed` **14건은 모두 `API-2`**, `temporarily disabled` **26건은 모두 `API-1`**(C9). |
| 확인 방법 | ② `grep -n 'API-2' incident-logs/a-connection-pool/nginx-error.log \| head -3` 과 `awk '$2 < "14:41:00"' incident-logs/a-connection-pool/nginx-error.log` 로 14:42 이전 `API-2` 관련 줄이 있는지 본다.<br>①③ 이 저장소에 없는 자료이므로 운영 담당에게 요청한다: Nginx `upstream item_bank_api` 블록(`server` 목록 · `max_fails` · `fail_timeout` · `backup` · `proxy_next_upstream`), `API-2` 의 같은 날 `app.log` · 프로세스 재시작 기록 · 헬스체크 기록. `nginx-access.log` 는 combined 형식이라 `$upstream_addr` 가 없어서 요청별 인스턴스를 알 수 없다는 점도 함께 적는다. | 실행: C9.<br>판단하려면: ① `$upstream_addr` · `$upstream_status` 가 찍힌 `nginx-access.log`(요청별 처리 인스턴스), ② `API-2` 의 같은 날 `app.log`(기동 · 종료 · 요청 처리 기록), ③ Nginx `upstream` 설정 파일이 있었어야 했다. |

### 3.5 가설 H5 — 배포 · 설정 변경: 장애 전에 풀 · 리포트 관련 설정이나 코드가 바뀌었다

| 항목 | 내용 | 검증 결과 (2026-10-06 실행) |
|---|---|---|
| 가설 | 9월 16일 이전 배포에서 커넥션 풀 설정(`maximum-pool-size: 5` · `connection-timeout: 3000`), 리포트 서명 반복 횟수(`SIGNATURE_ROUNDS`), 또는 Nginx upstream 구성이 바뀌어, 이전에는 견디던 리포트 요청량을 이날 견디지 못했다. | **판단 불가(로그 부족)** — 배포 이력과 9월 16일 이전 로그가 없어서, 두 반증 조건 모두 확인할 자료가 없다. |
| 지지 근거 | 타임라인에 직접 근거는 없고 간접 근거만 있다.<br>`a-connection-pool.md:165` — 풀 크기가 `total=5` 로 고정, 대기 3000ms 에서 실패(`application.yml:14-15` 와 같은 값).<br>`a-connection-pool.md:157` — 누수 감지 임계값 10초(`application.yml:18`)를 넘긴 보유가 리포트에서만 발생.<br>`a-connection-pool.md:200` — 수집 범위 안에는 재적재 · 재시작 기록이 없으므로, 변경이 있었다면 14:07 이전이다. | — |
| 반증 조건 | ① 배포 · 설정 변경 이력에 9월 16일 이전 일정 기간(예: 2주) 동안 `application.yml` 의 `hikari` 값, `ReportService`(특히 `SIGNATURE_ROUNDS`), Nginx upstream 설정 변경이 하나도 없다.<br>② 변경 이전 날짜에도 비슷한 report 요청량(분당 10건 이상)에서 같은 누수 감지 · 타임아웃이 있었다. | ① **판단 불가.** 저장소 이력: 두 파일을 바꾼 커밋은 `1260542 실습 저장소 초기 구성` **1건**뿐(C10). 이것은 실습 저장소 구성 커밋이라 운영 배포 이력이 아니다. `incident-logs/a-connection-pool/` 에는 배포 기록 파일이 없다. `app.log` 하루 전체(06:43:56 ~)에서 기동 · 종료 · 재적재 흔적 **0건**(C10)이라, 변경이 있었다면 06:43 이전이다.<br>② **판단 불가** — 9월 16일 이전 로그가 없다. |
| 확인 방법 | ① 저장소 이력: `git log --since=2026-09-01 -p -- modern/api/src/main/resources/application.yml modern/api/src/main/java/com/example/assignment/ReportService.java` (현재 저장소에는 초기 구성 커밋 `1260542` 하나뿐이라 운영 배포 이력을 따로 요청해야 한다. 형식 예: `incident-logs/b-deploy-5xx/deploy-history.md`).<br>운영 담당에게 9월 1일 ~ 16일 배포 기록 · Nginx 설정 변경 기록을 요청한다.<br>② 9월 16일 이전 날짜의 `app.log` 에서 `grep -c 'leak detection triggered'` · `grep -c 'request timed out after 3000ms'` 를 날짜별로 센다(이전 날짜 로그는 이 저장소에 없으므로 요청). | 실행: C10.<br>판단하려면: ① 9월 1일 ~ 16일 운영 배포 기록(`deploy-history.md` 같은 형식, 배포 시각 · 버전 · 변경 설정), Nginx 설정 변경 기록, ② 9월 16일 이전 며칠치 `app.log` · `nginx-access.log`(같은 report 요청량에서 누수 감지 · 타임아웃이 있었는지)가 있었어야 했다. |

### 3.6 판정 요약

| 가설 | 계층 | 판정 | 이유 (한 줄) | 판단하려면 필요한 로그 |
|---|---|---|---|---|
| H1 | 애플리케이션 코드 | **유지** | 타임아웃 49건 중 48건에서 대기 3초 내내 풀 5개 전부를 리포트가 쥐고 있었다(보유 15.5 ~ 21.5초, 리포트 외 장기 보유 0건). | — (②`sign()` 실행 시간은 로그가 아니라 코드 측정으로 따로 확인) |
| H2 | DB · 쿼리 | **기각** | 임계값 < 2.04초인데 14:38 ~ 14:51 slow 항목 0건이라 133건의 장기 보유를 느린 쿼리로 설명할 수 없다. | (③ 9.8초 쿼리를 누가 보냈는지는) MariaDB general log 또는 요청 ↔ DB 스레드를 잇는 trace id |
| H3 | 트래픽 · 클라이언트 | **유지** | report 분당 10건 이상은 장애 구간에만 있었고, 2xx 뒤든 5xx 뒤든 약 20초 간격으로 반복돼 재시도가 아닌 고정 주기 호출이다. | — (다른 날 비교는 이전 날짜 `nginx-access.log` 가 있으면 보강) |
| H4 | 인프라 | **판단 불가(로그 부족)** | `API-2` 연결 거부는 14:42:01 부터만 나오지만, 그 전에 `API-2` 가 트래픽을 받고 있었는지 가를 자료가 없다. | `$upstream_addr` 가 찍힌 access log, `API-2` 의 `app.log`, Nginx `upstream` 설정 |
| H5 | 배포 · 설정 변경 | **판단 불가(로그 부족)** | 배포 이력도, 9월 16일 이전 로그도 없다. | 9월 1일 ~ 16일 운영 배포 · 설정 변경 기록, 9월 16일 이전 `app.log` · `nginx-access.log` |

- H1 과 H3 은 둘 다 `유지` 이고 서로 배타적이지 않다. "리포트 한 건이 커넥션을 15 ~ 21초 쥔다"(H1)와 "리포트 요청이 20초 간격으로 4개 IP 에서 몰렸다"(H3)가 함께 있었다는 것까지가 로그로 확인한 범위다. 둘 중 무엇을 근본 원인으로 부를지는 이 절에서 정하지 않았다.
- 새로 생긴 열린 질문: `API-2` 는 14:42:01 부터 왜 연결을 거부했는가(H4 의 원래 전제와는 다른 질문이다).

### 3.7 실행한 명령 원문 (2026-10-06)

모든 명령은 저장소 루트 기준이다. `L=incident-logs/a-connection-pool`. 결과는 출력 그대로 옮겼고, 학생 식별자 · IP · 토큰은 리포트 맨 아래 마스킹 규칙대로 바꿨으며(`학생A` · `API-1` · `10.20.x.x` · `[TOKEN]`), C9 의 긴 줄 일부만 `...` 로 줄였다.

**C1 — (H1 ①, 처음 방식) 타임아웃 ERROR 시점의 "감지 후 미반환" 커넥션 수**

```bash
awk '/leak detection triggered/{match($0,/@[0-9a-f]+/); o[substr($0,RSTART,RLENGTH)]=$1}
     /was returned to the pool/{match($0,/@[0-9a-f]+/); delete o[substr($0,RSTART,RLENGTH)]}
     /request timed out after 3000ms/{n=0; for(k in o) n++; c[n]++}
     END{for(n in c) print "미반환 "n"개: 타임아웃 "c[n]"건"}' $L/app.log
```

```
미반환 0개: 타임아웃 3건
미반환 1개: 타임아웃 3건
미반환 2개: 타임아웃 48건
미반환 3개: 타임아웃 33건
미반환 4개: 타임아웃 33건
미반환 5개: 타임아웃 27건
```

이 결과는 쓰지 않았다. ① 합계가 147건인데, 패턴이 스택 트레이스 이어지는 줄까지 세서 ERROR 49건보다 많다. ② "감지된(10초 넘은) 커넥션"만 세므로 하한값이다. 그래서 C2 로 다시 셌다.

**C2 — (H1 ① · ③) 리포트 커넥션 보유 구간과 타임아웃 시점의 리포트 보유 수**

```python
# python3 -I h1.py incident-logs/a-connection-pool/app.log
import re,sys
from datetime import datetime,timedelta
from collections import Counter
ts=lambda s:datetime.fromisoformat(s)
warn={};iv=[];tos=[]
for line in open(sys.argv[1],encoding='utf-8'):
    if not line.startswith('2026'): continue            # 타임스탬프로 시작하는 줄만
    t=ts(line.split()[0])
    m=re.search(r'@([0-9a-f]+) on thread (\S+)',line)
    if 'leak detection triggered' in line: warn[m.group(1)]=(t,m.group(2))
    elif 'was returned to the pool' in line and m.group(1) in warn:
        w,th=warn.pop(m.group(1)); iv.append((w-timedelta(seconds=10),t,th))   # 빌린 시각 = 감지 - 10초
    elif 'ERROR' in line and 'request timed out after 3000ms' in line:
        tos.append((t,int(re.search(r'waiting=(\d+)',line).group(1))))
print('감지→반환 짝:',len(iv),'미반환 남음:',len(warn))
holds=sorted((b-a).total_seconds() for a,b,_ in iv)
print('보유시간(초, 빌린시각=감지-10s) 최소/중앙/최대: %.1f / %.1f / %.1f'%(holds[0],holds[len(holds)//2],holds[-1]))
c=Counter()
for t,w in tos:   # 대기 3초 구간 [t-3, t] 내내 리포트가 쥐고 있던 커넥션 수
    c[sum(1 for a,b,_ in iv if a<=t-timedelta(seconds=3) and b>=t)]+=1
print('타임아웃 ERROR',len(tos),'건 — 대기 3초 동안 report 가 계속 쥐고 있던 커넥션 수별:',dict(sorted(c.items())))
```

```
감지→반환 짝: 134 미반환 남음: 0
보유시간(초, 빌린시각=감지-10s) 최소/중앙/최대: 15.5 / 18.9 / 21.5
타임아웃 ERROR 49 건 — 대기 3초 동안 report 가 계속 쥐고 있던 커넥션 수별: {4: 1, 5: 48}
```

**C3 — (H1 ③ · H3 ②) 누수 감지 WARN 의 스택 첫 `com.example` 프레임**

```bash
grep -c 'leak detection triggered' $L/app.log
awk '/leak detection triggered/{f=1;next} f&&/at com.example/{print $2; f=0} /^2026/{f=0}' $L/app.log | sort | uniq -c
awk '/leak detection triggered/{f=1;next} f&&/at com.example/{print $2;f=0}' $L/app.log | grep -vc ReportService
```

```
134
    134 com.example.assignment.ReportService$$SpringCGLIB$$0.buildClassReport(<generated>)
0
```

**C4 — (H2 ①) slow log 항목의 종료 시각(UTC) · `Query_time`**

```bash
grep -n -E '^# (Time|Query_time)' $L/mariadb-slow.log | paste - - | awk '{print $1,$3,$4,$7,$8}'
```

```
1:# 260916 0:14:09 2.318446 Lock_time:
8:# 260916 2:02:42 2.041903 Lock_time:
15:# 260916 5:37:52 9.812417 Lock_time:
```

**C5 — (H2 ③) 14:37:40 ~ 14:37:53 의 `app.log` · `nginx-access.log`**

```bash
awk '$1>="2026-09-16T14:37:40" && $1<"2026-09-16T14:37:54"' $L/app.log | cut -c1-160
awk '$4>="[16/Sep/2026:14:37:40" && $4<"[16/Sep/2026:14:37:54"{print NR": "$1,$4,$7,$9}' $L/nginx-access.log | sed 's/STU-[0-9]*/STU-*/g'
```

```
2026-09-16T14:37:43.026+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-3] c.example.common.GlobalExceptionHandler  : no handler for /api/item/12
2026-09-16T14:37:52.199+09:00  WARN 1 --- [item-bank-api] [ool housekeeper] com.zaxxer.hikari.pool.ProxyLeakTask     : Connection leak detection triggered for o
18785: 10.20.x.x [16/Sep/2026:14:37:43 /api/item/12 404
18786: 10.20.x.x [16/Sep/2026:14:37:45 /api/items/23 200
18787: 10.20.x.x [16/Sep/2026:14:37:45 /api/units 200
18788: 10.20.x.x [16/Sep/2026:14:37:45 /api/units/M5-2/items?student=학생D 200
18789: 교사단말D [16/Sep/2026:14:37:45 /api/items/9 200
18790: 10.20.x.x [16/Sep/2026:14:37:46 /api/units 200
18791: 10.20.x.x [16/Sep/2026:14:37:47 /api/units/M6-1/items 200
18792: 교사단말B [16/Sep/2026:14:37:47 /api/items/28 200
18793: 10.20.x.x [16/Sep/2026:14:37:47 /api/items/17 200
18794: 10.20.x.x [16/Sep/2026:14:37:48 /api/distributions/4?student=학생B 200
18795: 10.20.x.x [16/Sep/2026:14:37:50 /api/units/M5-1/items 200
18796: 10.20.x.x [16/Sep/2026:14:37:52 /api/distributions/5?student=학생B 200
```

(nginx 는 요청 **종료** 시각을 찍는다. 14:37:42 에 시작해 14:38:01 에 끝난 class 3 리포트는 14:38:01 줄 `nginx-access.log:18801` 로 남는다.)

**C6 — (H3 ①) 분당 report 요청 수**

```bash
awk '/\/api\/classes\/[0-9]+\/report/{print substr($4,14,5)}' $L/nginx-access.log | sort | uniq -c | awk '$1>=10' | tr '\n' ' '
awk '/\/api\/classes\/[0-9]+\/report/{m=substr($4,14,5); if(m<"14:38"||m>"14:51") print m}' $L/nginx-access.log | sort | uniq -c | sort -k1,1nr | head -3
```

```
     10 14:40      16 14:41      15 14:42      19 14:43      14 14:44      15 14:45      19 14:46      12 14:47      14 14:48      16 14:49      10 14:50
      2 12:20
      2 14:59
      2 15:20
```

**C7 — (H3 ②) 첫 타임아웃(14:40:52) 이전 5xx 수**

```bash
awk '$4<"[16/Sep/2026:14:40:52" && $9~/^5/' $L/nginx-access.log | wc -l
```

```
0
```

**C8 — (H3 ③) 같은 IP · 같은 경로의 다음 report 요청까지 간격, 직전 응답 상태별 (14:38 ~ 14:51)**

```python
# python3 -I h3.py incident-logs/a-connection-pool/nginx-access.log
import re,sys
from datetime import datetime
from statistics import median
last={};g={'2xx':[],'5xx':[]}
for line in open(sys.argv[1],encoding='utf-8'):
    m=re.match(r'(\S+) .*?\[(\S+) [^\]]+\] "GET (/api/classes/\d+/report) [^"]*" (\d{3})',line)
    if not m: continue
    ip,t,path,st=m.groups(); t=datetime.strptime(t,'%d/%b/%Y:%H:%M:%S')
    if not ('14:38'<=t.strftime('%H:%M')<='14:51'): continue
    k=(ip,path)
    if k in last:
        pt,pst=last[k]; g['5xx' if pst[0]=='5' else '2xx'].append((t-pt).total_seconds())
    last[k]=(t,st)
for k,v in g.items(): print(f'직전 응답 {k} 뒤 다음 요청까지: {len(v)}건, 간격 중앙값 {median(v):.0f}초, 최소 {min(v):.0f} 최대 {max(v):.0f}')
```

```
직전 응답 2xx 뒤 다음 요청까지: 77건, 간격 중앙값 20초, 최소 0 최대 37
직전 응답 5xx 뒤 다음 요청까지: 89건, 간격 중앙값 19초, 최소 0 최대 42
```

**C9 — (H4 ② · ③) `API-2` 관련 줄, 14:41 이전 오류 줄, upstream 별 집계**

```bash
grep -n 'API-2' $L/nginx-error.log | head -1 | cut -c1-120
grep -c 'API-2' $L/nginx-error.log
awk '$2<"14:41:00"{print NR": "$1,$2,$3,substr($0,index($0,"*"),90)}' $L/nginx-error.log
grep 'connect() failed' $L/nginx-error.log | grep -o 'upstream: "http://[0-9.]*' | sort | uniq -c
grep 'temporarily disabled' $L/nginx-error.log | grep -o 'upstream: "http://[0-9.]*' | sort | uniq -c
awk '$4<"[16/Sep/2026:14:40:52"' $L/nginx-access.log | wc -l
```

```
11:2026/09/16 14:42:01 [error] 29#29: *225045 connect() failed (111: Connection refused) while connecting to upstream, c
14
1: 2026/09/16 09:12:03 [warn] ... an upstream response is buffered to a temporary file ...
2: 2026/09/16 10:42:17 [info] *172537 client sent too long header line: "Authorization: [TOKEN]
3: 2026/09/16 11:47:29 [info] *185201 client 10.20.x.x closed keepalive connection
4: 2026/09/16 13:05:51 [warn] ... an upstream response is buffered to a temporary file ...
5: 2026/09/16 13:18:05 [info] *172885 client sent too long header line: "Authorization: [TOKEN]
     14 upstream: "http://API-2
     26 upstream: "http://API-1
18917
```

(마지막 숫자는 06:43:56 ~ 14:40:51 전체 요청 줄 수. 14:40:52 이전 5xx 는 C7 의 0건.)

**C10 — (H5 ①) 저장소 변경 이력, `app.log` 기동 · 재적재 흔적**

```bash
git log --since=2026-09-01 --oneline -- modern/api/src/main/resources/application.yml modern/api/src/main/java/com/example/assignment/ReportService.java | wc -l
git log --oneline -- modern/api/src/main/resources/application.yml modern/api/src/main/java/com/example/assignment/ReportService.java
grep -c -E 'Started |Starting |HikariPool-[0-9]|Shutdown|shutdown|Refreshing|reload' $L/app.log
ls $L
```

```
1
1260542 실습 저장소 초기 구성: 레거시 3모듈 · 현행 샘플 · 동작 보존 테스트 틀 · 템플릿 · check-env
0
app.log
mariadb-slow.log
nginx-access.log
nginx-error.log
```

### 3.8 다음 단계

- `판단 불가` 인 H4 · H5 와 H2 ③에 필요한 자료(3.6절 마지막 열)를 운영 담당에게 요청한다. 운영 DB 에 직접 접속하는 명령은 쓰지 않는다.
- 로그로 확인할 수 없어 실행하지 않은 항목: H1 ②(`sign()` 실행 시간 측정), H2 ②(`EXPLAIN`). 필요하면 로컬에서 따로 실행한다.

### 3.9 판정

> 3.6절에서 `유지` 된 H1 · H3 을 `modern/api` 의 설정 파일과 로그에 찍힌 클래스의 코드와 대조했다. 설정값은 모두 파일에서 읽은 값이다. 벤치마크 · 테스트는 실행하지 않았다(2026-10-06).
> 저장소의 `application.yml` 은 운영 값과 같게 맞췄다고 적혀 있다(`application.yml:11`). 아래 대조는 이 주석과 로그 값이 일치한다는 데 기대고 있다.

#### 3.9.1 단서 → 코드 · 설정 대응

| 로그에 찍힌 단서 | 원본 로그 | 찾은 코드 · 설정 | 대응 |
|---|---|---|---|
| 풀 이름 `itembank-pool` | `app.log:1522` | `application.yml:10` `pool-name: itembank-pool` | 일치 |
| `total=5` (타임아웃 49건 모두) | `app.log:1522` | `application.yml:14` `maximum-pool-size: 5` | 일치 |
| `request timed out after 3000ms` | `app.log:1522` | `application.yml:15` `connection-timeout: 3000` | 일치 |
| `ProxyLeakTask` "Apparent connection leak detected" | `app.log:1243-1244` | `application.yml:18` `leak-detection-threshold: 10000` (10초 넘게 쥔 커넥션을 WARN 으로 알림) | 일치 |
| 누수 스택 `HibernateJpaDialect.beginTransaction` → `TransactionInterceptor.invoke` → `ReportService$$SpringCGLIB$$0.buildClassReport` → `ReportController.classReport(ReportController.java:22)` | `app.log:1249` · `app.log:1252-1254` | `ReportController.java:20-23` → `ReportService.java:41-42` (`@Transactional public ClassReport buildClassReport`) | 일치. 커넥션은 메서드 **진입 시 트랜잭션 시작**에서 빌렸다 |
| `built class report for class N: 3 distributions, 10 submissions` | `app.log:1258` | `ReportService.java:73-74` (서명 계산 `ReportService.java:71` **다음** 줄에서 찍음) | 일치 |
| `SqlExceptionHelper` ERROR → `unhandled exception on …` → 500 | `app.log:1521-1523` | `GlobalExceptionHandler.java:53-56` (`Exception` → 500, 고정 문구 "서버 내부 오류") | 일치 |
| slow query `select … from submission s1_0 where s1_0.distribution_id=5 order by s1_0.student_id` | `mariadb-slow.log:21` | `SubmissionRepository.java:8` `findByDistributionIdOrderByStudentIdAsc` — `modern/api/src/main` 에서 호출하는 곳은 `ReportService.java:54` **하나뿐** | 일치 |

#### 3.9.2 H1 — 애플리케이션 코드: **코드 · 설정이 가설을 지지한다**

| 근거 | 파일:줄번호 | 인용 · 내용 |
|---|---|---|
| 메서드 전체가 하나의 쓰기 트랜잭션 | `ReportService.java:41-42` | `@Transactional` / `public ClassReport buildClassReport(Integer classId)` — 조회 전용인데 `readOnly` 도 없고, 트랜잭션 경계가 메서드 전체다 |
| DB 조회가 끝난 뒤에도 같은 트랜잭션 안에서 계속 실행 | `ReportService.java:43-63` → `ReportService.java:69-71` | 조회(`findById` · `findByClassRoomIdOrderByDistributedAtAsc` · 배포별 `findByDistributionIdOrderByStudentIdAsc`) 다음에 `// TODO: 트랜잭션 밖으로 — 외부 집계 시스템 호출을 흉내내는 긴 루프.` / `// DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` / `String signature = sign(payload.toString());` |
| 고정 횟수 연산 루프 | `ReportService.java:23` · `ReportService.java:88-91` | `static final int SIGNATURE_ROUNDS = 200_000;` / `for (int round = 0; round < SIGNATURE_ROUNDS; round++) { digest.reset(); current = digest.digest(current); }` |
| 커넥션 반환은 메서드가 끝난 뒤 | `ReportService.java:73-81` · `app.log:1258-1259` | `built class report` 로그(14:38:01.900) 2ms 뒤 반환 INFO(14:38:01.902). 로그 · 코드 순서가 "빌림(진입) → 조회 → 서명 → 로그 → 반환"과 맞다 |
| 리포트 결과를 재사용하는 장치가 없다 | `ReportController.java:20-23` · `modern/api/src` 전체 | 요청마다 `buildClassReport` 를 새로 실행한다. `@Cacheable` · `@EnableCaching` **0건**(`grep -rn 'Cacheable\|EnableCaching' modern/api/src`) |
| 풀이 작고 대기가 짧다 | `application.yml:12` · `application.yml:14-15` | 주석 `풀이 작고 대기 시간이 짧아, 트랜잭션을 오래 쥐는 코드가 있으면 금방 고갈된다.` / `maximum-pool-size: 5` / `connection-timeout: 3000` |
| OSIV 꺼짐 — 컨트롤러 · 뷰 단계에서 커넥션을 더 쥐는 경로는 없음 | `application.yml:22` | `open-in-view: false`. 따라서 보유 시간은 `buildClassReport` 트랜잭션 구간으로 한정된다 |

수치 대조(로그 수치 × 설정값, 별도 실행 없음): 14:40 ~ 14:50 리포트 요청 160건 / 11분 ≈ 초당 0.24건(C6), 리포트 1건의 커넥션 보유 중앙값 18.9초(C2). 동시에 쥐는 커넥션 수 ≈ 0.24 × 18.9 ≈ **4.6개**로, 풀 최대 5개(`application.yml:14`)에 거의 닿는다. 그 위에 다른 API 요청이 한 개만 더 커넥션을 쥐어도 대기가 생기고, 3초(`application.yml:15`) 안에 못 얻으면 실패한다.

**코드로 확인되지 않은 부분:** 15 ~ 21초 중 무엇이 시간을 썼는지는 코드만으로 정할 수 없다. 코드가 보여 주는 것은 "조회 + 서명 루프 전체가 한 트랜잭션 안에 있다"는 구조까지다. 서명 루프는 파일에 있는 값이 `200_000` 회라는 것만 확인했고 실행 시간은 재지 않았다. SHA-256 한 번이 보통 마이크로초 이하라는 일반적인 성능 감각으로 보면, 20만 회만으로 15초를 채운다고 단정하기 어렵다. **이것은 파일에서 읽은 값이 아니라 추정이다.** CPU 경합이나 조회 단계(각 쿼리는 2.04초 미만, 3.2절) 같은 다른 요인이 보유 시간에 섞였을 수 있다.

#### 3.9.3 H3 — 트래픽 · 클라이언트: **관련 코드를 찾지 못했다**

| 근거 | 파일:줄번호 | 내용 |
|---|---|---|
| 반복 호출을 만드는 쪽의 코드가 없다 | — | 반복 요청의 referrer 는 `https://lms.example.com/teacher/classes/N/report`(`nginx-error.log:6`)인데, 이 화면의 코드는 저장소에 없다. `modern/web/src` 에는 `report` 를 쓰는 파일이 **0건**이고 `setInterval` · `refetchInterval` 도 없다 |
| 서버 쪽에는 반복 호출을 막거나 흡수하는 설정이 없다 | `ReportController.java:20-23` · `application.yml:1-34` | 캐시 · 요청 제한(rate limit) · 동시 실행 제한 설정이 없다. 단, 이것은 "반복 호출이 들어오면 그대로 다 처리한다"는 사실일 뿐이고, 반복 호출이 왜 생겼는지(H3 의 주장)는 지지도 반박도 하지 못한다 |
| CORS 설정은 운영 교사 화면과 무관 | `WebConfig.java:18-20` | `localhost:5173` 의 `GET /api/**` 만 허용하는 개발용 설정이다. 운영 도메인(`lms.example.com`)의 호출 경로는 이 저장소로 확인할 수 없다 |

#### 3.9.4 최종 판정

**채택: H1 — `ReportService.buildClassReport` 가 조회와 서명 루프 전체를 하나의 `@Transactional` 로 묶어 리포트 요청 1건이 커넥션을 15 ~ 21초 쥐었고, 리포트 요청이 겹치면서 풀(최대 5)이 리포트로 가득 차 다른 API 가 3초 안에 커넥션을 얻지 못했다.**

- 로그 근거: 타임아웃 49건 중 48건이 대기 3초 내내 풀 5개 모두 리포트에 묶인 상태였다(C2). 누수 감지 134건 모두 `buildClassReport` 경로다(C3).
- 코드 · 설정 근거: 트랜잭션 경계 `ReportService.java:41-42`, 트랜잭션 안의 서명 루프 `ReportService.java:69-71` · `ReportService.java:88-91`, 풀 설정 `application.yml:14-15`, 관측값과 설정 일치(3.9.1).
- 502 · `no live upstreams`(가장 많은 오류)는 이 고갈 뒤에 따라온 **증상**으로 본다. 첫 커넥션 타임아웃(14:40:52)이 첫 502(14:42:01)보다 먼저다(`a-connection-pool.md:165` · `a-connection-pool.md:178`).

**채택하지 않은 가설과 그 사유**

| 가설 | 3.6 판정 | 채택하지 않은 사유 |
|---|---|---|
| H2 DB · 쿼리 | 기각 | slow log 기준값이 2.04초 미만인데 14:38 ~ 14:51 의 slow 항목이 0건이다(C4). 그래서 15 ~ 21초 보유 133건을 느린 쿼리로 설명할 수 없다. 코드 대조로 3.2 ③도 정리됐다: 9.8초 쿼리의 SQL 은 `SubmissionRepository.java:8` 이고, 이를 호출하는 곳은 `ReportService.java:54` 뿐이다. `DistributionService` 는 `SubmissionRepository` 를 주입받지 않는다. 따라서 그 쿼리는 리포트 요청이 보냈다. 그래도 첫 커넥션은 쿼리가 끝난 뒤 약 9.7초를 더 쥐었다(3.1 ②). 이 쿼리는 첫 보유를 늘린 요인일 수는 있지만 14분 동안 이어진 고갈의 원인은 아니다 |
| H3 트래픽 · 클라이언트 | 유지 → 채택하지 않음 (**기여 요인으로 남김**) | 반증되지는 않았다. 그러나 요청 수만으로는 고갈이 설명되지 않는다. 같은 초당 0.24건이라도 1건 보유가 1초라면 동시 사용은 약 0.24개로 풀 5개에 한참 못 미친다. 고갈을 만든 곱셈 인자는 보유 시간(H1)이다. 또 전체 요청량은 평소 범위였다(`a-connection-pool.md:182`). 반복 호출은 고갈을 **드러낸 계기**로 기록한다. 호출 주체의 코드는 저장소에 없다(3.9.3) |
| H4 인프라 (`API-2`) | 판단 불가 | `API-1` 의 풀 고갈(첫 타임아웃 14:40:52)이 `API-2` 의 첫 연결 거부(14:42:01)보다 먼저다(C9). `API-2` 가 평소 트래픽을 나눠 받고 있었다면 `API-1` 은 절반만 받고도 고갈됐고, 원래 받지 않는 구성이었다면 `API-1` 은 오전 내내 같은 구조로 정상이었다. 어느 경우든 14:37 에 무엇이 달라졌는지를 설명하지 못한다. 502 확대에 관여했을 가능성은 남는다(`API-2` 로그 · Nginx 설정 필요) |
| H5 배포 · 설정 변경 | 판단 불가 | `app.log` 하루 전체에 재시작 · 재적재 흔적이 0건이다(C10). 그러니 06:43 ~ 15:29 동안 코드 · 설정은 같았고, 그 가운데 14:37 ~ 14:51 에만 장애가 났다. 06:43 이전 변경이 있었더라도 장애의 계기는 아니고 배경 조건일 뿐이다. 저장소의 풀 설정은 로그 값과 일치한다(3.9.1). 그러나 이 값이 언제부터였는지는 배포 이력 없이 알 수 없다 |

**확신 수준: 중간**

- 높일 근거
  - 로그(풀 5개 전부 리포트가 점유, 48/49건), 코드(트랜잭션 경계가 서명 루프까지 포함), 설정(풀 5 · 대기 3초 · 감지 10초)이 같은 그림을 가리킨다.
  - 로그에 찍힌 값과 `application.yml` 값이 모두 일치한다.
- 낮출 근거
  1. 리포트 1건이 15 ~ 21초 걸린 **내부 원인**(서명 루프 · CPU 경합 · 조회)을 나누지 못했다. 서명 루프 20만 회만으로 그 시간이 나온다는 것은 측정하지 않았고, 일반적인 처리 속도 감각과도 잘 맞지 않는다(3.9.2).
  2. 저장소 코드가 9월 16일 운영에 배포된 코드와 같다는 확인이 없다. 배포 이력이 없다(H5).
  3. `API-2` 상태와 Nginx 설정이 없어 인프라 쪽 기여분을 배제하지 못했다(H4).
- 확신을 "높음"으로 올리려면
  - `sign()` 실행 시간 측정, 또는 운영에 메서드 구간별 시간 로그를 넣어 보유 시간이 어디서 쓰였는지 확인한다.
  - 9월 16일 운영 배포 버전이 이 코드와 같은지 확인한다.

**참고 — 이번 대조에서 눈에 띈 것 (원인 판정과 무관):** `DistributionService` 의 재배포 로그 `log.info("redistributed distribution {} … reason={}"`(`DistributionService.java:56`)가 `reason` 값을 그대로 찍는다. 실제 로그에 학생 식별자가 남았다(`a-connection-pool.md:149`). 이는 CLAUDE.md 로깅 규칙("로그 인자에 학생 식별자를 넣지 않는다") 위반 소지가 있다. 이번 작업 범위가 아니어서 고치지 않았다.

## 4. 원인

> 3.9절에서 채택한 H1 을 인과 순서로 적는다. H3(호출 패턴)은 배경 원인으로 포함했다. 문장 번호는 5절 근거 표와 대응한다.

### 4.1 배경 원인 — 고갈을 가능하게 한 조건

- **B1 (코드):** `ReportService.buildClassReport` 는 메서드 전체가 `@Transactional` 이다. 커넥션은 메서드에 들어갈 때 빌리고 끝날 때 돌려준다. DB 조회가 끝난 뒤에도 서명 루프(`SIGNATURE_ROUNDS = 200_000`)가 같은 트랜잭션 안에서 돈다.
- **B2 (설정 · 구조):** 커넥션 풀은 최대 5개, 대기 한도는 3초다. 리포트 결과를 캐시하거나 동시 실행을 제한하는 장치가 없어서, 리포트 요청이 그대로 풀을 차지한다.
- **B3 (호출 패턴):** 교사 단말 4대가 리포트를 약 20초 주기로 반복 호출했다. 직전 응답이 성공이든 실패든 같은 주기였다.

### 4.2 직접 원인과 전개

1. 14:37:42 리포트 요청 1건이 커넥션을 빌려 약 20초 쥐었다. 이 요청의 `submission` 조회가 9.8초 걸렸다.
2. 14:38 부터 리포트 요청이 다시 들어오기 시작했고 14:40 에는 분당 10건을 넘었다. 요청마다 15 ~ 21초씩 커넥션을 쥐어, 리포트가 동시에 쥔 커넥션 수가 14:40:25 에 5개가 됐다.
3. **직접 원인:** 풀 5개를 모두 리포트 트랜잭션이 차지했다. 그래서 14:40:52 부터 다른 API 요청이 3초 안에 커넥션을 얻지 못해 500 이 났다(49건, 그중 48건은 대기 3초 내내 5개 모두 리포트가 점유한 상태였다).
4. 리포트 응답이 늦어지면서 Nginx 가 upstream 응답을 기다리다 504 를 냈다(55건, 모두 리포트). Nginx 는 API-1 을 일시 비활성화했다.
5. 같은 시점에 API-2 가 연결을 거부했다. API-1 도 비활성 상태여서 Nginx 에 살아 있는 upstream 이 없어졌고, 502 · `no live upstreams` 가 쏟아졌다(82건). API-2 가 연결을 거부한 이유는 확인하지 못했다(9절).
6. 리포트 요청이 14:50 10건 → 14:51 5건 → 14:52 0건으로 줄었다. 14:51:49 마지막 커넥션이 반환된 뒤 오류가 멈췄다.

## 5. 근거 로그 줄

| 문장 | 파일:줄번호 | 발췌 |
|---|---|---|
| B1 | `modern/api/.../ReportService.java:41-42` | `@Transactional` / `public ClassReport buildClassReport(Integer classId)` |
| B1 | `modern/api/.../ReportService.java:69-71` | `// DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` / `String signature = sign(payload.toString());` |
| B1 | `modern/api/.../ReportService.java:23` | `static final int SIGNATURE_ROUNDS = 200_000;` |
| B1 | `app.log:1249` · `app.log:1252-1254` | `HibernateJpaDialect.beginTransaction` → `TransactionInterceptor.invoke` → `ReportService$$SpringCGLIB$$0.buildClassReport` → `ReportController.classReport(ReportController.java:22)` |
| B2 | `modern/api/src/main/resources/application.yml:14-15` | `maximum-pool-size: 5` / `connection-timeout: 3000` |
| B2 | `modern/api/.../ReportController.java:20-23` | `@GetMapping("/{id}/report")` → `return reportService.buildClassReport(id);` (캐시 없음) |
| B3 | `nginx-access.log:18801-19381` (report 줄 집계, 3.7 C8) | 같은 단말 · 같은 경로의 다음 요청까지 간격 중앙값: 직전 2xx 뒤 20초 · 직전 5xx 뒤 19초 |
| 1 | `mariadb-slow.log:18` · `mariadb-slow.log:20` | `Query_time: 9.812417 … Rows_examined: 1284310` · `SET timestamp=1789537062;`(14:37:42 시작) |
| 1 | `app.log:1243` | `14:37:52.199 … Connection leak detection triggered for …@6e8f14d3 on thread http-nio-8080-exec-6` |
| 1 | `app.log:1258-1259` | `14:38:01.900 … built class report for class 3` / `14:38:01.902 … Previously reported leaked connection …@6e8f14d3 … was returned to the pool` |
| 2 | `nginx-access.log:18801` | `14:38:01 "GET /api/classes/3/report HTTP/1.1" 200` (20분 만의 리포트 요청) |
| 2 | `nginx-access.log:18801-19381` (report 줄 분당 집계, 3.7 C6) | 14:40 리포트 요청 10건 / 분. 14:38 ~ 14:51 밖에서는 분당 최대 2건 |
| 2 | `app.log:1243-5267` (감지 · 반환 짝, 3.7 C2) | 리포트 커넥션 보유 15.5 ~ 21.5초(중앙값 18.9초). 리포트 동시 보유 5개 도달 14:40:25 |
| 3 | `app.log:1522` | `14:40:52.319 … itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| 3 | `app.log:1523` · `nginx-access.log:18918` | `unhandled exception on /api/units/M6-2/items` · `"GET /api/units/M6-2/items HTTP/1.1" 500` |
| 4 | `nginx-error.log:6` · `nginx-access.log:18933` | `14:41:06 … upstream timed out (110: Connection timed out) … "GET /api/classes/3/report` · 같은 요청 504 |
| 4 | `nginx-error.log:10` | `14:42:01 [warn] … upstream server temporarily disabled …` (upstream API-1) |
| 5 | `nginx-error.log:11` | `14:42:01 [error] … connect() failed (111: Connection refused) …` (upstream API-2) |
| 5 | `nginx-error.log:12` · `nginx-access.log:18970` | `14:42:04 … no live upstreams while connecting to upstream` · `14:42:01 "GET /api/classes/2/report HTTP/1.1" 502` |
| 6 | `app.log:5267` · `nginx-access.log:19381` | `14:51:49.148 … was returned to the pool` (마지막) · `14:51:47 … 504` (마지막 5xx) |

`app.log` · `nginx-*.log` · `mariadb-slow.log` 는 `incident-logs/a-connection-pool/` 아래 파일이다. `modern/api/...` 는 `modern/api/src/main/java/com/example/assignment/` 를 줄였다.

## 6. 수정안 — 지금 적용할 것

> 제안만 한다. 코드는 바꾸지 않았다. `ReportService.buildClassReport` 는 CLAUDE.md 에 "요청 없이 고치지 않는다"고 적힌 장애 실습용 코드라서, 적용하려면 별도 승인을 받아야 한다.

| # | 파일 | 무엇을 | 어떻게 |
|---|---|---|---|
| 6-1 | `ReportService.java:41-82` | 트랜잭션 경계 | **조회 · 집계**는 `@Transactional(readOnly = true)` 메서드로 분리한다. 같은 클래스 안에서 호출하면 프록시가 적용되지 않으므로 별도 빈으로 나눈다. 이 메서드는 집계값과 payload 를 record 로 반환한다. **서명(`sign`)** 은 트랜잭션이 없는 바깥 메서드에서 호출한다. 그러면 커넥션 보유 시간이 조회 시간으로 줄어든다 |
| 6-2 | `ReportServiceTest` (테스트) | 회귀 방지 | 서명 단계에서 트랜잭션이 없다는 것을 검증하는 테스트를 추가한다. 예: `TransactionSynchronizationManager.isActualTransactionActive()` 가 `false` 인지. 기존 응답 형식(`ClassReport`)이 그대로인지도 확인한다 |
| 6-3 | 교사 리포트 화면 (이 저장소 밖) | 호출 주기 | 약 20초 자동 새로고침을 멈추거나 주기를 늘린다(예: 수동 새로고침 또는 60초 이상). 프런트 담당에게 요청한다 |

하지 않을 것: 풀 크기(`maximum-pool-size`)나 대기 한도(`connection-timeout`)를 늘리는 것은 1차 수정으로 제안하지 않는다. 보유 시간이 그대로면 고갈 시점만 늦출 뿐이고, CLAUDE.md 상 `hikari` 설정 변경은 금지 사항이다.

## 7. 재발 방지 — 같은 유형을 구조적으로 막는 장치

| # | 장치 | 막는 것 |
|---|---|---|
| 7-1 | "`@Transactional` 메서드 안에서 고정 횟수 루프 · 외부 HTTP 호출 금지" 규칙을 자동 검사로 만든다. `convention-check` Skill 항목 추가, 또는 ArchUnit 테스트(ArchUnit 은 의존성 추가라 승인 필요) | 커넥션을 쥔 채 DB 와 무관한 일을 하는 코드가 머지되는 것 |
| 7-2 | 무거운 엔드포인트 격리(bulkhead): 리포트 동시 실행 수를 풀보다 작게 제한한다(예: 세마포어로 최대 2) | 한 API 가 풀 전체를 차지해 다른 API 까지 실패하는 것 |
| 7-3 | 리포트 결과 캐시 또는 사전 계산. 배포 · 제출이 바뀔 때만 다시 계산한다 | 같은 리포트를 20초마다 다시 계산하는 것 |
| 7-4 | 트랜잭션 · 요청 시간 상한: `@Transactional(timeout = …)` 을 Nginx upstream 대기 시간보다 짧게 둔다 | 504 로 클라이언트는 이미 떠났는데 서버가 커넥션을 계속 쥐는 것 |
| 7-5 | 릴리스 전 부하 시나리오: 운영과 같은 풀 설정(5 · 3초)에서 리포트 동시 5건 + 일반 API 혼합 | 풀 크기 대비 보유 시간 문제를 배포 전에 찾는 것 |
| 7-6 | 클라이언트 호출 지침: 자동 갱신 최소 주기를 정하고, 실패 시 지수 백오프를 쓴다 | 고정 주기 호출이 장애 중에도 부하를 계속 더하는 것 |

## 8. 모니터링 항목

> 이번 타임라인에 임계값을 대입한 시각이다. 기준점은 첫 5xx(사용자 영향 시작) **14:40:52** 다. "추정" 표시는 지금 로그에 그 지표가 직접 없어서 2 · 3절 집계로 다시 계산한 값이다.

| # | 지표 | 임계값 (제안) | 이번에 울렸을 시각 | 첫 5xx 대비 |
|---|---|---|---|---|
| 8-1 | HikariCP 누수 감지 WARN(`ProxyLeakTask`) 건수 | 5분 안에 1건 이상 | 14:37:52 (`app.log:1243`) | **3분 0초 전** |
| 8-2 | 리포트 API 응답 시간(`$request_time` 또는 서버 요청 타이머) | 10초 초과 1건 | 14:38:01 (첫 리포트 약 19.7초, 추정) | **2분 51초 전** |
| 8-3 | 리포트 요청 수 | 분당 3건 이상이 2분 연속 (평소 하루 최대 분당 2건) | 14:40:00 (14:38 · 14:39 각 3건) | 52초 전 |
| 8-4 | 커넥션 풀 active 수 | 4 이상 (최대의 80%) | 14:40:11 (리포트 보유만 센 하한, 추정) | 41초 전 |
| 8-5 | 커넥션 풀 대기 스레드(pending) | 1 이상 | 늦어도 14:40:49 (첫 타임아웃이 3초 대기 뒤 실패) | 3초 전 |
| 8-6 | 커넥션 획득 실패(타임아웃) | 1건 이상 | 14:40:52 (`app.log:1522`) | 0초 (동시) |
| 8-7 | Nginx 5xx 비율 | 1분 동안 1% 초과 | 14:41:00 (14:40 분 42건 중 1건 = 2.4%) | 8초 **뒤** |
| 8-8 | slow query | `Query_time` 5초 초과 | 14:37:52 (`mariadb-slow.log:15`) | 3분 0초 전 (뒤에 재발이 없어 단독으로는 오경보 위험) |

- 8-1 · 8-2 가 가장 이르다. 사용자 영향보다 약 3분 먼저 울렸을 것이다.
- 8-6 · 8-7 은 사용자 영향과 동시이거나 늦다. 장애 확인용이고 조기 경보로는 쓸 수 없다.
- 8-2 · 8-4 · 8-5 를 쓰려면 9절의 로그 · 메트릭을 먼저 남겨야 한다.

## 9. 확인하지 못한 것

| 판단하지 못한 부분 | 다음을 위해 남겨야 할 로그 · 자료 |
|---|---|
| 리포트 1건의 15 ~ 21초가 조회 · 서명 · CPU 경합 중 어디서 쓰였는가 | `buildClassReport` 의 구간별 소요 시간 로그(조회 / 서명), 서버 CPU 사용률 |
| API-2 가 14:42:01 부터 연결을 거부한 이유, 그리고 평소 트래픽을 나눠 받았는가 | API-2 의 `app.log` · 재시작 기록 · 헬스체크 기록, Nginx `upstream` 설정(`max_fails` · `fail_timeout` · `backup`) |
| 요청별로 어느 인스턴스가 처리했고 얼마나 걸렸는가 | Nginx access log 형식에 `$upstream_addr` · `$upstream_status` · `$request_time` · `$upstream_response_time` 추가 |
| 9.8초 쿼리가 128만 행을 훑은 이유 | 운영 `EXPLAIN` 결과 · `SHOW INDEX FROM submission`, 요청과 DB 스레드를 잇는 trace id 또는 general log |
| slow log 설정(`long_query_time` · `log_slow_rate_limit`) | 운영 DB 설정값 기록 |
| 운영 배포본이 이 저장소 코드 · 설정과 같은가 | 배포 이력(시각 · 버전 · 변경 설정) |
| 다른 날에도 같은 패턴이 있었는가 | 9월 16일 이전 며칠치 `app.log` · `nginx-access.log` 보관 |
| 풀 active · pending 의 실제 시계열 | 커넥션 풀 메트릭 수집(Actuator · Micrometer 는 의존성 추가라 승인 필요) |
| 교사 화면이 왜 20초 주기로 호출하는가 | 교사 화면 프런트 코드 · 자동 갱신 설정 |

---

마스킹 적용: IP 71건(역할 별칭 `API-1` · `API-2` · `교사단말A~D`, 그 밖은 `10.20.x.x`) · 학생 식별자 8건(별칭 4명) · 토큰 2건(`[TOKEN]`) · 이메일 0건 · 비밀번호 · 접속 문자열 0건. 3.7절 명령 안의 IP 도 별칭으로 바꿨으므로, 그대로 다시 실행하려면 원본 로그의 값으로 바꿔 넣어야 한다.
