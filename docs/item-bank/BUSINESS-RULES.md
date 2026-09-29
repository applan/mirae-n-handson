# legacy/item-bank-php 비즈니스 규칙 후보

> 분석 범위: 문항 검색 조건 조합(단원 · 난이도 · 태그 · 키워드), 등록 검증, 목록 기본 정렬 · 제외 조건 · 페이지.
> 진입점 · 의존 관계는 [ARCHITECTURE.md](ARCHITECTURE.md), 테이블 · 뷰는 [ERD.md](ERD.md) 에 있다.
> 근거 경로는 `legacy/item-bank-php/` 기준(예: `search.php:215`), 스키마는 `db/mariadb/init/01-schema.sql:줄`.
> 코드(조건문 · SQL)에 있는 것만 규칙으로 올렸다. 주석에만 있는 내용은 비고에 "주석만 있음"으로 남겼다.
> 확신도: **확실** = 조건문 · SQL 로 확인 / **추정** = 이름 · 주석으로 짐작. 코드를 읽기만 했고 실행해 확인하지는 않았다.

## 0. 먼저 알아 둘 것

- **문항 수정 기능이 없다.** 모듈 전체에 `UPDATE` 문이 없고(ERD.md 3절), 수정 화면 · 수정 검증 코드도 없다. 그래서 "수정 시 검증" 규칙은 뽑지 않았다.
- 검색은 조건을 **막지 않고 경고만** 하는 쪽이다. 형식이 틀린 단원 코드 · 없는 태그 · 범위 밖 난이도도 그대로 조회한다. 등록은 반대로 **오류를 내고 저장하지 않는다.** 같은 값(난이도 · 단원)을 두 화면이 다르게 다룬다(BR-10, BR-29, BR-30 비고).
- 가장 큰 주의점은 **BR-08**: 난이도를 비우면 주석과 달리 난이도 5 문항이 빠진다.

---

## 1. 검색 조건 조합 (`search.php` `buildSearchQuery`)

### BR-01
- 규칙: 검색 파라미터가 배열로 들어오면 첫 번째 값만 쓴다.
- 근거: `search.php:51`, `search.php:55`, `search.php:59`, `search.php:63`, `search.php:67`, `search.php:71`, `search.php:75`, `search.php:79`
- 근거 코드:
  ```php
  // 파라미터 꺼내기 (배열로 들어오면 첫 값만 쓴다)
  $q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
  ```
- 확신도: 확실
- 비고: 따라서 태그 · 단원은 한 번에 하나만 조건으로 걸 수 있다(등록은 태그를 여러 개 받는다 — BR-31).

### BR-02
- 규칙: 키워드 · 단원 · 난이도 · 태그 조건이 여러 개 있으면 모두 AND 로 결합한다.
- 근거: `search.php:44`, `search.php:98`, `search.php:118`, `search.php:215`, `search.php:218`, `search.php:226`, `search.php:244`
- 근거 코드:
  ```php
  $where    = " WHERE 1=1";
  $where .= " AND unit_code = ?";
  $where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
  ```
- 확신도: 확실
- 비고: 건수 쿼리와 목록 쿼리가 같은 `$where` 를 쓴다(`search.php:525-526`).

### BR-03
- 규칙: 키워드가 있으면 앞뒤 공백을 지운 뒤 제목 또는 지문에 키워드가 포함된(`LIKE '%키워드%'`) 문항을 찾는다.
- 근거: `search.php:85`, `search.php:97-98`
- 근거 코드:
  ```php
  $q = trim($q);
  $like = '%' . $q . '%';
  $where .= " AND (title LIKE ? OR stem LIKE ?)";
  ```
- 확신도: 확실
- 비고: 키워드 안의 `%` · `_` 를 이스케이프하지 않아 와일드카드로 동작한다. 코드는 경고만 한다(`search.php:94-96`). 값은 `?` 바인딩이다.

### BR-04
- 규칙: 키워드가 100자를 넘으면 앞 100자만 쓰고 경고를 표시한다.
- 근거: `search.php:86-89`, `search.php:494`
- 근거 코드:
  ```php
  if (mb_strlen($q, 'UTF-8') > 100) {
      $q = mb_substr($q, 0, 100, 'UTF-8');
  ```
- 확신도: 확실
- 비고: 매직 넘버 100. 입력 필드에도 `maxlength="100"` 이 따로 적혀 있다(`search.php:494`) — 같은 값이 두 곳에 있다.

### BR-05
- 규칙: 키워드가 한 글자이면 경고만 표시하고 그대로 검색한다.
- 근거: `search.php:91-93`
- 근거 코드:
  ```php
  if (mb_strlen($q, 'UTF-8') === 1) {
      $warnings[] = '키워드가 한 글자라 결과가 많을 수 있습니다.';
  ```
- 확신도: 확실
- 비고: 최소 길이 제한은 없다.

### BR-06
- 규칙: 단원 코드가 있으면 앞뒤 공백을 지운 뒤 `unit_code` 와 정확히 같은 문항만 찾는다.
- 근거: `search.php:109`, `search.php:118-120`
- 근거 코드:
  ```php
  $unit = trim($unit);
  $where .= " AND unit_code = ?";
  ```
- 확신도: 확실
- 비고: 경고 문구는 "입력값 그대로 조회합니다"(`search.php:116`)라서 대소문자를 구분하는 것처럼 보이지만, `unit` 테이블 콜레이션이 `utf8mb4_unicode_ci`(`01-schema.sql:18`)라 소문자 입력도 일치할 수 있다. 실행해 확인하지 않았다.

### BR-07
- 규칙: 단원 코드가 형식(`^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$`)에 맞지 않거나, 소문자를 포함하거나, `unit` 테이블에 없으면 경고만 표시하고 입력값 그대로 조회한다.
- 근거: `search.php:111-117`, `search.php:124-143`
- 근거 코드:
  ```php
  if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
      // 형식이 달라도 그대로 조회한다 (결과는 대개 0건)
  $warnings[] = '등록되지 않은 단원 코드입니다: ' . $unit;
  ```
- 확신도: 확실
- 비고: 정규식이 소문자 첫 글자를 허용하는데(`[A-Za-z]`) 바로 아래에서 소문자를 경고한다 — 형식 규칙이 두 줄에 나뉘어 있다. 단원 존재 확인은 요약 문구용 추가 조회라 결과 건수에는 영향이 없다.

### BR-08
- 규칙: 난이도 파라미터가 비어 있으면 **난이도 5 미만(`level < 5`) 문항만** 조회한다.
- 근거: `search.php:213-216`
- 근거 코드:
  ```php
  // 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
  if ($level == '') {
      $where .= " AND level < 5";
  ```
- 확신도: 확실
- 비고: **주석과 코드 불일치.** 주석은 "1~5 모두 포함"이지만 코드는 난이도 5를 뺀다. 파일 머리 주석도 "빈값 허용"이라고만 한다(`search.php:8`). 이 조건은 요약(`$summary`)에 들어가지 않아, 다른 조건이 없으면 화면에는 "조건 없이 검색했습니다"(`search.php:355-356`)가 나오면서 난이도 5가 빠진다. 폼의 "전체" 선택지(`search.php:192`)도 빈값이므로 같은 결과다. 매직 넘버 5.

### BR-09
- 규칙: 난이도가 `1`~`5` 한 자리 숫자이면 그 난이도와 같은 문항만 조회한다.
- 근거: `search.php:217-221`
- 근거 코드:
  ```php
  else if (preg_match('/^[1-5]$/', $level)) {
      $where .= " AND level = ?";
  ```
- 확신도: 확실
- 비고: 난이도 5는 명시적으로 `level=5` 를 고를 때만 검색된다(BR-08 과 대비).

### BR-10
- 규칙: 난이도가 비어 있지 않고 `1`~`5` 가 아니면 경고를 표시하고, 입력값을 정수로 바꾼 값과 같은 문항을 조회한다(숫자가 아니면 0).
- 근거: `search.php:223-229`
- 근거 코드:
  ```php
  // 1~5 밖의 값은 그대로 정수로 비교한다 (대개 0건)
  $warnings[] = '난이도는 1~5 사이여야 합니다.';
  $values[] = (int)$level;
  ```
- 확신도: 확실
- 비고: **같은 규칙이 다르게 구현됨.** 등록은 같은 정규식 `^[1-5]$` 로 검사해 저장을 거부한다(BR-30, `register.php:70`). 검색은 거부하지 않고 조회한다. DB 에는 1~5 CHECK 제약이 없다(`01-schema.sql:25` 주석만).

### BR-11
- 규칙: 태그가 있으면 앞뒤 공백을 지운 뒤, 이름이 정확히 같은 태그가 붙은 문항만 조회한다.
- 근거: `search.php:235`, `search.php:244-247`
- 근거 코드:
  ```php
  $where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
          . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
  ```
- 확신도: 확실
- 비고: `%` · `_` 가 들어 있으면 "부분 일치를 지원하지 않습니다" 경고만 하고 정확 일치로 조회한다(`search.php:237-239`). `tag` 테이블 콜레이션도 `_ci` 여서 대소문자 무시 일치일 수 있다(미확인).

### BR-12
- 규칙: 태그 이름이 50자를 넘으면 앞 50자만 쓰고 경고를 표시한다.
- 근거: `search.php:240-243`
- 근거 코드:
  ```php
  if (mb_strlen($tag, 'UTF-8') > 50) {
      $tag = mb_substr($tag, 0, 50, 'UTF-8');
  ```
- 확신도: 확실
- 비고: 매직 넘버 50. `tag.name VARCHAR(50)`(`01-schema.sql:35-40`, ERD.md 1절)과 같은 값이다.

### BR-13
- 규칙: 태그가 등록된 태그 목록에 없으면 경고만 표시하고 그대로 조회한다.
- 근거: `search.php:250-260`
- 근거 코드:
  ```php
  // 등록된 태그인지 확인 (없어도 조회는 그대로 한다 → 0건)
  if (!$tagKnown) {
      $warnings[] = '등록되지 않은 태그입니다: ' . $tag;
  ```
- 확신도: 확실(경고 · 조회). 결과 건수는 추정.
- 비고: 등록 여부는 PHP 의 `===`(대소문자 구분)로 비교하지만(`search.php:253`), 조회는 `tag` 콜레이션 `utf8mb4_unicode_ci`(`01-schema.sql:40`) 비교다. 대소문자만 다른 태그를 넣으면 "등록되지 않은 태그" 경고가 뜨면서도 결과가 나올 수 있다(코드로 추론, 실행 확인 안 함). 주석 "→ 0건"(`search.php:250`)은 이 경우와 맞지 않을 수 있다.

---

## 2. 목록 제외 조건 · 정렬 · 페이지

### BR-14
- 규칙: 검색 목록 · 건수는 `status = 'A'` 인 문항만 대상으로 한다(`D` 삭제 · `R` 검수중 제외).
- 근거: `search.php:522`, `search.php:526`, `01-schema.sql:70`
- 근거 코드:
  ```php
  $from   = " FROM v_item_public";
  ```
  ```sql
  WHERE i.status = 'A';
  ```
- 확신도: 확실
- 비고: 뷰는 `item JOIN unit`(INNER)이라 단원이 없는 문항도 빠지지만 FK(`01-schema.sql:32`)가 있어 실제로 생길 수는 없다. 뷰 주석 "등록 화면이 모두 이 뷰를 기준으로 삼는다"(`01-schema.sql:51`)는 코드와 다르다 — `register.php` 는 뷰를 쓰지 않는다.

### BR-15
- 규칙: 단원 목록의 문항 수는 `status = 'A'` 인 문항만 센다.
- 근거: `units.php:17`
- 근거 코드:
  ```php
  (SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
  ```
- 확신도: 확실
- 비고: **같은 규칙이 두 방식으로 구현됨.** 검색은 뷰 경유(BR-14), 단원 목록은 `'A'` 리터럴을 직접 쓴다. 단원 목록에는 BR-08 의 `level < 5` 가 없어서, 단원 목록의 문항 수와 "난이도 전체" 검색 건수는 난이도 5 문항 수만큼 다를 수 있다.

### BR-16
- 규칙: 정렬 기준(`sort`)이 비어 있으면(앞뒤 공백 제거 · 소문자 변환 후) 난이도 내림차순, 같은 난이도는 ID 오름차순으로 정렬한다. 이때 정렬 방향(`dir`)은 쓰지 않는다. 알 수 없는 정렬 기준도 같은 기본 정렬로 처리한다(BR-17).
- 근거: `search.php:266`, `search.php:317-320`, `search.php:322-326`
- 근거 코드:
  ```php
  $sort = strtolower(trim($sort));
  case '':
      // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
      $orderBy = " ORDER BY level DESC, id ASC";
      break;
  ```
- 확신도: 확실
- 비고: 주석과 코드 일치. `case ''` 안에 `dir` 분기가 없어 `sort=&dir=asc` 여도 결과가 같다. 공백만 있는 `sort` 도 빈값으로 처리된다. 같은 `ORDER BY` 문자열이 `sort=level`(asc 가 아닐 때, `search.php:305`)에서도 쓰인다.

### BR-17
- 규칙: 정렬 기준이 비어 있지 않고 `id` · `title` · `unit` · `level` · `created` 도 아니면 경고를 표시하고, 정렬 기준을 빈값으로 바꾼 뒤 기본 정렬(BR-16)을 쓴다. 빈값이면 경고 없이 기본 정렬이다.
- 근거: `search.php:317-320`, `search.php:322-326`
- 근거 코드:
  ```php
  default:
      $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
      $sort = '';
      $orderBy = " ORDER BY level DESC, id ASC";
  ```
- 확신도: 확실
- 비고: 기본 정렬 문자열이 `search.php:319` 와 `search.php:325` 두 곳에 중복돼 있다. 정렬 기준은 소문자로 바꿔 비교한다(`search.php:266`).

### BR-18
- 규칙: 정렬 기준별 순서는 아래와 같고, `dir` 이 없으면 `id` · `title` · `unit` 은 오름차순, `level` · `created` 는 내림차순이다. `id` 외에는 보조 정렬로 `id ASC` 를 붙인다.
  | sort | dir 생략 · asc | desc |
  |---|---|---|
  | `id` | `id ASC` | `id DESC` |
  | `title` | `title ASC, id ASC` | `title DESC, id ASC` |
  | `unit` | `unit_code ASC, level DESC, id ASC` | `unit_code DESC, level DESC, id ASC` |
  | `level` | asc: `level ASC, id ASC` / 생략: `level DESC, id ASC` | `level DESC, id ASC` |
  | `created` | asc: `created_at ASC, id ASC` / 생략: `created_at DESC, id ASC` | `created_at DESC, id ASC` |
- 근거: `search.php:276-315`, `search.php:328-331`
- 근거 코드:
  ```php
  $orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
  if ($dir === 'asc') {
      $orderBy = " ORDER BY level ASC, id ASC";
  ```
- 확신도: 확실
- 비고: `unit` 정렬은 방향과 관계없이 2순위가 항상 `level DESC` 다(`search.php:295,297`). `created` 는 정렬은 되지만 열 머리글 링크가 없다(`search.php:392-397`). 표시용 기본 방향(`search.php:330`)은 SQL 분기와 같은 결과다.

### BR-19
- 규칙: 정렬 방향(`dir`)이 `asc` · `desc` 가 아니면 경고를 표시하고(빈값이면 경고 없이) 방향을 지정하지 않은 것으로 처리한다.
- 근거: `search.php:267-273`
- 근거 코드:
  ```php
  if ($dir !== 'asc' && $dir !== 'desc') {
      if ($dir !== '') {
          $warnings[] = '정렬 방향은 asc 또는 desc 만 가능합니다.';
  ```
- 확신도: 확실
- 비고: 없음.

### BR-20
- 규칙: 난이도 열 머리글 링크는 현재 난이도로 정렬 중이 아니면 첫 클릭에 내림차순(`dir=desc`)을 건다. 다른 열은 오름차순이다.
- 근거: `search.php:400`, `search.php:410-413`
- 근거 코드:
  ```php
  } else if ($key === 'level') {
      // 난이도는 첫 클릭에 내림차순
      $nextDir = 'desc';
  ```
- 확신도: 확실
- 비고: 화면 링크 규칙이다. 현재 정렬 열을 다시 누르면 방향이 뒤집힌다(`search.php:402-409`).

### BR-21
- 규칙: 검색 결과는 한 페이지에 20건씩, `(page - 1) * 20` 건을 건너뛰고 보여 준다.
- 근거: `search.php:349`, `search.php:523`, `search.php:553`, `search.php:685`
- 근거 코드:
  ```php
  $offset = ($page - 1) * 20;
  $limit  = " LIMIT 20 OFFSET " . (int)$offset;
  ```
- 확신도: 확실
- 비고: 매직 넘버 20이 세 곳(`349`, `523`, `553`)에 따로 적혀 있다. 주석 "한 페이지 20건"(`search.php:334`)과 일치.

### BR-22
- 규칙: 페이지 번호가 비어 있으면 경고 없이 1페이지, 비어 있지 않은데 숫자가 아니면 1페이지를 보여 주고 경고하며, 0 이하이면 1, 999 를 넘으면 999 로 맞추고 경고한다.
- 근거: `search.php:336-348`
- 근거 코드:
  ```php
  if (preg_match('/^[0-9]+$/', $pageRaw)) {              // search.php:337
  } else if ($pageRaw !== '' && $pageRaw !== '1') {      // search.php:339
      $warnings[] = '페이지 번호가 올바르지 않아 1페이지를 표시합니다.';
  if ($page > 999) {                                     // search.php:345
      $page = 999;
  ```
- 확신도: 확실
- 비고: 매직 넘버 999. `page=0` 은 정규식을 통과해 경고 없이 1로 바뀐다(`search.php:342-344`). 음수(`-1`)는 정규식에 걸려 경고가 난다. 빈값은 경고 조건(`search.php:339`)에서 빠진다. 전체 페이지 수보다 큰 페이지 번호는 막지 않는다.

### BR-23
- 규칙: 총 건수가 0이면 결과 표 대신 "검색 결과가 없습니다" 문구를 보여 준다.
- 근거: `search.php:657-659`
- 근거 코드:
  ```php
  if ($total === 0) {
      echo '<p id="message">검색 결과가 없습니다</p>' . "\n";
  ```
- 확신도: 확실
- 비고: 페이지 이동 링크는 전체 페이지가 2 이상일 때만 나온다(`search.php:685-686`).

### BR-24
- 규칙: 단원 목록 화면과 단원 선택 목록은 학년 오름차순, 같은 학년이면 단원 코드 오름차순으로 보여 준다. 태그 선택 목록은 태그 ID 오름차순이다.
- 근거: 단원 `units.php:19`, `register.php:27`, `search.php:156` / 태그 `register.php:34`, `search.php:178`
- 근거 코드:
  ```php
  ORDER BY u.grade ASC, u.code ASC
  $res = $conn->query("SELECT id, name FROM tag ORDER BY id ASC");
  ```
- 확신도: 확실
- 비고: 같은 정렬이 세 파일에 각각 적혀 있다(구현은 같다). 검색 폼의 단원 선택 목록은 학년별 `optgroup` 으로 묶는다(`search.php:452-462`).

---

## 3. 문항 등록 검증 · 저장 (`register.php`)

### BR-25
- 규칙: 등록 입력값(제목 · 지문 · 단원 · 난이도)은 앞뒤 공백을 지운 뒤 검증한다.
- 근거: `register.php:41-44`
- 근거 코드:
  ```php
  $title  = isset($_POST['title'])  ? trim((string)$_POST['title'])  : '';
  $stem   = isset($_POST['stem'])   ? trim((string)$_POST['stem'])   : '';
  ```
- 확신도: 확실
- 비고: 제목 주석 "공백 제외 아님, 앞뒤 공백만 제거"(`register.php:48`)와 일치 — 가운데 공백은 글자 수에 들어간다.

### BR-26
- 규칙: 제목이 5자(UTF-8 문자 기준) 미만이면 등록을 거부한다.
- 근거: `register.php:49-51`, `register.php:143`
- 근거 코드:
  ```php
  if (mb_strlen($title, 'UTF-8') < 5) {
      $errors[] = '제목은 5자 이상 입력해야 합니다.';
  ```
- 확신도: 확실
- 비고: 매직 넘버 5(난이도 상한 5 와 값만 같고 의미는 다르다). 라벨 "제목 (5자 이상)"(`register.php:143`)에도 따로 적혀 있다.

### BR-27
- 규칙: 제목이 200자를 넘으면 등록을 거부한다.
- 근거: `register.php:52-54`
- 근거 코드:
  ```php
  if (mb_strlen($title, 'UTF-8') > 200) {
      $errors[] = '제목은 200자를 넘을 수 없습니다.';
  ```
- 확신도: 확실
- 비고: 매직 넘버 200. `item.title VARCHAR(200)`(`01-schema.sql:23`)과 같은 값. 화면 라벨에는 상한이 적혀 있지 않다(`register.php:143`).

### BR-28
- 규칙: 지문이 비어 있으면(앞뒤 공백 제거 후) 등록을 거부한다.
- 근거: `register.php:56-58`
- 근거 코드:
  ```php
  if ($stem === '') {
      $errors[] = '지문을 입력해야 합니다.';
  ```
- 확신도: 확실
- 비고: 지문 길이 상한 검사는 없다(`stem TEXT`, `01-schema.sql:24`).

### BR-29
- 규칙: 단원 ID 가 `unit` 테이블에서 읽은 단원 목록에 없으면 등록을 거부한다.
- 근거: `register.php:27`, `register.php:60-68`
- 근거 코드:
  ```php
  if ((string)$u['id'] === $unitId) {
      $unitOk = true;
  $errors[] = '단원을 선택해야 합니다.';
  ```
- 확신도: 확실
- 비고: **같은 성격의 검사가 다르게 구현됨.** 검색은 없는 단원 코드를 경고만 하고 조회한다(BR-07). 등록은 단원 ID, 검색은 단원 코드로 판단한다.

### BR-30
- 규칙: 난이도가 `1`~`5` 한 자리 숫자가 아니면 등록을 거부한다.
- 근거: `register.php:70-72`, `register.php:163`
- 근거 코드:
  ```php
  if (!preg_match('/^[1-5]$/', $level)) {
      $errors[] = '난이도는 1~5 사이여야 합니다.';
  ```
- 확신도: 확실
- 비고: 검색(`search.php:217`)과 같은 정규식이지만 검색은 거부하지 않는다(BR-10). 선택 목록 루프 `for ($i = 1; $i <= 5; $i++)`(`register.php:163`)에도 5가 따로 있다.

### BR-31
- 규칙: 선택한 태그 ID 중 `tag` 테이블 목록에 없는 것은 오류 없이 버리고, 있는 것만 저장한다. 태그는 선택하지 않아도 된다.
- 근거: `register.php:34`, `register.php:74-81`, `register.php:104`
- 근거 코드:
  ```php
  if ((string)$t['id'] === (string)$tid) {
      $validTagIds[] = (int)$tid;
  ```
- 확신도: 확실
- 비고: 중복 제거가 없다. 같은 태그 ID 가 두 번 오면 `item_tag` PK(`item_id`, `tag_id`, `01-schema.sql:42-48`) 위반으로 저장이 실패할 것으로 보인다(코드로 추론, 실행 확인 안 함).

### BR-32
- 규칙: 검증 오류가 하나라도 있으면 저장하지 않고 모든 오류 문구를 함께 보여 준다.
- 근거: `register.php:83`, `register.php:133-139`
- 근거 코드:
  ```php
  if (empty($errors)) {
  ```
- 확신도: 확실
- 비고: 검증은 첫 오류에서 멈추지 않고 모든 항목을 검사한다.

### BR-33
- 규칙: 새 문항은 상태 `'R'`(검수중)로 저장한다.
- 근거: `register.php:93-94`
- 근거 코드:
  ```php
  "INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)
   VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
  ```
- 확신도: 확실
- 비고: 스키마 기본값은 `'A'`(`01-schema.sql:26`)지만 INSERT 가 `'R'` 을 명시한다. 주석 "검수 완료 전까지 검색 화면에 나오지 않는다"(`register.php:84`)와 안내 문구 "검수 완료 후 검색에 노출됩니다"(`register.php:116`)의 **검수 절차(R → A 전이)는 주석만 있음** — 이 모듈에 UPDATE 가 없다. 검색에 안 나오는 것 자체는 BR-14 로 확인된다.

### BR-34
- 규칙: 새 문항 ID 는 현재 최대 ID + 1(문항이 없으면 1)로 정한다.
- 근거: `register.php:87-90`
- 근거 코드:
  ```php
  $res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
  ```
- 확신도: 확실
- 비고: `item.id` 에 AUTO_INCREMENT 가 없다(`01-schema.sql:21`). 동시 등록 시 충돌을 막는지는 미확인(ERD.md 4절).

### BR-35
- 규칙: 등록 시 `created_at` 과 `updated_at` 을 모두 DB 현재 시각(`NOW()`)으로 저장한다.
- 근거: `register.php:94`
- 근거 코드:
  ```php
  VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
  ```
- 확신도: 확실
- 비고: 애플리케이션 시각이 아니라 DB 서버 시각이다.

### BR-36
- 규칙: ID 채번(`SELECT ... FOR UPDATE`) · 문항 INSERT · 태그 INSERT 를 한 트랜잭션으로 처리한다. 문항 INSERT 나 태그 INSERT 의 `execute()` 가 실패(false)하면 롤백하고, 오류 로그를 남기고, "저장 중 오류가 발생했습니다"를 보여 준다.
- 근거: `register.php:85`, `register.php:87`, `register.php:99-101`, `register.php:108-110`, `register.php:114`, `register.php:119-122`
- 근거 코드:
  ```php
  $conn->begin_transaction();                          // register.php:85
  if (!$stmt->execute()) {                             // register.php:99, 108
      throw new RuntimeException('문항 저장 실패');
  } catch (Exception $e) {                             // register.php:119-122
      $conn->rollback();
      Log::error('item register failed', array('msg' => $e->getMessage()));
      $errors[] = '저장 중 오류가 발생했습니다.';
  ```
- 확신도: 확실(롤백 대상은 `execute()` 실패만). 그 밖의 실패 동작은 추정.
- 비고: 등록 성공 후 입력 폼을 비운다(`register.php:117-118`). **"어떤 실패든 모두 롤백"은 아니다.** `mysqli_report(MYSQLI_REPORT_OFF)`(`inc/db.php:20`)라 mysqli 는 예외를 던지지 않고, 코드가 잡는 실패는 `execute()` 의 false 뿐이다. `commit()` 반환값은 검사하지 않는다(`register.php:114`). `query()`(`register.php:87`) · `prepare()`(`register.php:92`, `105`) 가 false 를 돌려주면 이어지는 메서드 호출이 PHP 7.4(`Dockerfile:1`)에서 `Error` 를 내고, `Error` 는 `catch (Exception $e)` 에 잡히지 않아 롤백 · 안내 문구 없이 중단될 것으로 추정한다(실행 확인 안 함). `item` · `item_tag` 는 InnoDB 다(`01-schema.sql:33`, `01-schema.sql:48`).

---

## 4. 매직 넘버

| 값 | 위치 | 추정 의미 |
|---|---|---|
| `5` | `search.php:215` (`level < 5`) | 난이도 상한. "전체" 검색에서 최상(5) 문항을 빼는 경계 — 주석(`search.php:213`)과 불일치 |
| `1`~`5` (`^[1-5]$`) | `search.php:217`, `register.php:70` | 난이도 허용 범위 |
| `1`, `5` (루프 경계) | `register.php:163` | 등록 폼 난이도 선택지 범위 |
| `1`~`5` (라벨 배열) | `search.php:193-199` | 난이도 표시명: 1 매우 쉬움 · 2 쉬움 · 3 보통 · 4 어려움 · 5 최상 |
| `5` | `register.php:49`, `register.php:143` | 제목 최소 글자 수 |
| `200` | `register.php:52` | 제목 최대 글자 수 (`item.title VARCHAR(200)`) |
| `100` | `search.php:86-87`, `search.php:494` | 검색 키워드 최대 글자 수 |
| `1` | `search.php:91` | "결과가 많을 수 있음" 경고를 내는 키워드 길이 |
| `50` | `search.php:240-241` | 검색 태그 이름 최대 글자 수 (`tag.name VARCHAR(50)`) |
| `20` | `search.php:349`, `search.php:523`, `search.php:553` | 검색 페이지 크기 |
| `999` | `search.php:345-347` | 검색 최대 페이지 번호 |
| `{1,2}` · `{1,2}` | `search.php:111` | 단원 코드 형식 `영문 1자 + 숫자 1~2자리 - 숫자 1~2자리`(예: `M5-1`, `search.php:113`) |
| `'R'` | `register.php:94` | 신규 문항 상태 = 검수중 |
| `'A'` | `units.php:17`, `01-schema.sql:26`, `01-schema.sql:70` | 공개 상태 코드 |
| `0` (+1) | `register.php:87` | 문항이 하나도 없을 때 첫 ID 를 1로 만드는 기본값 |

## 5. 주석만 있는 내용 · 불일치 모음

| 내용 | 위치 | 코드 상태 |
|---|---|---|
| 난이도 빈값이면 "1~5 모두 포함" | `search.php:213` | 코드는 `level < 5`(BR-08) — 불일치 |
| 검수 완료 후 검색에 노출 | `register.php:84`, `register.php:116` | R → A 전이 코드 없음 — 주석만 있음 |
| 등록 화면도 `v_item_public` 을 기준으로 삼음 | `01-schema.sql:51` | `register.php` 는 뷰를 쓰지 않음 — 불일치 |
| `D`(삭제) 상태 | `01-schema.sql:26` | 이 모듈 코드에서 `D` 를 쓰거나 만드는 곳 없음 — 주석만 있음 |
| 단원 코드는 대문자로, 입력값 그대로 조회 | `search.php:115-116` | 콜레이션 `_ci`(`01-schema.sql:18`)로 소문자도 일치할 수 있음 — 미확인 |

## 6. 검증 이력

| 날짜 | 규칙 | 조치 | 내용 |
|---|---|---|---|
| 2026-09-29 | BR-16 | 수정 | 표본 점검. `sort` 를 `trim` · 소문자 변환한 뒤 비교하는 점(`search.php:266`), 기본 정렬에서 `dir` 을 쓰지 않는 점, 알 수 없는 기준도 같은 정렬로 떨어지는 점이 빠져 있어 규칙 · 근거 · 인용에 추가 |
| 2026-09-29 | BR-36 | 수정 | 표본 점검. "하나라도 실패하면 모두 롤백"은 과장이다. 롤백하는 경우는 `execute()` 가 false 일 때뿐이다(`register.php:99`, `108`, `inc/db.php:20`). ID 채번 `FOR UPDATE`(`87`)와 `Log::error`(`121`)가 빠져 있어 추가했다. 인용 코드는 떨어진 줄을 줄번호와 함께 적도록 바꿈 |
| 2026-09-29 | BR-17 | 수정 | 같은 함수(`search.php` 정렬 `switch`) 재확인. "허용 목록이 아니면 경고"는 과장이다. 빈값은 경고 없이 `case ''` 로 간다(`search.php:317-320`). `$sort = ''` 로 바꾸는 동작도 추가 |
| 2026-09-29 | BR-18 · BR-19 · BR-20 | 확인 · 변경 없음 | 같은 정렬 코드(`search.php:266-331`, `392-415`)를 다시 읽음. 코드와 일치 |
| 2026-09-29 | BR-31 · BR-32 · BR-33 · BR-34 · BR-35 | 확인 · 변경 없음 | 같은 등록 코드(`register.php:40-139`)를 다시 읽음. 코드와 일치 |
| 2026-09-29 | BR-14 | 교차 검증 · 변경 없음 · 사람 확인 | CROSS-CHECK CX-07 은 "뷰가 `status='A'` 만 남기는지 확인 안 됨"이었다. `search.php:522` `FROM v_item_public` 과 `01-schema.sql:70` `WHERE i.status = 'A'` 로 BR-14 가 맞음을 확인 |
| 2026-09-29 | BR-36 | 교차 검증 · 변경 없음 · 사람 확인 | CROSS-CHECK CX-16 은 "하나라도 실패하면 롤백"이었다. 예외는 `execute()` false 일 때만 던지고(`register.php:99-101`, `108-110`) `catch (Exception)`(`119`)만 롤백하며 `MYSQLI_REPORT_OFF`(`inc/db.php:20`)라 BR-36 이 맞음. `query()` · `prepare()` 실패 시 동작은 여전히 추정 |
| 2026-09-29 | BR-04 · BR-12 · BR-16 · BR-19 · BR-25 | 교차 검증 · 변경 없음 | CROSS-CHECK 에 경고 · `dir` 무시 · trim 범위가 빠져 있던 항목. `search.php:88`, `242`, `266`, `317-320`, `269-271`, `register.php:43-44` 로 BR 쪽이 맞음을 확인 |
| 2026-09-29 | BR-08 | 교차 검증 · 변경 없음 | 양쪽 일치(CX-03). 일치해도 같은 주석에 속았을 수 있어 코드를 다시 봄. 주석(`search.php:213`)이 아니라 코드 `level < 5`(`search.php:214-215`)가 기준임을 확인 |
| 2026-09-29 | BR-22 | 수정 | 교차 검증 중 코드 확인. 빈 `page` 는 경고 없이 1페이지다(`search.php:339` 경고 조건에서 빠짐). 규칙 · 인용 코드에 추가 |
| 2026-09-29 | BR-13 | 수정 | 교차 검증 중 코드 확인. 등록 여부는 `===`(`search.php:253`)로, 조회는 `_ci` 콜레이션(`01-schema.sql:40`)으로 비교해 대소문자만 다른 태그는 경고와 결과가 함께 나올 수 있음을 비고에 추가(추정, 실행 확인 안 함) |

## 이관 회고

> 대상: `modern/api` 문항 검색(`/api/items/search`) · 단원 목록(`/api/units`) 이관 코드. "테스트가 잡았나"는 `characterization/` 테스트 코드와 정규화(`lib/normalize.mjs:4` — `status, rows, count, message` 만 비교)를 읽고 판단했다. 테스트를 실행해 확인하지는 않았다.

| 바뀐 지점 | 레거시 동작 | AI가 만든 동작 | 테스트가 잡았나 | 다음에 막을 방법 |
|---|---|---|---|---|
| 단원 목록 문항 수(BR-15) | 단원마다 공개(`A`) 문항 수 `item_count` 를 보여 준다(`units.php:16-19`) | `GET /api/units` 응답에 `item_count` 가 없다(`UnitResponse.java:4`). 레거시에 없던 `id` 가 생겼다 | 못 잡음 — `example-units` 는 레거시 전용(`example-units.test.js:11`) | |
| 검색 입력 경고(BR-04 · 05 · 07 · 10~13 · 17 · 19 · 22) | 키워드 · 단원 · 난이도 · 태그 · 정렬 · 페이지가 이상하면 경고 문구를 함께 보여 주고 조회한다 | 조회 결과는 같지만 경고를 하나도 돌려주지 않는다(`ItemSearchResponse.java:13`) | 못 잡음 — 정규화 결과에 경고가 없다 | |
| 검색 중 DB 오류 · 연결 실패 | HTTP 200 + "검색 중 오류가 발생했습니다" / "DB 연결 실패" 문구(`search.php:714-718`, `726-729`) | HTTP 500 + "서버 내부 오류"(`GlobalExceptionHandler.java:53-57`) | 못 잡음 — DB 장애 케이스가 없다 | |
| 검색 요청 메서드 | 메서드를 가리지 않고 쿼리스트링으로 검색한다 | `GET` 만 받고 그 밖은 405(`ItemSearchController.java:24`) | 못 잡음 — GET 케이스만 있다 | |
| 쿼리스트링 특수문자 | 인코딩 안 된 `\|` · `{` · `}` 등도 받아 조회한다 | Tomcat 이 400 으로 막는다(추정, 실행 확인 안 함) | 못 잡음 — 테스트가 URL 인코딩해서 보낸다(`target.mjs:38-46`) | |
| 파라미터 1000개 초과 · 잘못된 UTF-8(BR-01) | PHP `max_input_vars`(1000) 넘는 변수는 버리고, 잘못된 바이트는 그대로 조회한다 | 개수 제한 없이 모두 읽고, 잘못된 바이트는 U+FFFD 로 바꾼다(`LegacySearchParams.java:26-47`, `144`) | 못 잡음 — 해당 케이스가 없다 | |
| 결과 행 태그 목록 길이(BR-14) | 뷰의 `GROUP_CONCAT` 이 1024바이트에서 자른다(`01-schema.sql:64`) | 태그를 모두 돌려준다(`ItemSearchRow.java:17`) | 못 잡음 — 시드 태그가 짧다 | |
