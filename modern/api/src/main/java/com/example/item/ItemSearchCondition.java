package com.example.item;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 문항 검색 조건. {@link LegacySearchParams} 를 레거시 규칙대로 정규화한 값이다.
 * 근거는 모두 {@code legacy/item-bank-php/search.php} 줄번호다. 레거시의 경고 문구는 응답 모양에 없어 옮기지 않았다.
 *
 * @param keywordPattern LIKE 패턴({@code %키워드%}). 키워드가 없으면 null
 * @param unitCode       단원 코드(입력값 그대로). 없으면 null
 * @param levelEquals    이 난이도와 같은 문항만. null 이면 {@code level < 5}(BR-08)
 * @param tagName        태그 이름. 없으면 null
 * @param matchesNothing 난이도가 정수 범위를 벗어나 어떤 문항과도 같을 수 없음
 * @param sort           정렬
 * @param page           1 ~ 999 페이지
 */
record ItemSearchCondition(
    String keywordPattern,
    String unitCode,
    Integer levelEquals,
    String tagName,
    boolean matchesNothing,
    Sort sort,
    int page) {

    /** 키워드 최대 길이(글자) — BR-04, {@code search.php:86-89}. */
    static final int KEYWORD_MAX_LENGTH = 100;
    /** 태그 이름 최대 길이(글자) — BR-12, {@code search.php:240-243}. */
    static final int TAG_MAX_LENGTH = 50;
    /** 난이도를 비웠을 때 조회하는 상한(미만) — BR-08, {@code search.php:214-215}. 주석("1~5 모두")과 다르다. */
    static final int LEVEL_DEFAULT_CAP = 5;
    /** 한 페이지 건수 — BR-21, {@code search.php:349}. */
    static final int PAGE_SIZE = 20;
    /** 최대 페이지 — BR-22, {@code search.php:345-348}. */
    static final int PAGE_MAX = 999;

    private static final String VALID_LEVEL = "[1-5]";

    static ItemSearchCondition from(LegacySearchParams params) {
        // 키워드 — BR-03 · BR-04 · BR-05, search.php:85-104. % · _ 는 와일드카드 그대로 둔다(search.php:94-97).
        String q = PhpStrings.mbHead(PhpStrings.trim(params.q()), KEYWORD_MAX_LENGTH);
        String keywordPattern = q.isEmpty() ? null : "%" + q + "%";

        // 단원 — BR-06 · BR-07, search.php:109-120. 형식 · 대소문자 · 미등록이어도 입력값 그대로 조회한다.
        String unit = PhpStrings.trim(params.unit());
        String unitCode = unit.isEmpty() ? null : unit;

        // 난이도 — BR-08 · BR-09 · BR-10, search.php:211-230.
        String level = PhpStrings.trim(params.level());
        Integer levelEquals = null;
        boolean matchesNothing = false;
        if (level.matches(VALID_LEVEL)) {
            levelEquals = Integer.valueOf(level);
        } else if (!level.isEmpty()) {
            long cast = PhpStrings.toInt(level);
            if (cast < Integer.MIN_VALUE || cast > Integer.MAX_VALUE) {
                matchesNothing = true;
            } else {
                levelEquals = (int) cast;
            }
        }

        // 태그 — BR-11 · BR-12 · BR-13, search.php:235-261. 자른 뒤 정확히 일치.
        String tag = PhpStrings.mbHead(PhpStrings.trim(params.tag()), TAG_MAX_LENGTH);
        String tagName = tag.isEmpty() ? null : tag;

        Sort sort = sortOf(params.sort(), params.dir());
        int page = pageOf(params.page());
        return new ItemSearchCondition(keywordPattern, unitCode, levelEquals, tagName, matchesNothing, sort, page);
    }

    /** 현재 페이지 요청 — offset {@code (page - 1) * 20}(BR-21, search.php:349, 523). */
    Pageable pageable() {
        return PageRequest.of(page - 1, PAGE_SIZE, sort);
    }

    /** 정렬 — BR-16 ~ BR-19, search.php:266-327. */
    static Sort sortOf(String rawSort, String rawDir) {
        String sort = PhpStrings.toLowerAscii(PhpStrings.trim(rawSort));
        String dir = PhpStrings.toLowerAscii(PhpStrings.trim(rawDir));
        if (!dir.equals("asc") && !dir.equals("desc")) {
            dir = "";
        }
        Sort idAsc = Sort.by(Sort.Order.asc("id"));
        return switch (sort) {
            case "id" -> dir.equals("desc") ? Sort.by(Sort.Order.desc("id")) : idAsc;
            case "title" -> Sort.by(dir.equals("desc") ? Sort.Order.desc("title") : Sort.Order.asc("title"))
                .and(idAsc);
            case "unit" -> Sort.by(dir.equals("desc") ? Sort.Order.desc("unit.code") : Sort.Order.asc("unit.code"))
                .and(Sort.by(Sort.Order.desc("level")))
                .and(idAsc);
            case "level" -> Sort.by(dir.equals("asc") ? Sort.Order.asc("level") : Sort.Order.desc("level"))
                .and(idAsc);
            case "created" -> Sort.by(dir.equals("asc") ? Sort.Order.asc("createdAt") : Sort.Order.desc("createdAt"))
                .and(idAsc);
            // 빈값과 알 수 없는 기준은 기본 정렬: 난이도 내림차순, id 오름차순. dir 은 쓰지 않는다.
            default -> Sort.by(Sort.Order.desc("level")).and(idAsc);
        };
    }

    /** 페이지 — BR-22, search.php:336-348. trim 하지 않는다. */
    static int pageOf(String rawPage) {
        long page = 1;
        if (PhpStrings.isDigitsOnly(rawPage)) {
            page = PhpStrings.toInt(rawPage);
        }
        if (page < 1) {
            page = 1;
        }
        if (page > PAGE_MAX) {
            page = PAGE_MAX;
        }
        return (int) page;
    }
}
