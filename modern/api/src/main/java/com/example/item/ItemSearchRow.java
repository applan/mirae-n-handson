package com.example.item;

import java.util.List;

/**
 * 문항 검색 결과 한 행. 필드는 동작 보존 테스트 정규화 결과의 행과 같다({@code id, title, unit, level, tags}).
 * {@code unit} 은 단원 코드, {@code tags} 는 태그 이름 목록(태그 id 순 — {@code v_item_public.tag_names} 와 같음).
 */
public record ItemSearchRow(Integer id, String title, String unit, Integer level, List<String> tags) {

    static ItemSearchRow from(Item item) {
        return new ItemSearchRow(
            item.getId(),
            item.getTitle(),
            item.getUnit().getCode(),
            item.getLevel(),
            item.getTags().stream().map(Tag::getName).toList());
    }
}
