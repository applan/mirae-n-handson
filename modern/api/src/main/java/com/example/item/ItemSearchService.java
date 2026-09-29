package com.example.item;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문항 검색 서비스 — 레거시 {@code legacy/item-bank-php/search.php} 의 조회 동작을 옮겼다.
 * 규칙 대응은 {@code docs/item-bank/BUSINESS-RULES.md} BR-01 ~ BR-23 을 따른다.
 * 모르는 단원 · 범위 밖 난이도 · 미등록 태그도 오류가 아니라 0건으로 응답한다(BR-07, BR-10, BR-13).
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    private final ItemRepository itemRepository;

    public ItemSearchService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    /** 검색 조건으로 공개({@code status='A'}) 문항 한 페이지와 전체 건수를 읽는다. */
    public ItemSearchResponse search(LegacySearchParams params) {
        ItemSearchCondition condition = ItemSearchCondition.from(params);
        if (condition.matchesNothing()) {
            return ItemSearchResponse.of(List.of(), 0);
        }

        Page<Integer> page = itemRepository.searchIds(
            ItemStatus.ACTIVE,
            condition.keywordPattern(),
            condition.unitCode(),
            condition.levelEquals(),
            ItemSearchCondition.LEVEL_DEFAULT_CAP,
            condition.tagName(),
            condition.pageable());

        List<Integer> ids = page.getContent();
        List<ItemSearchRow> rows = List.of();
        if (!ids.isEmpty()) {
            Map<Integer, Item> byId = itemRepository.findWithDetailsByIdIn(ids).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
            rows = ids.stream().map(byId::get).map(ItemSearchRow::from).toList();
        }
        log.debug("item search page={} rows={} total={}", condition.page(), rows.size(), page.getTotalElements());
        return ItemSearchResponse.of(rows, page.getTotalElements());
    }
}
