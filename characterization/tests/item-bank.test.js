// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 규칙
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 모듈의 레거시 기본 주소).
//   테스트 코드에 주소를 직접 적지 않는다.
// - 응답은 fetchNormalized 로 {status, rows, count, message} 모양으로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 이관 후 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기(skipIf)를 두지 않는다.
// - 케이스 이름이 스냅샷 키다. 이름을 바꾸면 스냅샷이 새로 찍힌다.
// - 겨냥한 규칙 ID 는 docs/item-bank/BUSINESS-RULES.md 기준이다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

// 경계값 길이 — BR-04(키워드 100자), BR-12(태그 50자)
const KEYWORD_MAX_LENGTH = 100;
const TAG_MAX_LENGTH = 50;

describe('item-bank · 문항 검색(search.php)', () => {
  // ------------------------------------------------------------------
  // 정상 입력
  // ------------------------------------------------------------------

  // BR-08, BR-14, BR-16, BR-21 — 시드상 status='A' AND level<5 가 정확히 20건(페이지 크기와 같음)
  it('noParamsDefault — 파라미터 없음: 기본 난이도 · 기본 정렬 · 1페이지', async () => {
    const result = await fetchNormalized(MODULE, PATH);
    expect(result).toMatchSnapshot();
  });

  // BR-02, BR-06, BR-09 — 시드에 있는 단원 코드와 유효 난이도를 AND 로 결합
  it('unitAndLevelCombined — 단원 M5-1 + 난이도 1', async () => {
    const result = await fetchNormalized(MODULE, PATH, { unit: 'M5-1', level: '1' });
    expect(result).toMatchSnapshot();
  });

  // BR-02, BR-03, BR-11, BR-18 — 키워드 · 태그 결합 + 기본이 아닌 정렬(title asc)
  it('keywordTagSortTitle — 키워드 분수 + 태그 계산 + 제목 오름차순', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '분수', tag: '계산', sort: 'title', dir: 'asc' });
    expect(result).toMatchSnapshot();
  });

  // ------------------------------------------------------------------
  // 규칙의 경계값
  // ------------------------------------------------------------------

  // BR-09 ↔ BR-08 — 빈 난이도에서 빠지는 5 를 직접 지정
  it('levelFiveExplicit — 난이도 5 직접 지정', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '5' });
    expect(result).toMatchSnapshot();
  });

  // BR-10 — 허용 범위 [1-5] 바로 밖
  it('levelSixJustOutside — 난이도 6(범위 밖 +1)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '6' });
    expect(result).toMatchSnapshot();
  });

  // BR-21, BR-23 — 결과가 딱 20건일 때 2페이지(offset 20)
  it('secondPageAfterExactlyTwenty — 20건 결과의 2페이지', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '2' });
    expect(result).toMatchSnapshot();
  });

  // BR-22 — 페이지 상한값(경고 없이 통과하는 마지막 값)
  it('pageCapAt999 — 페이지 999', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '999' });
    expect(result).toMatchSnapshot();
  });

  // BR-22 — 페이지 상한 +1(999 로 맞추고 경고)
  it('pageOverCap — 페이지 1000', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '1000' });
    expect(result).toMatchSnapshot();
  });

  // BR-04 — 자르기 경계(100자는 자르지 않음). 멀티바이트 글자 수 기준인지도 본다
  it('keywordExactly100 — 키워드 100자(멀티바이트)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '가'.repeat(KEYWORD_MAX_LENGTH) });
    expect(result).toMatchSnapshot();
  });

  // BR-03, BR-05 — 한 글자 경고가 나는 유일한 길이
  it('keywordSingleChar — 키워드 한 글자', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '분' });
    expect(result).toMatchSnapshot();
  });

  // ------------------------------------------------------------------
  // 빈 값 · 누락 (누락은 noParamsDefault)
  // ------------------------------------------------------------------

  // BR-08, BR-16, BR-17, BR-19, BR-22 — 누락과 빈 문자열의 차이(빈 page 는 경고 없이 1페이지)
  it('allParamsEmpty — 모든 파라미터 빈 문자열', async () => {
    const result = await fetchNormalized(MODULE, PATH, {
      q: '',
      unit: '',
      level: '',
      tag: '',
      sort: '',
      dir: '',
      page: '',
    });
    expect(result).toMatchSnapshot();
  });

  // BR-03, BR-08, BR-16, BR-18 — trim 뒤 빈값, trim · 소문자 변환 뒤 level 정렬로 인식되는지
  it('whitespaceOnly — 공백뿐인 키워드 · 난이도, 공백 · 대문자 정렬 기준', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '  ', level: ' ', sort: ' LEVEL ' });
    expect(result).toMatchSnapshot();
  });

  // ------------------------------------------------------------------
  // 이상한 값
  // ------------------------------------------------------------------

  // BR-10, BR-22 — 음수: page 는 숫자 아님 분기, level 은 (int)-1 조회 분기
  it('negativePageAndLevel — 페이지 -1 · 난이도 -1', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '-1', level: '-1' });
    expect(result).toMatchSnapshot();
  });

  // BR-04, BR-12, BR-13 — 아주 긴 문자열: 100자 · 50자로 잘리고, 잘린 태그는 등록되지 않은 태그
  it('keywordAndTagTooLong — 키워드 101자 · 태그 51자', async () => {
    const result = await fetchNormalized(MODULE, PATH, {
      q: '가'.repeat(KEYWORD_MAX_LENGTH + 1),
      tag: '태'.repeat(TAG_MAX_LENGTH + 1),
    });
    expect(result).toMatchSnapshot();
  });

  // BR-06, BR-07 — 형식은 맞지만 unit 테이블에 없는 코드
  it('unknownUnitCode — 없는 단원 코드 Z99-99', async () => {
    const result = await fetchNormalized(MODULE, PATH, { unit: 'Z99-99' });
    expect(result).toMatchSnapshot();
  });

  // BR-01, BR-17, BR-19 — 배열 파라미터(첫 값만 사용) + 알 수 없는 sort · dir
  it('arrayParamsAndBadSort — q[] 두 개 + sort=hack · dir=up', async () => {
    const result = await fetchNormalized(MODULE, PATH, { 'q[]': ['분수', '덧셈'], sort: 'hack', dir: 'up' });
    expect(result).toMatchSnapshot();
  });
});
