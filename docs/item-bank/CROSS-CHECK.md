# CROSS-CHECK — legacy/item-bank-php 비즈니스 규칙 (독립 추출)

- 읽은 파일: `search.php`, `register.php`, `units.php`, `inc/db.php` (`index.php` · `inc/layout.php` · `Dockerfile` · `vendor/` 는 읽지 않음)
- `docs/` 는 읽지 않았다. 코드에 없는 규칙은 쓰지 않았고, 주석과 코드가 다르면 코드를 기준으로 했다.
- 경로는 모두 `legacy/item-bank-php/` 기준이다.

## 1. 문항 검색 — 조건 조합

### CX-01 키워드는 제목 또는 지문의 부분 일치다
- 규칙: 키워드(`q`)는 앞뒤 공백을 제거하고 100자까지만 쓰며, 비어 있지 않으면 `title` 또는 `stem` 에 `%키워드%` LIKE 로 찾는다(`%` · `_` 는 와일드카드로 동작하고 경고만 낸다).
- 근거: `search.php:85-89`, `search.php:94-101`
```php
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-02 단원은 코드 정확 일치이며 형식이 틀려도 조회한다
- 규칙: 단원(`unit`)은 `unit_code = ?` 로 정확히 일치시키고, 형식 오류 · 소문자 · 미등록 코드는 경고만 표시할 뿐 대문자 변환이나 차단 없이 입력값 그대로 조회한다.
- 근거: `search.php:109-121`, `search.php:141-143`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {   // search.php:111
$where .= " AND unit_code = ?";                                  // search.php:118
```

### CX-03 난이도를 비우면 난이도 5 문항이 빠진다 (주석과 코드 불일치)
- 규칙: 난이도가 비어 있으면 `level < 5` 조건이 붙어 난이도 5 문항은 결과에서 제외되고, 난이도 5 는 `level=5` 를 명시해야만 나온다.
- 근거: `search.php:213-216` — 주석은 "전체 난이도 검색 (1~5 모두 포함)" 이지만 코드는 5 를 제외한다. 코드를 기준으로 썼다.
```php
if ($level == '') {
    $where .= " AND level < 5";
```

### CX-04 난이도 값이 1~5 이면 일치 비교, 그 밖은 정수로 바꿔 비교한다
- 규칙: `1`~`5` 한 자리면 `level = ?` 로 비교하고, 그 밖의 값은 "난이도는 1~5 사이여야 합니다." 경고를 내되 `(int)` 로 바꿔 그대로 `level = ?` 로 조회한다(비숫자는 0 이 된다).
- 근거: `search.php:217-222`, `search.php:223-229`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
...
    $values[] = (int)$level;
```

### CX-05 태그는 이름 정확 일치이며 50자까지만 쓴다
- 규칙: 태그(`tag`)는 앞뒤 공백을 제거하고 50자로 자른 뒤, `item_tag` · `tag` 를 거쳐 이름이 정확히 같은 태그가 붙은 문항만 남긴다(부분 일치 미지원, 미등록 태그는 경고 후 0건).
- 근거: `search.php:235-249`, `search.php:258-260`
```php
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-06 단원 · 난이도 · 태그 · 키워드는 모두 AND 로 결합한다
- 규칙: 채워진 조건은 서로 AND 로 이어 붙고, 조건이 하나도 없으면 `WHERE 1=1` 에 난이도 규칙(CX-03)만 적용된다. 같은 이름의 파라미터가 배열로 오면 첫 값만 쓴다.
- 근거: `search.php:44`, `search.php:55`, `search.php:98`, `search.php:118`
```php
$where   = " WHERE 1=1";
$q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
```

## 2. 목록의 제외 조건 · 기본 정렬 · 페이지

### CX-07 검색은 `v_item_public` 뷰만 조회한다 (제외 조건 본체는 이 폴더에 없음)
- 규칙: 검색 목록과 건수는 `item` 테이블이 아니라 공개 문항 뷰 `v_item_public` 에서만 읽으므로, 어떤 문항이 제외되는지는 뷰 정의가 정한다. 뷰 정의(DB 스크립트)는 범위 밖이라 읽지 않았고, 그래서 뷰가 `status='A'` 만 남기는지는 이 폴더의 코드만으로는 확인되지 않는다.
- 근거: `search.php:519-526`
```php
$from   = " FROM v_item_public";
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```
- 간접 근거: 등록 직후 문항은 검색에 나오지 않는다는 주석(`register.php:84`)과 단원 목록의 공개 문항 수 산정(CX-18)이 같은 방향이다. 다만 주석이라 코드 근거로는 세지 않았다.

### CX-08 기본 정렬은 난이도 내림차순, 같으면 ID 오름차순이다
- 규칙: `sort` 가 비어 있거나 알 수 없는 값이면 `ORDER BY level DESC, id ASC` 를 쓴다(알 수 없는 값은 경고 후 `sort` 를 비운다).
- 근거: `search.php:317-320`, `search.php:322-326`
```php
case '':
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-09 정렬 기준별 방향 기본값이 다르다
- 규칙: `sort` 는 `id` · `title` · `unit` · `level` · `created` 만 허용하며 모두 2차 키 `id ASC`(id 정렬 제외)를 둔다. `dir` 이 `asc`/`desc` 가 아니면 무시하고, 방향을 안 준 경우 `id` · `title` · `unit` 은 오름차순, `level` · `created` 는 내림차순으로 정렬한다(`unit` 은 `unit_code, level DESC, id ASC`).
- 근거: `search.php:266-273`, `search.php:293-314`
```php
case 'level':
    if ($dir === 'asc') { $orderBy = " ORDER BY level ASC, id ASC"; } else { $orderBy = " ORDER BY level DESC, id ASC"; }
```
- 참고: `unit` 정렬은 `dir=desc` 여도 `level DESC` 가 그대로이고 `unit_code` 만 뒤집힌다(`search.php:295`).

### CX-10 한 페이지 20건, 페이지 번호는 1~999 로 보정한다
- 규칙: 한 페이지는 20건이고 `OFFSET = (page-1)*20` 이다. 숫자가 아닌 `page` 는 1 로, 1 미만은 1 로, 999 초과는 999 로 보정한다.
- 근거: `search.php:336-349`, `search.php:523`
```php
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
if ($page > 999) { $page = 999;
```

## 3. 문항 등록 검증 (`register.php`)

> 수정(update) 화면 · 코드는 검토한 파일에 없다. 수정 시 검증 규칙은 추출할 수 없어 쓰지 않았다.

### CX-11 제목은 앞뒤 공백 제거 후 5자 이상 200자 이하다
- 규칙: 제목은 `trim` 한 뒤 글자 수(`mb_strlen`)가 5 미만이면 오류, 200 초과이면 오류이며, 공백만 제외하고 세지는 않는다.
- 근거: `register.php:41`, `register.php:49-54`
```php
if (mb_strlen($title, 'UTF-8') < 5) {
if (mb_strlen($title, 'UTF-8') > 200) {
```

### CX-12 지문은 필수다
- 규칙: 지문은 `trim` 한 뒤 빈 문자열이면 오류이며, 길이 상한 검증은 없다.
- 근거: `register.php:42`, `register.php:56-58`
```php
if ($stem === '') {
    $errors[] = '지문을 입력해야 합니다.';
```

### CX-13 단원은 필수이며 등록된 단원 id 여야 한다
- 규칙: `unit_id` 가 `unit` 테이블에서 읽은 목록의 `id` 중 하나와 문자열로 일치하지 않으면 오류다.
- 근거: `register.php:26-31`, `register.php:60-68`
```php
if ((string)$u['id'] === $unitId) {
    $unitOk = true;
```

### CX-14 난이도는 1~5 한 자리 숫자여야 한다
- 규칙: 난이도가 `^[1-5]$` 에 맞지 않으면(빈 값 포함) 오류다.
- 근거: `register.php:69-72`
```php
if (!preg_match('/^[1-5]$/', $level)) {
```

### CX-15 태그는 선택 사항이며 등록된 태그 id 만 저장하고 나머지는 조용히 버린다
- 규칙: 태그를 하나도 안 골라도 등록되고, 목록에 없는 태그 id 는 오류 없이 무시한 채 유효한 것만 `item_tag` 에 저장한다.
- 근거: `register.php:73-81`, `register.php:104-113`
```php
if ((string)$t['id'] === (string)$tid) {
    $validTagIds[] = (int)$tid;
```

### CX-16 새 문항은 검수중(`R`) 상태로 저장된다
- 규칙: 등록 시 `status` 는 항상 `'R'` 이며, 문항 저장과 태그 저장은 한 트랜잭션이고 하나라도 실패하면 롤백한다.
- 근거: `register.php:85`, `register.php:92-95`, `register.php:114`, `register.php:120`
```php
VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())
```

### CX-17 새 문항 id 는 `MAX(id)+1` 로 직접 채번한다
- 규칙: `id` 는 자동 증가가 아니라 `item` 테이블을 `FOR UPDATE` 로 잠근 뒤 `COALESCE(MAX(id), 0) + 1` 로 정한다.
- 근거: `register.php:87-90`
```php
$res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
```

## 4. 단원 목록 (`units.php`)

### CX-18 단원별 문항 수는 공개(`A`) 문항만 센다
- 규칙: 단원 목록의 문항 수는 `status = 'A'` 인 문항만 세고 삭제 · 검수중은 제외하며, 단원은 학년 오름차순, 같으면 코드 오름차순으로 나열한다.
- 근거: `units.php:15-19`
```php
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
 FROM unit u ORDER BY u.grade ASC, u.code ASC
```

## 5. 확인하지 못한 것

- `v_item_public` 뷰 정의(제외 조건, `tag_names` 생성 방식): 범위 밖이라 읽지 않았다.
- 문항 수정 · 삭제 · 상태 전환(R → A) 코드: 검토한 파일에 없다.
- `index.php` · `inc/layout.php`: 화면 골격으로 보고 읽지 않았다.
