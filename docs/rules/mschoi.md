# 실 공통 규칙 후보 — mschoi

- 출처: `.claude/settings.json`(permissions · hooks, 2026-10-13 작업 트리 기준), `hooks/block-dangerous.mjs`, `hooks/secret-scan.mjs`, `scripts/hook-node.sh`
- 작성자 주 스택: Spring Boot / React / MS-SQL / MySQL / Vanilla JS / jQuery / JSP / ASP / ArgoCD / Jenkins
- 스택 코드 매핑(이 문서에서 정한 기준): JSP → `spring`, Vanilla JS · jQuery → `react`(웹 프런트), ASP(클래식 ASP · VBScript) → `php`(서버 스크립트 페이지라 같은 묶음), MS-SQL · MySQL · Docker · ArgoCD · Jenkins → `data-infra`
- Hook 은 스크립트 안의 탐지 패턴 하나를 한 줄로 적었다.

## 규칙 표

| 규칙 | 종류 | 적용 범위 | 막는 사고 또는 허용 이유 |
|---|---|---|---|
| `Bash(rm -rf *)` | deny | 공통 | 경로 실수 한 번으로 작업 트리 · 홈 디렉터리가 통째로 지워지는 사고 |
| `Bash(sudo *)` | deny | 공통 | 관리자 권한으로 시스템 패키지 · 설정 · 권한을 바꾸는 사고 |
| `Bash(git push --force *)` | deny | 공통 | 원격 이력을 덮어써서 동료 커밋이 사라지는 사고 |
| `Bash(git push -f *)` | deny | 공통 | 위와 같음(짧은 옵션) |
| `Bash(git reset --hard *)` | deny | 공통 | 커밋하지 않은 작업이 복구 불가능하게 사라지는 사고 |
| `Bash(*prod-db*)` | deny | 공통 | 운영 DB 호스트에 접속해 운영 데이터를 읽거나 바꾸는 사고 |
| `Bash(*prod.db*)` | deny | 공통 | 위와 같음(도메인 표기) |
| `Bash(curl *)` | deny | 공통 | 소스 · 로그 · 시크릿을 외부로 전송하거나, 출처 모를 스크립트를 내려받는 사고 |
| `Bash(wget *)` | deny | 공통 | 위와 같음 |
| `Read(./.env)` | deny | 공통 | DB 비밀번호 · 토큰이 Claude 컨텍스트와 로그에 노출되는 사고 |
| `Read(./.env.*)` | deny | 공통 | `.env.local` · `.env.prod` 등 변형 파일의 비밀 노출 |
| `Read(./secrets/**)` | deny | 공통 | 비밀 보관 폴더 내용 노출 |
| `Read(**/*.pem)` | deny | 공통 | 인증서 개인키 노출 |
| `Read(~/.ssh/**)` | deny | 공통 | SSH 개인키 노출 → 서버 · Git 원격 접근 권한 유출 |
| `Read(~/.aws/**)` | deny | 공통 | 클라우드 자격 증명 노출 |
| `Edit(./.env)` | deny | 공통 | 접속 정보를 잘못 바꿔 다른 DB(운영 등)를 바라보게 되는 사고 |
| `Edit(./.env.*)` | deny | 공통 | 위와 같음 |
| `Edit(./secrets/**)` | deny | 공통 | 비밀 파일 변조 · 손상 |
| `mcp__github__delete_file` | deny | 공통 | 원격 저장소 파일을 리뷰 없이 삭제하는 사고 |
| `mcp__github__merge_pull_request` | deny | 공통 | 사람 승인 없이 PR 이 머지되는 사고 |
| `Bash(git push *)` | ask | 공통 | 원격 반영 전에 대상 브랜치 · 커밋을 사람이 확인 |
| `Bash(npm install *)` | ask | react | `package-lock.json` 이 바뀌고 검증 안 된 패키지가 들어오는 사고(공급망) |
| `Bash(npm i *)` | ask | react | 위와 같음(축약 명령) |
| `Bash(npm ci *)` | ask | react | `node_modules` 재설치와 lock 파일 기준 설치를 사람이 확인 |
| `Bash(docker compose down *)` | ask | data-infra | 공유 DB 컨테이너가 내려가 다른 프로필 · 동료 작업이 끊기는 사고(영구 볼륨이 없으면 데이터도 사라짐) |
| `Bash(docker compose --profile *)` | ask | data-infra | 의도하지 않은 프로필 기동 · 중지로 포트 충돌 · DB 초기화 |
| `mcp__github__create_pull_request` | ask | 공통 | PR 제목 · 대상 브랜치 · 본문을 사람이 확인한 뒤 외부에 게시 |
| `mcp__github__push_files` | ask | 공통 | 로컬 Git 을 거치지 않는 원격 직접 커밋을 사람이 확인 |
| `mcp__dbhub` | ask | data-infra | AI 가 DB(MySQL 계열)에 쿼리를 날리기 전에 대상 · 쿼리를 사람이 확인 |
| `mcp__dbhub-mssql` | ask | data-infra | 위와 같음(MS-SQL) |
| `Bash(npm test *)` | allow | react | 부작용 없는 검증이라 매번 확인을 받으면 흐름만 끊김 |
| `Bash(npm run test *)` | allow | react | 위와 같음 |
| `Bash(npm run lint *)` | allow | react | 위와 같음 |
| `Bash(npm run typecheck *)` | allow | react | 위와 같음 |
| `Bash(npm run build *)` | allow | react | 로컬 산출물만 만드는 빌드 |
| `Bash(./gradlew test *)` | allow | spring | 읽기 전용 검증이라 매번 확인을 받으면 흐름만 끊김 |
| `Bash(./gradlew check *)` | allow | spring | 위와 같음 |
| `Bash(./gradlew build *)` | allow | spring | 로컬 산출물만 만드는 빌드 |
| `Bash(git status *)` | allow | 공통 | 읽기 전용 조회 |
| `Bash(git diff *)` | allow | 공통 | 읽기 전용 조회 |
| `Bash(git log *)` | allow | 공통 | 읽기 전용 조회 |
| `Bash(git add *)` | allow | 공통 | 로컬 스테이징만 바뀌고 되돌리기 쉬움 |
| `Bash(git commit *)` | allow | 공통 | 로컬 이력만 바뀌고, 시크릿은 `secret-scan` Hook 이 커밋 직전에 검사 |
| `WebFetch(domain:docs.spring.io)` | allow | spring | 공식 문서 조회. 외부 전송 위험이 낮음 |
| `WebFetch(domain:docs.gradle.org)` | allow | spring | 위와 같음 |
| `WebFetch(domain:developer.mozilla.org)` | allow | react | 위와 같음(Vanilla JS · jQuery 작업에도 쓰임) |
| `mcp__github__get_file_contents` | allow | 공통 | 읽기 전용 조회 |
| `mcp__github__list_pull_requests` | allow | 공통 | 읽기 전용 조회 |
| `mcp__github__pull_request_read` | allow | 공통 | 읽기 전용 조회 |
| `mcp__github__list_issues` | allow | 공통 | 읽기 전용 조회 |
| `mcp__item-bank__search_items` | allow | 공통 | 읽기 전용 조회. 단 실습 저장소 전용 MCP 라 실 공통 규칙에서는 빼야 함 |
| `scripts/hook-node.sh` — node 미탐지 시 차단 | Hook | 공통 | node 가 PATH 에 없는 셸에서 Hook 이 조용히 꺼지는 사고(fail-closed) |
| `hooks/block-dangerous.mjs` — `rm` 재귀+강제 변형(`-fr` · `-r -f` · `-Rf` · `--recursive --force`) | Hook | 공통 | `Bash(rm -rf *)` 글롭으로는 못 잡는 옵션 변형 삭제 |
| `hooks/block-dangerous.mjs` — `DROP TABLE/DATABASE` | Hook | 공통 | deny 패턴으로는 못 잡는 SQL 문자열을 실행 전에 차단 |
| `hooks/block-dangerous.mjs` — `prod-db` / `prod.db` | Hook | 공통 | deny 와 같은 운영 DB 차단을 정규식으로 한 번 더(이중 방어) |
| `hooks/secret-scan.mjs` — `AKIA…` 키 | Hook | 공통 | AWS 키가 커밋에 섞여 저장소에 남는 사고 |
| `hooks/secret-scan.mjs` — `password = '…'` | Hook | 공통 | 하드코딩된 비밀번호가 커밋되는 사고 |
| `hooks/secret-scan.mjs` — PEM 개인키 헤더 | Hook | 공통 | 개인키가 커밋되는 사고 |

## 추가 제안 (내 스택에 필요한데 지금 없는 규칙)

| 규칙 | 종류 | 적용 범위 | 이유 |
|---|---|---|---|
| `Bash(./mvnw test *)` · `Bash(mvn test *)` | allow | spring | JSP · 구형 Spring 프로젝트는 Maven 이 많다. 지금은 Gradle 만 허용 |
| `Bash(mvn deploy *)` · `Bash(./gradlew publish *)` | deny | spring | 사내 저장소(Nexus 등)에 산출물이 올라가는 사고 |
| `Read(~/.m2/settings.xml)` · `Read(**/*.jks)` · `Read(**/*.p12)` | deny | spring | Maven 저장소 자격 증명 · 키스토어 노출 |
| `Read(**/application-prod*.yml)` · `Read(**/application-prod*.properties)` | deny | spring | 운영 프로필 접속 정보 노출 |
| `secret-scan` 패턴에 `password:`(YAML) · `password=값`(따옴표 없는 properties) · `jdbc:…password=` 추가 | Hook | spring | 지금 정규식은 `password = '…'` 형태만 잡아서 Spring 설정 파일 형식은 통과됨 |
| `Bash(sqlcmd *)` · `Bash(mysql *)` · `Bash(mysqldump *)` | ask | data-infra | MCP 를 거치지 않는 CLI DB 접속도 사람이 확인 |
| `block-dangerous` 에 `TRUNCATE TABLE` · `WHERE` 없는 `DELETE FROM` / `UPDATE` 추가 | Hook | data-infra | MS-SQL · MySQL 데이터 대량 삭제 · 변경 사고. 지금은 `DROP` 만 막음 |
| 운영 호스트 패턴을 실 실제 이름(운영 DB · 운영 클러스터)으로 교체 · 확장 | deny · Hook | 공통 | `prod-db` · `prod.db` 는 실습용 이름이라 실제 운영 호스트는 안 잡힘 |
| `Bash(argocd app sync *)` · `Bash(argocd app rollback *)` | ask | data-infra | AI 가 배포 · 롤백을 직접 트리거하는 사고 |
| `Bash(argocd app delete *)` · `Bash(kubectl delete *)` | deny | data-infra | 애플리케이션 · 리소스 삭제 사고 |
| `Read(~/.kube/**)` · `Read(~/.config/argocd/**)` | deny | data-infra | 클러스터 · ArgoCD 토큰 노출 |
| `Edit(**/Jenkinsfile)` · `Edit(**/argocd/**)` · `Edit(**/k8s/**)` | ask | data-infra | 파이프라인 · 배포 매니페스트 변경은 사람이 확인 |
| `Bash(java -jar *jenkins-cli*)` | deny | data-infra | Jenkins 작업 실행 · 설정 변경 사고 |
| `Bash(git push * --force*)` · `Bash(git push * -f*)` | deny | 공통 | 지금 deny 는 `git push --force …` 처럼 옵션이 앞에 올 때만 잡힘. `git push origin main --force` 는 ask 로 빠짐 |
| `Bash(git commit *)` 를 allow → ask 로 재검토 | ask | 공통 | 실 공통 기준으로는 커밋 메시지 · 범위를 사람이 보는 편이 안전함 |
| `Read(**/web.config)` · `Read(**/global.asa)` · `Read(**/*.udl)` | ask | php | ASP 연결 문자열(DB 계정 · 비밀번호)이 Claude 컨텍스트에 들어가는 사고. 설정을 고칠 일이 잦아 deny 대신 ask |
| `Edit(**/web.config)` · `Edit(**/global.asa)` | ask | php | 연결 대상 DB · 세션 · 오류 표시 설정이 리뷰 없이 바뀌는 사고 |
| `secret-scan` 패턴에 연결 문자열 `Password=…;` · `Pwd=…;`(대소문자 무시) 추가 | Hook | php | ASP · ADO 연결 문자열은 `"…;Password=abc;"` 형태라 지금 정규식(`password = '…'`)에 안 걸림 |

## 추가 제안 — 웹 취약점(Sparrow 사전 차단)

지금 `.claude/settings.json` · `hooks/` 에는 웹 취약점 규칙이 없다. 아래는 Sparrow 가 잡는 행정안전부 「소프트웨어 개발보안 가이드」(2021) 구현단계 보안약점 중 **코드 패턴으로 미리 걸러 낼 수 있는 것**을 규칙 후보로 정리한 것이다.

- 방식: `PostToolUse` Hook(matcher `Edit|Write`)으로 Claude 가 방금 쓴 내용(`tool_input.new_string` · `content`)을 정규식으로 검사하고, 걸리면 exit 2 + stderr 로 보안약점 항목 이름을 돌려준다. 파일은 이미 쓰였으므로 차단이 아니라 **Claude 가 바로 고치도록 되돌려 주는 경고**다. 오탐이 많은 패턴을 `PreToolUse` 로 막으면 정상 작업까지 멈춘다.
- 이 Hook 은 Sparrow 앞단의 1차 거름망이다. Sparrow 정적 분석 결과를 대신하지 않으며, Sparrow 는 지금처럼 Jenkins 파이프라인 · 정기 점검에서 돌린다.

| 규칙 | 종류 | 적용 범위 | 막는 사고(행안부 보안약점 항목) |
|---|---|---|---|
| Java 문자열에 SQL 키워드와 `+` 연결(`"SELECT … " +`, `"… WHERE … " +`), `Statement.execute*(` | Hook | spring | SQL 삽입 |
| MyBatis XML 의 `${…}` | Hook | spring | SQL 삽입. `ORDER BY` 컬럼처럼 `#{}` 를 못 쓰는 곳은 화이트리스트 검증 여부를 사람이 확인 |
| JSP 의 `<%= request.getParameter`, `${param.…}` 직접 출력, `<c:out … escapeXml="false"` | Hook | spring | 크로스사이트 스크립트(XSS) |
| `.innerHTML =` · `.outerHTML =` · `document.write(` · `insertAdjacentHTML(` | Hook | react | XSS(Vanilla JS) |
| jQuery `.html(` · `.append(` · `$(` 에 문자열 연결 변수 | Hook | react | XSS(jQuery). 상수 HTML 은 통과하도록 변수 연결이 있을 때만 경고 |
| `dangerouslySetInnerHTML` | Hook | react | XSS(React) |
| `eval(` · `new Function(` · 문자열 인자 `setTimeout(` · `setInterval(` | Hook | react | 코드 삽입 |
| `Runtime.getRuntime().exec(` · `new ProcessBuilder(` | Hook | spring | 운영체제 명령어 삽입 |
| `new File(` · `Paths.get(` · `FileInputStream(` 과 `getParameter` 가 같은 줄 | Hook | spring | 경로 조작 및 자원 삽입 |
| `MultipartFile` 의 `transferTo(` · `getOriginalFilename()` 을 그대로 저장 경로에 사용 | Hook | spring | 위험한 형식 파일 업로드, 경로 조작 |
| `sendRedirect(request.getParameter` · `"redirect:" +` 요청값 | Hook | spring | 신뢰되지 않는 URL 주소로 자동접속 연결(오픈 리다이렉트) |
| `csrf().disable()` · `csrf(AbstractHttpConfigurer::disable)` | Hook | spring | 크로스사이트 요청 위조(CSRF) |
| `MessageDigest.getInstance("MD5"` · `"SHA-1"`, `Cipher.getInstance("DES` · `/ECB/` | Hook | spring | 취약한 암호화 알고리즘 사용 |
| 토큰 · 세션 · 비밀번호 생성 코드의 `new Random(` · `Math.random()` | Hook | 공통 | 적절하지 않은 난수 값 사용. 일반 용도 난수는 오탐이라 `token` · `session` · `password` · `otp` 단어와 같은 줄일 때만 경고 |
| `printStackTrace()` · 응답 본문에 `e.getMessage()` | Hook | spring | 오류 메시지 정보노출 |
| 빈 `catch` 블록(`catch (…) { }`) | Hook | spring | 오류 상황 대응 부재 |
| `System.out.println` · `console.log` | Hook | 공통 | 제거되지 않고 남은 디버그 코드 |
| `@CrossOrigin("*")` · `allowedOrigins("*")` | Hook | spring | 부적절한 CORS 허용(중요 정보 노출 경로) |
| ASP `.Execute(` · `.Open` 의 SQL 문자열에 `&` 연결과 `Request(` · `Request.QueryString` · `Request.Form` | Hook | php | SQL 삽입(ASP · ADO). `ADODB.Command` 파라미터 사용을 권고 |
| ASP `Response.Write Request(` · `<%= Request(` (`Server.HTMLEncode` 없이 출력) | Hook | php | XSS(ASP) |
| ASP `Server.CreateObject("WScript.Shell")` · `.Run` · `.Exec` | Hook | php | 운영체제 명령어 삽입 |
| ASP `Server.MapPath(` · `FileSystemObject` 에 `Request(` 값 | Hook | php | 경로 조작 및 자원 삽입 |
| ASP `Response.Redirect Request(` | Hook | php | 신뢰되지 않는 URL 주소로 자동접속 연결 |
| ASP `On Error Resume Next` 뒤 `Err.Number` 확인 없음 | Hook | php | 오류 상황 대응 부재 |
| `Edit(**/SecurityConfig*.java)` · `Edit(**/WEB-INF/web.xml)` | ask | spring | 인증 · 세션 · 보안 필터 설정이 리뷰 없이 바뀌는 사고 |
| `Edit(**/lucy-xss*.xml)` 등 XSS 필터 규칙 파일 | ask | spring | XSS 필터 예외가 몰래 추가되는 사고 |
| `Edit` Sparrow 분석 제외 · 오탐 처리 설정 파일(경로는 실 환경에 맞게 지정) | deny | 공통 | AI 가 Sparrow 경고를 고치는 대신 분석 대상에서 빼 버리는 사고 |

## 추가 제안 — EKS · EC2 · DB 변경 동의(동료 제안 반영)

동료 요구 두 가지를 규칙으로 옮겼다. ① EKS · EC2 는 `kubectl` · `aws` CLI 의 조회만 바로 실행하고 그 밖의 변경은 동의를 받는다. ② DB 는 복구가 오래 걸리므로, 더미 데이터가 아닌 데이터 삭제와 메타데이터 변경(`TRUNCATE` · `DROP` · `ALTER` 등)은 허락 없이 실행하지 않는다.

- **구현 주의 1(평가 순서):** 권한 규칙은 deny → ask → allow 순서로 보고, 먼저 맞는 규칙이 이긴다. `Bash(kubectl *)` 를 ask 에 넣으면 allow 에 둔 `kubectl get` 까지 매번 묻게 된다. 그래서 **조회 명령만 allow 에 넣고 `kubectl` · `aws` 전체는 어느 목록에도 넣지 않는다.** 목록에 없는 명령은 기본 모드(`defaultMode: default`)가 알아서 동의를 받는다.
- **구현 주의 2(DB 판정):** "더미 데이터만 예외"는 글롭으로 가릴 수 없다. `PreToolUse` Hook 이 SQL 을 읽어 DB · 스키마 허용 목록과 비교하고, 걸리면 차단(exit 2) 대신 `permissionDecision: "ask"` JSON 을 돌려줘야 "허락하면 실행"이 된다. 지금 `block-dangerous.mjs` 의 `DROP` 은 무조건 차단이므로 이 제안과 충돌한다. 운영 · 공유 DB 는 차단을 유지하고, 그 밖의 DB 는 ask 로 바꾸는 것을 제안한다.

| 규칙 | 종류 | 적용 범위 | 막는 사고 또는 허용 이유 |
|---|---|---|---|
| `Bash(kubectl get *)` · `Bash(kubectl describe *)` · `Bash(kubectl logs *)` · `Bash(kubectl top *)` | allow | data-infra | 상태 조회는 부작용이 없어 매번 묻지 않음 |
| `Bash(kubectl get secret*)` · `Bash(kubectl get secrets*)` · `Bash(kubectl config view --raw*)` | deny | data-infra | `get` 이지만 시크릿 값 · 클러스터 인증 정보가 그대로 출력됨 |
| 그 밖의 `kubectl`(`apply` · `edit` · `patch` · `scale` · `rollout` · `exec` · `port-forward` 등) | ask(목록에 넣지 않음) | data-infra | EKS 리소스 변경 · 컨테이너 접속을 사람이 확인. `kubectl delete` 는 위 추가 제안대로 deny 유지 |
| `Bash(aws * describe-*)` · `Bash(aws * list-*)` · `Bash(aws * get-*)` · `Bash(aws s3 ls *)` · `Bash(aws sts get-caller-identity*)` | allow | data-infra | EC2 · EKS 상태 조회 |
| `Bash(aws secretsmanager get-secret-value*)` · `Bash(aws ssm get-parameter* --with-decryption*)` · `Bash(aws ecr get-login-password*)` | deny | data-infra | `get` 이지만 비밀 값 · 레지스트리 로그인 토큰이 출력됨 |
| 그 밖의 `aws`(`ec2 run-instances` · `stop-instances` · `terminate-instances` · `modify-*` · `s3 cp` · `s3 rm` · `eks update-*` 등) | ask(목록에 넣지 않음) | data-infra | EC2 중지 · 종료, 보안 그룹 · S3 변경을 사람이 확인 |
| `Bash(aws ec2 terminate-instances*)` · `Bash(aws s3 rb*)` · `Bash(aws s3 rm * --recursive*)` | deny | data-infra | 되돌릴 수 없는 인스턴스 종료 · 버킷 삭제 |
| DB 변경 판정 Hook(`PreToolUse`, matcher `Bash\|mcp__dbhub.*`) — `DELETE` · `TRUNCATE` · `DROP` · `ALTER` · `RENAME` · `CREATE/DROP INDEX` · `GRANT` · `REVOKE` · MS-SQL `sp_rename` | Hook(ask) | data-infra | 복구에 오래 걸리는 데이터 삭제 · 스키마 변경을 허락 없이 실행하는 사고. 허용 목록(로컬 `localhost` · 더미 DB · 시드 스키마)에 해당하면 묻지 않고 통과 |
| `Bash(mysql * < *)` · `Bash(sqlcmd * -i *)` (SQL 파일 실행) | ask | data-infra | Hook 이 파일 안의 SQL 을 볼 수 없어 내용과 상관없이 확인 |
| `Bash(flyway clean*)` · `Bash(./gradlew flywayClean*)` | deny | spring | 스키마 전체 삭제 |
| `Bash(flyway migrate*)` · `Bash(liquibase update*)` | ask | spring | 마이그레이션 적용 대상 DB를 사람이 확인 |

## 추가 제안 — 장애 회고 기반 재발 방지

`incident-logs/` 4건과 기존 분석(`docs/rca/` · `docs/batch-check/report.md`)에서 **AI 가 같은 실수를 반복할 수 있는 지점**만 골랐다. 근본 원인을 아직 정하지 않은 건은 원인을 단정하지 않았다.

| 규칙 | 종류 | 적용 범위 | 막는 사고(근거) |
|---|---|---|---|
| `@Transactional` 메서드 안의 `RestTemplate` · `WebClient` · `HttpClient` 호출, 고정 횟수 반복 루프 경고 | Hook(PostToolUse) | spring | 트랜잭션이 커넥션을 오래 쥐어 풀(최대 5)이 고갈된 사고. 리포트 1건이 커넥션을 15 ~ 21초 쥠(`docs/rca/a-connection-pool.md:8`) |
| `Edit(**/application*.yml)` · `Edit(**/application*.properties)` 중 `hikari` · `datasource` 변경 | ask | spring | 풀 크기 · 대기 시간을 바꿔 증상을 가리는 사고(같은 근거) |
| 배치 재실행 명령의 `--force` · `"force":true` · 락 무시 옵션 | deny | data-infra | 앞선 실행이 아직 돌고 있는데 `force=true` 로 락을 우회해 재실행 → `mode=append` 대상에 이중 적재(`incident-logs/c-batch-duplicate/batch-job.log:97-100`) |
| 배치 재실행 전 `batch-check` Skill 로 진행 중 실행 · 락 상태 확인 | Skill | data-infra | "멈춘 것으로 보고 재실행"한 판단 착오(같은 근거 `:97`). 실제로는 진행률이 계속 오르고 있었음(`docs/batch-check/report.md:28`) |
| `secret-scan` 패턴에 `Bearer [A-Za-z0-9._-]{16,}` · `dpl_` · `btc_` 같은 사내 토큰 접두어 추가, 대상에 `*.md` 포함 | Hook | 공통 | 배포 명령 · 운영 로그를 문서에 붙이면서 토큰이 그대로 남은 사고(`incident-logs/b-deploy-5xx/deploy-history.md:20`, `incident-logs/c-batch-duplicate/batch-job.log:98`) |
| 배포 전 점검 Skill — 응답 형식 변경 여부 · 무중단 여부 · 롤백 명령 확인 | Skill | spring | 응답 형식을 바꾸는 변경을 단일 호스트 재기동(무중단 아님)으로 수업 중 배포(`incident-logs/b-deploy-5xx/deploy-history.md:15-22`). 이 건의 5xx 근본 원인은 아직 판정되지 않음(`docs/rca/b-deploy-5xx.md:6`) |
| 같은 `.sql` 변경에서 여러 테이블을 갱신하는 프로시저의 잠금 순서 경고 | Hook(PostToolUse) | data-infra | `usp_class_report:172` 와 `usp_aggregate_grades:409` 가 `grade_summary` · `submission` 을 서로 반대 순서로 잠가 MS-SQL 대기 · 데드락이 생긴 사고(`incident-logs/d-mssql-deadlock/mssql-wait.log:19-43`) |
| `Edit(**/*.sql)` 중 저장 프로시저(`CREATE/ALTER PROCEDURE`) | ask | data-infra | 운영 프로시저 변경을 사람이 확인(같은 근거) |
| 로그 출력문에 `student` · `email` · `STU-` · `user=` 값이 들어가면 경고 | Hook(PostToolUse) | 공통 | 학생 식별자 · 이메일이 운영 로그에 평문으로 남음(`incident-logs/d-mssql-deadlock/app-timeout.log:84`) |

## 주의 (다른 스택에 그대로 걸면 일을 막을 수 있는 규칙)

| 규칙 | 막히는 일 | 대안 |
|---|---|---|
| `Bash(curl *)` · `Bash(wget *)` deny | 헬스체크, 로컬 API 호출(`curl localhost:8080`), Jenkins API 확인, php · data-infra 의 설치 스크립트 | `curl localhost:*` · 사내 도메인만 allow 하고 나머지는 ask |
| `Bash(sudo *)` deny | data-infra 의 패키지 설치 · 서비스 재시작 | data-infra 저장소만 ask 로 완화 |
| `Bash(*prod-db*)` deny · `block-dangerous` 의 `prod-db` · `DROP TABLE` | 명령 문자열 전체를 보므로 `grep -r "DROP TABLE" db/`, `grep prod-db incident-logs/` 같은 **검색**도 막힘. data-infra 의 마이그레이션 · ETL 스크립트(스테이징 테이블 DROP)도 막힘 | 검색 명령(`grep` · `rg`)은 예외 처리하거나, 실행 명령(`mysql` · `sqlcmd` · `psql`)의 인자일 때만 차단 |
| `secret-scan` 의 `password = '…'` | 테스트 픽스처 · 실습 더미 비밀번호가 있는 커밋(php · spring 테스트 코드)이 막힘 | 허용 목록(테스트 경로, 알려진 더미 값) 추가 |
| `Bash(npm ci *)` · `npm install` ask | 프런트 팀은 하루에 여러 번 실행해서 확인 요청이 너무 잦음 | react 저장소에서는 `npm ci` 만 allow(lock 파일 기준이라 의존성이 바뀌지 않음) |
| `Bash(docker compose --profile *)` ask | data-infra · 로컬 통합 테스트를 자주 띄우는 팀의 흐름이 끊김 | `up` 은 allow, `down -v` 만 ask · deny |
| allow 가 `npm` · `./gradlew` 뿐 | php(`composer`, `phpunit`) · data-infra(`terraform plan`) 는 매번 확인을 받음. 막히지는 않지만 공통 파일에 이것만 두면 다른 스택만 불편 | 스택별 allow 를 저장소 `.claude/settings.json` 에 따로 둠 |
| `secret-scan` 은 명령이 `git commit` 으로 **시작할 때만** 검사 | `cd app && git commit …` 처럼 앞에 다른 명령이 붙으면 검사 없이 통과(차단이 아니라 빈틈) | 명령 안에 `git commit` 이 포함되면 검사하도록 조건 변경 |
| 웹 취약점 Hook 의 `System.out.println` · `console.log` · `new Random(` | data-infra 의 배치 · 운영 스크립트, 프런트 개발 중 디버깅 출력까지 매번 경고가 떠서 작업이 늘어짐 | `src/main` · 배포 대상 경로에만 적용하고 `scripts/` · 테스트 경로는 제외 |
| 웹 취약점 Hook 의 Java · JSP · jQuery · ASP 패턴 | php 묶음 안에서도 PHP 와 ASP 는 문법이 달라(`mysqli_query(` · `echo $_GET` vs `conn.Execute(` · `Response.Write`) 한쪽 패턴만 걸면 다른 쪽은 오탐만 나고 정작 약점은 못 잡음. 다른 팀 PHP 저장소에 ASP 패턴을 걸어도 마찬가지 | 패턴 묶음을 스택별 파일로 나눠 저장소마다 고름 |
| DB 변경 판정 Hook(`DELETE` · `TRUNCATE` 등 ask) | data-infra 의 ETL 은 스테이징 테이블을 매일 `TRUNCATE` · `DELETE` 하므로 실행할 때마다 확인 요청이 뜸 | 스테이징 스키마를 허용 목록에 넣음 |
| `kubectl` · `aws` 조회 외 ask | 클러스터를 매일 다루는 운영 담당은 `rollout restart` · `scale` 도 매번 확인 | 운영 담당 저장소에서만 자주 쓰는 변경 명령을 allow 로 추가(개인 `settings.local.json`) |
| Hook 스크립트는 stdin 파싱 · `git` 실행 실패 시 통과(fail-open) | 막지는 않지만, 실 공통 규칙으로 쓰면 "Hook 이 있으니 안전하다"고 오해할 수 있음 | 공통 규칙으로 채택할 때 fail-open 지점을 문서에 명시 |

## 공유용 프롬프트 — 다른 분이 자기 스택으로 같은 문서를 만들 때

아래를 복사해 `<…>` 만 바꿔 쓰면 이 문서와 같은 형식이 나온다.

```
이 폴더의 .claude/settings.json(permissions · hooks)과 hooks/ 의 규칙을 실 전체 개발자에게 걸 규칙 후보로 정리하려고 합니다. 기존 파일은 고치지 마세요.
제 주 스택: <예: Spring Boot / React / MS-SQL / MySQL / jQuery / JSP / ASP / ArgoCD / Jenkins>
스택 매핑: <예: JSP → spring, ASP → php, Vanilla JS · jQuery → react, MS-SQL · MySQL · ArgoCD · Jenkins → data-infra>

규칙마다 아래 표의 한 줄로 정리해 docs/rules/<내 아이디>.md 로 저장해 주세요.
| 규칙 | 종류(deny / ask / allow / Hook) | 적용 범위(공통 또는 스택 코드) | 막는 사고 또는 허용 이유 |
- 어느 스택에서든 뜻이 같은 규칙(파괴 명령, 강제 push, 운영 DB, 외부 전송, 비밀 파일 읽기, 시크릿 탐지 Hook)은 "공통"으로 둡니다.
- 특정 빌드 · 패키지 도구에만 해당하는 규칙(gradlew, npm, composer, terraform 등)은 그 스택 코드로 둡니다. 스택 코드는 spring · react · php · data-infra 가운데서만 고릅니다.
- Hook 은 스크립트 파일이 아니라 그 안의 탐지 패턴 하나를 한 줄로 적습니다.
- 표 아래에 다음 절을 둡니다.
  1. 추가 제안: 제 스택에 필요한데 지금 없는 규칙
  2. 웹 취약점: 행안부 구현단계 보안약점 중 코드 패턴으로 미리 거를 수 있는 Hook 후보(Sparrow 를 대신하지 않는다고 적기)
  3. 인프라 · DB 변경 동의: <예: kubectl · aws CLI 는 조회만 allow, DB 삭제 · DROP · TRUNCATE · ALTER 는 허락 후 실행. 더미 데이터는 예외>
  4. 장애 회고 기반 재발 방지: <예: incident-logs/ 와 Jira · Confluence 의 장애 회고>를 근거(파일:줄번호 또는 이슈 키)와 함께
  5. 주의: 다른 스택에 그대로 걸면 일을 막을 수 있는 규칙
```

- 권한 규칙은 deny → ask → allow 순서로 평가된다. "조회만 허용, 나머지는 확인" 같은 요구는 ask 에 넓은 패턴을 넣지 말고 allow 에 조회 명령만 넣는다.
- 장애 회고를 Jira · Confluence 에서 가져올 때는 이슈 키 · 페이지 제목을 근거로 적고, 회고 본문의 토큰 · 이메일 · 학생 식별자는 옮기지 않는다.
