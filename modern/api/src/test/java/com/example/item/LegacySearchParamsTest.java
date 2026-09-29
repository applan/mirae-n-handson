package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/** 원본 쿼리스트링을 PHP $_GET 규칙대로 해석하는지 — BR-01. */
@ActiveProfiles("test")
class LegacySearchParamsTest {

    @Test
    @DisplayName("쿼리스트링이 없으면 모두 빈값, page 만 \"1\"")
    void parseNullGivesDefaults() {
        LegacySearchParams params = LegacySearchParams.parse(null);

        assertThat(params).isEqualTo(new LegacySearchParams("", "", "", "", "", "", "1"));
    }

    @Test
    @DisplayName("일반 파라미터는 URL 디코딩(+ 는 공백)해서 읽는다")
    void parseDecodesValues() {
        LegacySearchParams params = LegacySearchParams.parse(
            "q=%EB%B6%84%EC%88%98&unit=M5-1&level=1&tag=%EA%B3%84%EC%82%B0&sort=+LEVEL+&dir=asc&page=2");

        assertThat(params).isEqualTo(new LegacySearchParams("분수", "M5-1", "1", "계산", " LEVEL ", "asc", "2"));
    }

    @Test
    @DisplayName("빈 값은 빈 문자열로 남고, page= 도 빈 문자열이다")
    void parseKeepsEmptyValues() {
        LegacySearchParams params = LegacySearchParams.parse("q=&unit=&level=&tag=&sort=&dir=&page=");

        assertThat(params.page()).isEmpty();
        assertThat(params.q()).isEmpty();
    }

    @Test
    @DisplayName("배열 파라미터 q[] 는 첫 값을 쓴다")
    void parseArrayUsesFirstValue() {
        LegacySearchParams params = LegacySearchParams.parse(
            "q%5B%5D=%EB%B6%84%EC%88%98&q%5B%5D=%EB%8D%A7%EC%85%88&q[]=x");

        assertThat(params.q()).isEqualTo("분수");
    }

    @Test
    @DisplayName("같은 이름이 반복되면 마지막 값, 배열 뒤의 일반 값은 배열을 덮는다")
    void parseRepeatedKeyUsesLastValue() {
        assertThat(LegacySearchParams.parse("level=1&level=3").level()).isEqualTo("3");
        assertThat(LegacySearchParams.parse("level[]=1&level=3").level()).isEqualTo("3");
        assertThat(LegacySearchParams.parse("level=3&level[]=1").level()).isEqualTo("1");
    }

    @Test
    @DisplayName("키가 있는 배열은 처음 들어간 키의 값을 쓴다")
    void parseKeyedArrayKeepsInsertionOrder() {
        assertThat(LegacySearchParams.parse("tag[b]=%EA%B3%84%EC%82%B0&tag[a]=x").tag()).isEqualTo("계산");
        assertThat(LegacySearchParams.parse("tag[a]=1&tag[b]=2&tag[a]=3").tag()).isEqualTo("3");
    }

    @Test
    @DisplayName("중첩 배열의 첫 값은 문자열 \"Array\" 가 된다")
    void parseNestedArrayBecomesArrayString() {
        assertThat(LegacySearchParams.parse("q[0][0]=x").q()).isEqualTo("Array");
    }

    @Test
    @DisplayName("잘못된 퍼센트 인코딩은 그대로 두고, 값 없는 이름은 빈 문자열")
    void parseLeavesInvalidEscapes() {
        LegacySearchParams params = LegacySearchParams.parse("q=%zz%2&unit");

        assertThat(params.q()).isEqualTo("%zz%2");
        assertThat(params.unit()).isEmpty();
    }
}
