package com.gift.gift.domain.user.repository;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.entity.Term;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class TermRepositoryTest {

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("같은 코드에서 가장 높은 미삭제 정수 버전을 조회한다")
    void findCurrentTerms_returnsHighestUndeletedVersion() {
        String code = uniqueCode();

        saveTerm(code, 2, true);
        Term latest = saveTerm(code, 10, true);

        entityManager.clear();

        List<Term> terms = findTermsFor(code);

        assertEquals(1, terms.size());
        assertEquals(latest.getId(), terms.getFirst().getId());
        assertEquals(10, terms.getFirst().getVersion());
    }

    @Test
    @DisplayName("최신 버전이 선택 약관이면 해당 선택 약관을 조회한다")
    void findCurrentTerms_returnsLatestOptionalTerm() {
        String code = uniqueCode();

        saveTerm(code, 1, true);
        Term latest = saveTerm(code, 2, false);

        entityManager.clear();

        List<Term> terms = findTermsFor(code);

        assertEquals(1, terms.size());
        assertEquals(latest.getId(), terms.getFirst().getId());
        assertEquals(2, terms.getFirst().getVersion());
        assertFalse(terms.getFirst().isRequired());
    }

    @Test
    @DisplayName("최신 버전이 삭제되면 이전 미삭제 버전을 조회한다")
    void findCurrentTerms_returnsPreviousVersion_whenLatestIsDeleted() {
        String code = uniqueCode();

        Term previous = saveTerm(code, 1, true);
        Term latest = saveTerm(code, 2, true);

        jdbcTemplate.update(
                "UPDATE terms SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
                latest.getId()
        );

        entityManager.clear();

        List<Term> terms = findTermsFor(code);

        assertEquals(1, terms.size());
        assertEquals(previous.getId(), terms.getFirst().getId());
    }

    @Test
    @DisplayName("모든 버전이 삭제된 약관 코드는 조회하지 않는다")
    void findCurrentTerms_excludesCode_whenAllVersionsAreDeleted() {
        String code = uniqueCode();

        Term term = saveTerm(code, 1, true);

        jdbcTemplate.update(
                "UPDATE terms SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
                term.getId()
        );

        entityManager.clear();

        assertTrue(findTermsFor(code).isEmpty());
    }

    private List<Term> findTermsFor(String code) {
        return termRepository.findCurrentTerms()
                .stream()
                .filter(term -> term.getTermCode().equals(code))
                .toList();
    }

    private Term saveTerm(
            String code,
            int version,
            boolean required
    ) {
        return termRepository.saveAndFlush(
                new Term(code, version, "약관 제목", "약관 본문", required)
        );
    }

    private String uniqueCode() {
        return "TEST_" + UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}
