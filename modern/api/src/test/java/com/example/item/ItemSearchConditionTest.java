package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/** 검색 파라미터 → 조건 정규화. 규칙 ID 는 docs/item-bank/BUSINESS-RULES.md 기준. */
@ActiveProfiles("test")
class ItemSearchConditionTest {

    private static ItemSearchCondition condition(String q, String unit, String level, String tag,
                                                 String sort, String dir, String page) {
        return ItemSearchCondition.from(new LegacySearchParams(q, unit, level, tag, sort, dir, page));
    }

    private static ItemSearchCondition defaults() {
        return condition("", "", "", "", "", "", "1");
    }

    @Test
    @DisplayName("BR-08: 파라미터가 없으면 조건 없음, 난이도는 level < 5, 기본 정렬, 1페이지")
    void defaultsUseLevelCapAndDefaultSort() {
        ItemSearchCondition c = defaults();

        assertThat(c.keywordPattern()).isNull();
        assertThat(c.unitCode()).isNull();
        assertThat(c.levelEquals()).isNull();
        assertThat(c.tagName()).isNull();
        assertThat(c.matchesNothing()).isFalse();
        assertThat(c.sort()).isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(c.pageable().getOffset()).isZero();
        assertThat(c.pageable().getPageSize()).isEqualTo(ItemSearchCondition.PAGE_SIZE);
    }

    @Test
    @DisplayName("BR-03 · BR-04: 키워드는 trim 뒤 100자에서 자르고 % · _ 는 그대로 LIKE 패턴에 둔다")
    void keywordIsTrimmedTruncatedAndNotEscaped() {
        assertThat(condition("  분수 ", "", "", "", "", "", "1").keywordPattern()).isEqualTo("%분수%");
        assertThat(condition("가".repeat(101), "", "", "", "", "", "1").keywordPattern())
            .isEqualTo("%" + "가".repeat(100) + "%");
        assertThat(condition("가".repeat(100), "", "", "", "", "", "1").keywordPattern())
            .isEqualTo("%" + "가".repeat(100) + "%");
        assertThat(condition("5_%", "", "", "", "", "", "1").keywordPattern()).isEqualTo("%5_%%");
        assertThat(condition("   ", "", "", "", "", "", "1").keywordPattern()).isNull();
    }

    @Test
    @DisplayName("BR-06 · BR-07: 단원 코드는 trim 만 하고 형식 · 대소문자와 무관하게 그대로 쓴다")
    void unitCodeIsUsedAsIs() {
        assertThat(condition("", " m5-1 ", "", "", "", "", "1").unitCode()).isEqualTo("m5-1");
        assertThat(condition("", "Z99-99", "", "", "", "", "1").unitCode()).isEqualTo("Z99-99");
    }

    @Test
    @DisplayName("BR-09 · BR-10: 1~5 는 그 값, 그 밖은 PHP (int) 값으로 비교한다")
    void levelUsesValueOrPhpIntCast() {
        assertThat(condition("", "", "5", "", "", "", "1").levelEquals()).isEqualTo(5);
        assertThat(condition("", "", " 1 ", "", "", "", "1").levelEquals()).isEqualTo(1);
        assertThat(condition("", "", "6", "", "", "", "1").levelEquals()).isEqualTo(6);
        assertThat(condition("", "", "-1", "", "", "", "1").levelEquals()).isEqualTo(-1);
        assertThat(condition("", "", "abc", "", "", "", "1").levelEquals()).isZero();
        assertThat(condition("", "", "2abc", "", "", "", "1").levelEquals()).isEqualTo(2);
        assertThat(condition("", "", "1.9", "", "", "", "1").levelEquals()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-10: 정수 범위를 넘는 난이도는 어떤 문항과도 같을 수 없다")
    void hugeLevelMatchesNothing() {
        assertThat(condition("", "", "99999999999999999999", "", "", "", "1").matchesNothing()).isTrue();
    }

    @Test
    @DisplayName("BR-11 · BR-12: 태그는 trim 뒤 50자에서 자른다")
    void tagIsTrimmedAndTruncated() {
        assertThat(condition("", "", "", " 계산 ", "", "", "1").tagName()).isEqualTo("계산");
        assertThat(condition("", "", "", "태".repeat(51), "", "", "1").tagName()).isEqualTo("태".repeat(50));
    }

    @Test
    @DisplayName("BR-16 · BR-17: 빈 정렬 · 알 수 없는 정렬은 기본 정렬이고 dir 은 무시한다")
    void unknownSortFallsBackToDefault() {
        Sort defaultSort = Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id"));

        assertThat(ItemSearchCondition.sortOf("", "asc")).isEqualTo(defaultSort);
        assertThat(ItemSearchCondition.sortOf("hack", "up")).isEqualTo(defaultSort);
    }

    @Test
    @DisplayName("BR-18 · BR-19: 기준별 정렬과 기본 방향, 대소문자 · 공백은 정규화한다")
    void sortByKeyAndDirection() {
        assertThat(ItemSearchCondition.sortOf("id", "")).isEqualTo(Sort.by(Sort.Order.asc("id")));
        assertThat(ItemSearchCondition.sortOf("id", "DESC")).isEqualTo(Sort.by(Sort.Order.desc("id")));
        assertThat(ItemSearchCondition.sortOf("title", "up"))
            .isEqualTo(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")));
        assertThat(ItemSearchCondition.sortOf("unit", "desc"))
            .isEqualTo(Sort.by(Sort.Order.desc("unit.code"), Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(ItemSearchCondition.sortOf(" LEVEL ", ""))
            .isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(ItemSearchCondition.sortOf("level", "asc"))
            .isEqualTo(Sort.by(Sort.Order.asc("level"), Sort.Order.asc("id")));
        assertThat(ItemSearchCondition.sortOf("created", ""))
            .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("BR-22: 페이지 빈값 · 숫자 아님 · 음수는 1, 0 은 1, 999 초과는 999, 끝 개행은 허용")
    void pageIsNormalized() {
        assertThat(ItemSearchCondition.pageOf("")).isEqualTo(1);
        assertThat(ItemSearchCondition.pageOf("abc")).isEqualTo(1);
        assertThat(ItemSearchCondition.pageOf("-1")).isEqualTo(1);
        assertThat(ItemSearchCondition.pageOf("0")).isEqualTo(1);
        assertThat(ItemSearchCondition.pageOf("999")).isEqualTo(999);
        assertThat(ItemSearchCondition.pageOf("1000")).isEqualTo(999);
        assertThat(ItemSearchCondition.pageOf("99999999999999999999")).isEqualTo(999);
        assertThat(ItemSearchCondition.pageOf("3\n")).isEqualTo(3);
        assertThat(ItemSearchCondition.pageOf(" 3")).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-21: offset 은 (page - 1) * 20")
    void pageableUsesOffsetOfTwenty() {
        assertThat(condition("", "", "", "", "", "", "2").pageable().getOffset()).isEqualTo(20);
        assertThat(condition("", "", "", "", "", "", "999").pageable().getOffset()).isEqualTo(19960);
    }
}
