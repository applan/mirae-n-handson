package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/**
 * 문항 검색 쿼리 — H2(MariaDB 모드). H2 는 _ci 콜레이션 · 한글 정렬을 재현하지 않아 그 부분은 동작 보존 테스트로 본다.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchRepositoryTest {

    private static final int CAP = ItemSearchCondition.LEVEL_DEFAULT_CAP;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    private Integer addTwo;
    private Integer wordProblem;
    private Integer levelFive;
    private Integer decimal;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimalUnit = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        addTwo = entityManager.persist(ItemFixtures.item(null, fraction, "Fraction add", 2, ItemStatus.ACTIVE,
            ItemFixtures.SEED_TIME.plusDays(2), calc)).getId();
        wordProblem = entityManager.persist(ItemFixtures.item(null, fraction, "Fraction word", 4, ItemStatus.ACTIVE,
            ItemFixtures.SEED_TIME, calc, word)).getId();
        entityManager.persist(ItemFixtures.item(null, fraction, "Fraction reviewing", 3, ItemStatus.REVIEWING, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "Fraction deleted", 2, ItemStatus.DELETED, calc));
        levelFive = entityManager.persist(ItemFixtures.item(null, fraction, "Fraction hard", 5, ItemStatus.ACTIVE,
            word)).getId();
        decimal = entityManager.persist(ItemFixtures.item(null, decimalUnit, "Decimal 50% off", 2, ItemStatus.ACTIVE,
            ItemFixtures.SEED_TIME.plusDays(1))).getId();
        entityManager.flush();
        entityManager.clear();
    }

    private Page<Integer> search(String pattern, String unitCode, Integer levelEquals, String tagName,
                                 Pageable pageable) {
        return itemRepository.searchIds(ItemStatus.ACTIVE, pattern, unitCode, levelEquals, CAP, tagName, pageable);
    }

    private static Pageable firstPage(Sort sort) {
        return PageRequest.of(0, ItemSearchCondition.PAGE_SIZE, sort);
    }

    private static Pageable defaultFirstPage() {
        return firstPage(ItemSearchCondition.sortOf("", ""));
    }

    @Test
    @DisplayName("searchIds: 조건이 없으면 공개 문항 중 level < 5 만, 난이도 내림차순 · id 오름차순")
    void searchWithoutConditionExcludesLevelFiveAndHiddenStatus() {
        Page<Integer> page = search(null, null, null, null, defaultFirstPage());

        assertThat(page.getContent()).containsExactly(wordProblem, addTwo, decimal);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("searchIds: 난이도를 지정하면 5 도 나오고, 범위 밖 값은 0건")
    void searchWithLevelEqualsIncludesFive() {
        assertThat(search(null, null, 5, null, defaultFirstPage()).getContent()).containsExactly(levelFive);
        assertThat(search(null, null, 6, null, defaultFirstPage()).getTotalElements()).isZero();
        assertThat(search(null, null, 0, null, defaultFirstPage()).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("searchIds: 키워드는 제목 · 지문 LIKE, % 는 와일드카드로 동작한다")
    void searchWithKeywordMatchesTitleOrStemAsWildcard() {
        assertThat(search("%word%", null, null, null, defaultFirstPage()).getContent()).containsExactly(wordProblem);
        assertThat(search("%문제 본문%", null, 2, null, defaultFirstPage()).getContent())
            .containsExactly(addTwo, decimal);
        assertThat(search("%50%%", null, null, null, defaultFirstPage()).getContent()).containsExactly(decimal);
    }

    @Test
    @DisplayName("searchIds: 단원 코드 · 태그 이름 조건을 AND 로 결합하고, 없는 코드 · 태그는 0건")
    void searchCombinesUnitAndTag() {
        assertThat(search(null, "M5-1", null, "계산", defaultFirstPage()).getContent())
            .containsExactly(wordProblem, addTwo);
        assertThat(search(null, "M5-1", null, "문장제", defaultFirstPage()).getContent())
            .containsExactly(wordProblem);
        assertThat(search(null, "Z99-99", null, null, defaultFirstPage()).getTotalElements()).isZero();
        assertThat(search(null, null, null, "없는태그", defaultFirstPage()).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("searchIds: 제목 · 단원 · 등록일 정렬과 보조 정렬 id ASC")
    void searchAppliesSortOrders() {
        assertThat(search(null, null, null, null, firstPage(ItemSearchCondition.sortOf("title", ""))).getContent())
            .containsExactly(decimal, addTwo, wordProblem);
        assertThat(search(null, null, null, null, firstPage(ItemSearchCondition.sortOf("unit", "desc"))).getContent())
            .containsExactly(decimal, wordProblem, addTwo);
        assertThat(search(null, null, null, null, firstPage(ItemSearchCondition.sortOf("created", ""))).getContent())
            .containsExactly(addTwo, decimal, wordProblem);
    }

    @Test
    @DisplayName("searchIds: 결과보다 뒤 페이지면 id 는 비고 전체 건수는 그대로")
    void searchPastLastPageKeepsTotal() {
        Page<Integer> page = search(null, null, null, null,
            PageRequest.of(1, ItemSearchCondition.PAGE_SIZE, ItemSearchCondition.sortOf("", "")));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("findWithDetailsByIdIn: 단원 · 태그(id 순)를 함께 읽는다")
    void findWithDetailsByIdInLoadsTagsInIdOrder() {
        List<Item> items = itemRepository.findWithDetailsByIdIn(List.of(wordProblem, decimal));

        assertThat(items).hasSize(2);
        Item word = items.stream().filter(i -> i.getId().equals(wordProblem)).findFirst().orElseThrow();
        assertThat(word.getUnit().getCode()).isEqualTo("M5-1");
        assertThat(word.getTags()).extracting(Tag::getName).containsExactly("계산", "문장제");
    }
}
