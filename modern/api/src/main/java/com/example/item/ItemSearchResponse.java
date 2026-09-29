package com.example.item;

import java.util.List;

/**
 * 문항 검색 응답. 동작 보존 테스트 정규화 결과와 같은 모양({@code rows, count, message})이다.
 *
 * @param rows    현재 페이지 행(최대 20)
 * @param count   조건에 맞는 전체 건수
 * @param message 전체 건수가 0 이면 "검색 결과가 없습니다", 아니면 null
 *                — BR-23, {@code legacy/item-bank-php/search.php:657-659}
 */
public record ItemSearchResponse(List<ItemSearchRow> rows, long count, String message) {

    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    static ItemSearchResponse of(List<ItemSearchRow> rows, long count) {
        return new ItemSearchResponse(rows, count, count == 0 ? NO_RESULT_MESSAGE : null);
    }
}
