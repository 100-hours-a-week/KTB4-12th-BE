package com.gift.gift.domain.preference.controller;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.preference.entity.UserDislikeCategory;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PreferenceControllerSecurityTest {

    private static String passwordHash;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserDislikeCategoryRepository dislikeRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeAll
    static void initializePasswordHash() {
        passwordHash = new BCryptPasswordEncoder(4)
                .encode("Password1!");
    }

    @Test
    @DisplayName("토큰이 없으면 비선호 카테고리 조회는 401 공통 응답을 반환한다")
    void getDislikeCategories_rejectsMissingAccessToken()
            throws Exception {
        mockMvc.perform(
                        get("/preferences/dislike-categories")
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.error.code")
                        .value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
    }

    @Test
    @DisplayName("인증 사용자는 활성 대분류와 본인의 선택 상태만 조회한다")
    void getDislikeCategories_returnsOwnSelection()
            throws Exception {
        User owner = persistedUser("조회사용자");
        User otherUser = persistedUser("다른사용자");

        Category beauty = persistedRoot("뷰티");
        Category food = persistedRoot("식품");
        Category digital = persistedRoot("디지털");

        persistedChild("향수", beauty);
        persistedChild("간식", food);

        dislikeRepository.saveAndFlush(
                new UserDislikeCategory(owner, beauty)
        );
        dislikeRepository.saveAndFlush(
                new UserDislikeCategory(otherUser, food)
        );

        mockMvc.perform(
                        get("/preferences/dislike-categories")
                                .with(jwt().jwt(token -> token.subject(
                                        owner.getId().toString()
                                )))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("비선호 카테고리를 조회했습니다."))
                .andExpect(jsonPath("$.data.maxSelectableCount")
                        .value(5))
                .andExpect(jsonPath("$.data.categories.length()")
                        .value(3))
                .andExpect(jsonPath(
                        "$.data.categories[0].categoryId"
                ).value(beauty.getId()))
                .andExpect(jsonPath(
                        "$.data.categories[0].name"
                ).value(beauty.getName()))
                .andExpect(jsonPath(
                        "$.data.categories[0].isSelected"
                ).value(true))
                .andExpect(jsonPath(
                        "$.data.categories[1].categoryId"
                ).value(food.getId()))
                .andExpect(jsonPath(
                        "$.data.categories[1].name"
                ).value(food.getName()))
                .andExpect(jsonPath(
                        "$.data.categories[1].isSelected"
                ).value(false))
                .andExpect(jsonPath(
                        "$.data.categories[2].categoryId"
                ).value(digital.getId()))
                .andExpect(jsonPath(
                        "$.data.categories[2].isSelected"
                ).value(false))
                .andExpect(jsonPath("$.error")
                        .doesNotExist());
    }

    private User persistedUser(String name) {
        return userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                passwordHash,
                name,
                LocalDate.of(2000, 1, 1)
        ));
    }

    private Category persistedRoot(String prefix) {
        Category category = new Category(
                prefix + "-" + UUID.randomUUID(),
                null
        );
        entityManager.persist(category);
        return category;
    }

    private Category persistedChild(
            String prefix,
            Category parent
    ) {
        Category category = new Category(
                prefix + "-" + UUID.randomUUID(),
                parent
        );
        entityManager.persist(category);
        return category;
    }
}
