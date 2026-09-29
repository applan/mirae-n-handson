package com.example.item;

import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/** 테스트용 엔티티 생성 도우미. */
final class ItemFixtures {

    static final LocalDateTime SEED_TIME = LocalDateTime.of(2026, 9, 1, 9, 0, 0);

    private ItemFixtures() {
    }

    static Unit unit(int id, String code, String name, int grade) {
        return new Unit(id, code, name, grade);
    }

    static Tag tag(int id, String name) {
        return new Tag(id, name);
    }

    static Item item(Integer id, Unit unit, String title, int level, String status, Tag... tags) {
        return item(id, unit, title, level, status, SEED_TIME, tags);
    }

    static Item item(Integer id, Unit unit, String title, int level, String status, LocalDateTime createdAt,
                     Tag... tags) {
        Item item = new Item(unit, title, title + " 문제 본문", level, status, createdAt);
        for (Tag tag : tags) {
            item.addTag(tag);
        }
        if (id != null) {
            ReflectionTestUtils.setField(item, "id", id);
        }
        return item;
    }
}
