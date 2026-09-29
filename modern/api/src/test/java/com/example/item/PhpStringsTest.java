package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/** PHP 7.4 문자열 함수와 같은 결과를 내는지 — 레거시 search.php 의 입력 정규화 기반. */
@ActiveProfiles("test")
class PhpStringsTest {

    @Test
    @DisplayName("trim: 공백 · 탭 · 개행 · NUL · 수직탭만 지우고 전각 공백은 남긴다")
    void trimRemovesOnlyPhpWhitespace() {
        assertThat(PhpStrings.trim(" \t\n\r\0\u000B분수 \n")).isEqualTo("분수");
        assertThat(PhpStrings.trim("　분수")).isEqualTo("　분수");
        assertThat(PhpStrings.trim("\f분수")).isEqualTo("\f분수");
    }

    @Test
    @DisplayName("toLowerAscii: ASCII 대문자만 소문자로 바꾼다")
    void toLowerAsciiChangesOnlyAscii() {
        assertThat(PhpStrings.toLowerAscii(" LEVEL ")).isEqualTo(" level ");
        assertThat(PhpStrings.toLowerAscii("İÀ")).isEqualTo("İÀ");
    }

    @Test
    @DisplayName("mbLength · mbHead: 코드포인트 단위로 세고 자른다")
    void mbFunctionsCountCodePoints() {
        String emoji = "😀";
        assertThat(PhpStrings.mbLength(emoji + "가")).isEqualTo(2);
        assertThat(PhpStrings.mbHead(emoji + emoji + "가", 2)).isEqualTo(emoji + emoji);
        assertThat(PhpStrings.mbHead("가나", 5)).isEqualTo("가나");
    }

    @Test
    @DisplayName("isDigitsOnly: 숫자만, 끝 개행 하나는 허용(preg_match 의 $)")
    void isDigitsOnlyAllowsOneTrailingNewline() {
        assertThat(PhpStrings.isDigitsOnly("12")).isTrue();
        assertThat(PhpStrings.isDigitsOnly("12\n")).isTrue();
        assertThat(PhpStrings.isDigitsOnly("12\n\n")).isFalse();
        assertThat(PhpStrings.isDigitsOnly("-1")).isFalse();
        assertThat(PhpStrings.isDigitsOnly("")).isFalse();
        assertThat(PhpStrings.isDigitsOnly(" 1")).isFalse();
    }

    @Test
    @DisplayName("toInt: 앞쪽 숫자 부분만 읽는다")
    void toIntReadsLeadingNumber() {
        assertThat(PhpStrings.toInt("abc")).isZero();
        assertThat(PhpStrings.toInt("")).isZero();
        assertThat(PhpStrings.toInt("-1")).isEqualTo(-1);
        assertThat(PhpStrings.toInt("+5")).isEqualTo(5);
        assertThat(PhpStrings.toInt("2abc")).isEqualTo(2);
        assertThat(PhpStrings.toInt(" \t7")).isEqualTo(7);
        assertThat(PhpStrings.toInt("0x1A")).isZero();
    }

    @Test
    @DisplayName("toInt: 소수 · 지수 표기는 실수로 읽어 0 쪽으로 자른다")
    void toIntTruncatesDecimalAndExponent() {
        assertThat(PhpStrings.toInt("1.9")).isEqualTo(1);
        assertThat(PhpStrings.toInt("-1.9")).isEqualTo(-1);
        assertThat(PhpStrings.toInt(".5")).isZero();
        assertThat(PhpStrings.toInt("1e1")).isEqualTo(10);
        assertThat(PhpStrings.toInt("1e")).isEqualTo(1);
    }

    @Test
    @DisplayName("toInt: long 범위를 넘으면 끝 값으로 맞추고 무한대는 0")
    void toIntSaturatesOnOverflow() {
        assertThat(PhpStrings.toInt("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(PhpStrings.toInt("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
        assertThat(PhpStrings.toInt("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(PhpStrings.toInt("1e999")).isZero();
    }
}
