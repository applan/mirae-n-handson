package com.example.item;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 문항 검색 요청 파라미터. 원본 쿼리스트링을 PHP {@code $_GET} 규칙대로 해석한 값이다(정규화 전 원문).
 *
 * <ul>
 *   <li>같은 이름이 여러 번 오면 뒤의 값이 앞의 값을 덮는다({@code q=a&q=b} → {@code b}).</li>
 *   <li>{@code q[]=a&q[]=b} 처럼 배열로 오면 첫 값을 쓴다 — BR-01, {@code legacy/item-bank-php/search.php:53-80}.</li>
 *   <li>{@code page} 가 없으면 {@code "1"} — {@code search.php:76}.</li>
 * </ul>
 */
record LegacySearchParams(String q, String unit, String level, String tag, String sort, String dir, String page) {

    private static final String DEFAULT_PAGE = "1";
    /** PHP 가 배열을 문자열로 바꿀 때의 값. */
    private static final String PHP_ARRAY_STRING = "Array";
    private static final int HEX_RADIX = 16;
    private static final int PERCENT_ESCAPE_LENGTH = 3;

    /** 원본 쿼리스트링({@code request.getQueryString()}, 없으면 null)을 해석한다. */
    static LegacySearchParams parse(String rawQuery) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = urlDecode(eq < 0 ? pair : pair.substring(0, eq));
                String value = eq < 0 ? "" : urlDecode(pair.substring(eq + 1));
                register(vars, name, value);
            }
        }
        return new LegacySearchParams(
            first(vars.get("q"), ""),
            first(vars.get("unit"), ""),
            first(vars.get("level"), ""),
            first(vars.get("tag"), ""),
            first(vars.get("sort"), ""),
            first(vars.get("dir"), ""),
            first(vars.get("page"), DEFAULT_PAGE));
    }

    /** {@code is_array($v) ? (string)reset($v) : (string)$v}. 값이 없으면 기본값. */
    private static String first(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String s) {
            return s;
        }
        Map<?, ?> array = (Map<?, ?>) value;
        if (array.isEmpty()) {
            return "";
        }
        Object head = array.values().iterator().next();
        return head instanceof String s ? s : PHP_ARRAY_STRING;
    }

    /** PHP php_register_variable_ex 의 이름 규칙 중 이 화면에 필요한 부분: 앞 공백(' ') 제거, {@code name[]} · {@code name[key]}. */
    private static void register(Map<String, Object> vars, String rawName, String value) {
        int nameStart = 0;
        while (nameStart < rawName.length() && rawName.charAt(nameStart) == ' ') {
            nameStart++;
        }
        String name = rawName.substring(nameStart);
        int bracket = name.indexOf('[');
        if (bracket >= 0 && name.indexOf(']', bracket) < 0) {
            // 닫는 괄호가 없으면 PHP 는 '[' 를 '_' 로 바꾼 일반 이름으로 본다.
            name = name.substring(0, bracket) + '_' + name.substring(bracket + 1);
            bracket = -1;
        }
        String base = (bracket < 0 ? name : name.substring(0, bracket)).replace(' ', '_').replace('.', '_');
        if (base.isEmpty()) {
            return;
        }
        if (bracket < 0) {
            vars.put(base, value);
            return;
        }

        Map<String, Object> target = arrayAt(vars, base);
        int pos = bracket;
        while (true) {
            int close = name.indexOf(']', pos);
            String key = name.substring(pos + 1, close);
            boolean last = close + 1 >= name.length()
                || name.charAt(close + 1) != '['
                || name.indexOf(']', close + 1) < 0;
            String resolvedKey = key.isEmpty() ? nextIndex(target) : key;
            if (last) {
                target.put(resolvedKey, value);
                return;
            }
            target = arrayAt(target, resolvedKey);
            pos = close + 1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arrayAt(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    /** PHP 배열의 다음 정수 키: 0 이상 정수 키 중 가장 큰 값 + 1, 없으면 0. */
    private static String nextIndex(Map<String, Object> array) {
        long next = 0;
        for (String key : array.keySet()) {
            if (key.matches("0|[1-9][0-9]{0,17}")) {
                next = Math.max(next, Long.parseLong(key) + 1);
            }
        }
        return Long.toString(next);
    }

    /** PHP {@code urldecode()}: {@code +} → 공백, 올바른 {@code %XX} 만 바이트로 바꾸고 나머지는 그대로 둔다. */
    private static String urlDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '+') {
                out.write(' ');
            } else if (b == '%' && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) {
                int high = Character.digit(bytes[i + 1], HEX_RADIX);
                int low = Character.digit(bytes[i + 2], HEX_RADIX);
                out.write(high * HEX_RADIX + low);
                i += PERCENT_ESCAPE_LENGTH - 1;
            } else {
                out.write(b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(byte b) {
        return Character.digit(b, HEX_RADIX) >= 0;
    }
}
