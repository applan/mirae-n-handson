package com.example.item;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 컨트롤러 슬라이스 — 원본 쿼리스트링 해석과 응답 JSON 모양({rows, count, message}). */
@WebMvcTest(ItemSearchController.class)
@ActiveProfiles("test")
class ItemSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    @Test
    @DisplayName("GET /api/items/search → 200, rows · count · message, 행에는 정규화 필드만")
    void searchReturnsNormalizedShape() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(
            List.of(new ItemSearchRow(1, "분모가 같은 분수의 덧셈", "M5-1", 1, List.of("계산", "개념"))), 2));

        mockMvc.perform(get(URI.create("/api/items/search?unit=M5-1&level=1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.message").isEmpty())
            .andExpect(jsonPath("$.rows[0].id").value(1))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].level").value(1))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("개념"))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.rows[0].status").doesNotExist());
        verify(itemSearchService).search(new LegacySearchParams("", "M5-1", "1", "", "", "", "1"));
    }

    @Test
    @DisplayName("GET /api/items/search 0건 → 200, message 는 \"검색 결과가 없습니다\"")
    void searchWithNoResultReturns200() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(List.of(), 0));

        mockMvc.perform(get(URI.create("/api/items/search?unit=Z99-99")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(0))
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
    }

    @Test
    @DisplayName("GET /api/items/search 배열 · 숫자 아닌 값도 400 없이 원문 그대로 서비스에 넘긴다")
    void searchPassesRawParamsWithoutValidation() throws Exception {
        when(itemSearchService.search(any())).thenReturn(ItemSearchResponse.of(List.of(), 0));

        mockMvc.perform(get(URI.create(
                "/api/items/search?q%5B%5D=%EB%B6%84%EC%88%98&q%5B%5D=x&level=-1&page=abc&sort=hack&dir=up")))
            .andExpect(status().isOk());
        verify(itemSearchService).search(new LegacySearchParams("분수", "", "-1", "", "hack", "up", "abc"));
    }
}
