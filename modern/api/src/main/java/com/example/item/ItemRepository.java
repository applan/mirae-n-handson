package com.example.item;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Integer> {

    /** 단건 조회 — 단원 · 태그를 함께 가져온다(OSIV 꺼져 있음). */
    @EntityGraph(attributePaths = {"unit", "tags"})
    Optional<Item> findWithDetailsById(Integer id);

    /** 단원 코드 + 상태로 조회. 정렬은 난이도 내림차순, 같은 난이도면 id 오름차순. */
    @EntityGraph(attributePaths = {"unit", "tags"})
    @Query("""
        select i from Item i
        where i.unit.code = :unitCode and i.status = :status
        order by i.level desc, i.id asc
        """)
    List<Item> findByUnitCodeAndStatus(@Param("unitCode") String unitCode, @Param("status") String status);

    long countByUnitIdAndStatus(Integer unitId, String status);

    /**
     * 문항 검색 — 조건에 맞는 id 한 페이지와 전체 건수. 정렬은 {@code pageable} 의 {@code Sort} 로 받는다.
     * 레거시 {@code v_item_public}(status 조건 + unit 내부 조인)과 {@code search.php:44-261} 의 WHERE 를 옮겼다.
     * null 인 조건은 걸지 않는다. {@code levelEquals} 가 null 이면 {@code level < levelCap}.
     */
    @Query(value = """
        select i.id from Item i
        where i.status = :status
          and (:keywordPattern is null or i.title like :keywordPattern or i.stem like :keywordPattern)
          and (:unitCode is null or i.unit.code = :unitCode)
          and ((:levelEquals is null and i.level < :levelCap) or i.level = :levelEquals)
          and (:tagName is null or exists (
                select 1 from Item it join it.tags t where it.id = i.id and t.name = :tagName))
        """,
        countQuery = """
        select count(i) from Item i
        where i.status = :status
          and (:keywordPattern is null or i.title like :keywordPattern or i.stem like :keywordPattern)
          and (:unitCode is null or i.unit.code = :unitCode)
          and ((:levelEquals is null and i.level < :levelCap) or i.level = :levelEquals)
          and (:tagName is null or exists (
                select 1 from Item it join it.tags t where it.id = i.id and t.name = :tagName))
        """)
    Page<Integer> searchIds(
        @Param("status") String status,
        @Param("keywordPattern") String keywordPattern,
        @Param("unitCode") String unitCode,
        @Param("levelEquals") Integer levelEquals,
        @Param("levelCap") Integer levelCap,
        @Param("tagName") String tagName,
        Pageable pageable);

    /** id 목록으로 단원 · 태그까지 함께 읽는다(OSIV 꺼져 있음). 순서는 보장하지 않는다. */
    @EntityGraph(attributePaths = {"unit", "tags"})
    List<Item> findWithDetailsByIdIn(Collection<Integer> ids);
}
