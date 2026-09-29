package com.example.item;

import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * 레거시 문항 검색({@code legacy/item-bank-php/search.php})이 쓰던 PHP 7.4 문자열 함수를 같은 결과로 옮긴 도우미.
 * Java 표준 함수와 결과가 다른 곳(공백 문자 집합, 길이 단위, 정수 변환)만 따로 둔다.
 */
final class PhpStrings {

    /** PHP {@code trim()} 이 기본으로 지우는 문자: 공백, \t, \n, \r, \0, \x0B. */
    private static final String TRIM_CHARS = " \t\n\r\0\u000B";

    /** PHP {@code is_numeric_string()} 이 숫자 앞에서 건너뛰는 공백: 공백, \t, \n, \r, \v, \f. */
    private static final String NUMERIC_LEADING_WHITESPACE = " \t\n\r\u000B\f";

    /** PHP {@code preg_match('/^[0-9]+$/')} — {@code $} 는 끝의 개행 하나 앞에서도 맞는다. */
    private static final Pattern PHP_DIGITS_ONLY = Pattern.compile("\\A[0-9]+\\n?\\z");

    private PhpStrings() {
    }

    /** PHP {@code trim($s)}. Java {@code trim()} · {@code strip()} 과 달리 위 6개 문자만 지운다. */
    static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && TRIM_CHARS.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIM_CHARS.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    /** PHP {@code strtolower($s)} — ASCII 대문자만 바꾼다. */
    static String toLowerAscii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    /** PHP {@code mb_strlen($s, 'UTF-8')} — 코드포인트 수. */
    static int mbLength(String s) {
        return s.codePointCount(0, s.length());
    }

    /** PHP {@code mb_substr($s, 0, $length, 'UTF-8')} — 앞에서 코드포인트 {@code length} 개. */
    static String mbHead(String s, int length) {
        if (mbLength(s) <= length) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, length));
    }

    /** PHP {@code preg_match('/^[0-9]+$/', $s)}. 끝의 개행 하나는 허용된다. */
    static boolean isDigitsOnly(String s) {
        return PHP_DIGITS_ONLY.matcher(s).matches();
    }

    /**
     * PHP 7.4 {@code (int)$s}.
     * 앞 공백을 건너뛰고 앞쪽의 숫자 부분만 읽는다({@code "2abc"} → 2, {@code "abc"} → 0).
     * 소수 · 지수 표기는 실수로 읽어 0 쪽으로 자르고({@code "1.9"} → 1, {@code "1e1"} → 10),
     * long 범위를 넘으면 {@link Long#MAX_VALUE} · {@link Long#MIN_VALUE} 로 맞추며, 무한대는 0 이다.
     */
    static long toInt(String s) {
        int n = s.length();
        int i = 0;
        while (i < n && NUMERIC_LEADING_WHITESPACE.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        int start = i;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int intStart = i;
        i = skipDigits(s, i);
        int intDigits = i - intStart;

        boolean isDouble = false;
        int fracDigits = 0;
        if (i < n && s.charAt(i) == '.') {
            int fracEnd = skipDigits(s, i + 1);
            fracDigits = fracEnd - (i + 1);
            if (intDigits > 0 || fracDigits > 0) {
                isDouble = true;
                i = fracEnd;
            }
        }
        if (intDigits == 0 && fracDigits == 0) {
            return 0;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            int expEnd = skipDigits(s, j);
            if (expEnd > j) {
                isDouble = true;
                i = expEnd;
            }
        }

        String number = s.substring(start, i);
        if (!isDouble) {
            BigInteger exact = new BigInteger(number);
            if (exact.bitLength() < Long.SIZE) {
                return exact.longValue();
            }
            // PHP 도 long 범위를 넘는 정수 문자열은 실수로 읽는다 → 아래에서 범위 끝 값으로 맞춘다.
        }
        double value = Double.parseDouble(number);
        if (!Double.isFinite(value)) {
            return 0;
        }
        // Java 의 (long) 변환은 범위를 넘으면 Long.MAX_VALUE · MIN_VALUE 로 맞춘다(PHP zend_dval_to_lval_cap 과 같음).
        return (long) value;
    }

    private static int skipDigits(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i;
    }
}
