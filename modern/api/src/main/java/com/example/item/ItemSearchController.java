package com.example.item;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 검색 API — 레거시 {@code /search.php} 의 이관 경로({@code characterization/lib/target.mjs} PATH_ALIASES).
 * 파라미터 이름은 레거시와 같다: {@code q, unit, level, tag, sort, dir, page}.
 * 배열 파라미터({@code q[]}) · 반복 키를 PHP 와 같게 해석하려고 원본 쿼리스트링을 그대로 넘긴다.
 */
@RestController
@RequestMapping("/api/items")
public class ItemSearchController {

    private final ItemSearchService itemSearchService;

    public ItemSearchController(ItemSearchService itemSearchService) {
        this.itemSearchService = itemSearchService;
    }

    /** {@code GET /api/items/search} — 공개 문항 검색. 조건이 이상해도 오류 대신 0건으로 응답한다. */
    @GetMapping("/search")
    public ItemSearchResponse search(HttpServletRequest request) {
        return itemSearchService.search(LegacySearchParams.parse(request.getQueryString()));
    }
}
