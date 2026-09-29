package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    private static LegacySearchParams params(String unit, String level, String page) {
        return new LegacySearchParams("", unit, level, "", "", "", page);
    }

    @Test
    @DisplayName("search: id 페이지 순서대로 행을 만들고 공개 상태 · 조건을 넘긴다")
    void searchKeepsIdOrderAndPassesCondition() {
        Pageable pageable = PageRequest.of(0, ItemSearchCondition.PAGE_SIZE);
        when(itemRepository.searchIds(eq(ItemStatus.ACTIVE), isNull(), eq("M5-1"), eq(1),
            eq(ItemSearchCondition.LEVEL_DEFAULT_CAP), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(2, 1), pageable, 2));
        Item first = ItemFixtures.item(1, unit, "분모가 같은 분수의 덧셈", 1, ItemStatus.ACTIVE,
            ItemFixtures.tag(1, "계산"), ItemFixtures.tag(3, "개념"));
        Item second = ItemFixtures.item(2, unit, "분모가 같은 분수의 뺄셈", 1, ItemStatus.ACTIVE);
        when(itemRepository.findWithDetailsByIdIn(List.of(2, 1))).thenReturn(List.of(first, second));

        ItemSearchResponse response = itemSearchService.search(params("M5-1", "1", "1"));

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.message()).isNull();
        assertThat(response.rows()).containsExactly(
            new ItemSearchRow(2, "분모가 같은 분수의 뺄셈", "M5-1", 1, List.of()),
            new ItemSearchRow(1, "분모가 같은 분수의 덧셈", "M5-1", 1, List.of("계산", "개념")));
    }

    @Test
    @DisplayName("search: 전체 0건이면 \"검색 결과가 없습니다\"(BR-23), 404 가 아니다")
    void searchWithNoResultReturnsMessage() {
        when(itemRepository.searchIds(eq(ItemStatus.ACTIVE), isNull(), eq("Z99-99"), isNull(),
            eq(ItemSearchCondition.LEVEL_DEFAULT_CAP), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, ItemSearchCondition.PAGE_SIZE), 0));

        ItemSearchResponse response = itemSearchService.search(params("Z99-99", "", "1"));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemRepository, never()).findWithDetailsByIdIn(anyCollection());
    }

    @Test
    @DisplayName("search: 결과보다 뒤 페이지면 행은 비고 건수는 그대로, 문구는 없다(BR-21 · BR-23)")
    void searchPastLastPageKeepsCountWithoutMessage() {
        when(itemRepository.searchIds(eq(ItemStatus.ACTIVE), isNull(), isNull(), isNull(),
            eq(ItemSearchCondition.LEVEL_DEFAULT_CAP), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, ItemSearchCondition.PAGE_SIZE), 20));

        ItemSearchResponse response = itemSearchService.search(params("", "", "2"));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isEqualTo(20);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 정수 범위를 넘는 난이도는 조회 없이 0건")
    void searchWithHugeLevelSkipsQuery() {
        ItemSearchResponse response = itemSearchService.search(params("", "99999999999999999999", "1"));

        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemRepository, never()).searchIds(any(), any(), any(), any(), any(), any(), any());
    }
}
