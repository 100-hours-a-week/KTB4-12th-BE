package com.gift.gift.domain.preference.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.preference.entity.UserGiftPreference;
import com.gift.gift.domain.preference.repository.UserGiftPreferenceRepository;
import com.gift.gift.domain.preference.service.PreferenceSaveService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GiftPreferenceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserGiftPreferenceRepository preferenceRepository;
    @Autowired
    private PreferenceSaveService saveService;
    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUpOwnedData() {
        for (Long userId : createdUserIds) {
            jdbc.update("DELETE FROM user_gift_preferences WHERE user_id = ?", userId);
            userRepository.deleteById(userId);
        }
    }

    @Test
    @DisplayName("미작성·최초 저장·수정·비우기 흐름에서 본인 행 하나와 비선호 상태를 유지한다")
    void saveAndGet_preserveSingleRowAndOtherUsers() throws Exception {
        User owner = saveUser();
        User other = saveUser();
        saveService.saveGiftPreference(other.getId(), "다른 사용자 취향");
        mockMvc.perform(get("/preferences").with(jwt().jwt(t -> t.subject(owner.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preference", nullValue()));

        putPreference(owner.getId(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preference", nullValue()));
        assertThat(rowCount(owner.getId())).isZero();
        putPreference(owner.getId(), " \n향수  좋아해요\n문구도 좋아해요\t ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preference").value("향수  좋아해요\n문구도 좋아해요"));
        Long id = preferenceRepository.findByUser_IdAndDeletedAtIsNull(owner.getId()).orElseThrow().getId();
        putPreference(owner.getId(), "실용적인 선물").andExpect(status().isOk());
        putPreference(owner.getId(), " \t\n").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preference", nullValue()));
        mockMvc.perform(get("/preferences").with(jwt().jwt(t -> t.subject(owner.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내 선물 취향을 조회했습니다."))
                .andExpect(jsonPath("$.data.preference", nullValue()));
        UserGiftPreference row = preferenceRepository.findByUser_IdAndDeletedAtIsNull(owner.getId()).orElseThrow();
        assertThat(row.getId()).isEqualTo(id);
        assertThat(row.getPreference()).isNull();
        assertThat(row.getDeletedAt()).isNull();
        assertThat(rowCount(owner.getId())).isEqualTo(1);
        assertThat(preferenceRepository.findByUser_IdAndDeletedAtIsNull(other.getId())
                .orElseThrow().getPreference()).isEqualTo("다른 사용자 취향");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_dislike_categories WHERE user_id = ?",
                Long.class, owner.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipient_profiles WHERE recipient_id = ?",
                Long.class, owner.getId())).isZero();
    }

    @Test
    @DisplayName("정규화 후 같은 값과 반복 null 저장은 updated_at을 변경하지 않는다")
    void save_sameFinalStateDoesNotUpdateRow() throws Exception {
        User user = saveUser();
        saveService.saveGiftPreference(user.getId(), "향수");
        LocalDateTime sentinel = LocalDateTime.of(2000, 1, 1, 0, 0);
        jdbc.update("UPDATE user_gift_preferences SET updated_at = ? WHERE user_id = ?", sentinel, user.getId());
        putPreference(user.getId(), " \n향수\t ").andExpect(status().isOk());
        assertThat(updatedAt(user.getId())).isEqualTo(sentinel);
        putPreference(user.getId(), null).andExpect(status().isOk());
        jdbc.update("UPDATE user_gift_preferences SET updated_at = ? WHERE user_id = ?", sentinel, user.getId());
        putPreference(user.getId(), " \t\n").andExpect(status().isOk());
        putPreference(user.getId(), null).andExpect(status().isOk());
        assertThat(updatedAt(user.getId())).isEqualTo(sentinel);
    }

    @ParameterizedTest
    @ValueSource(ints = {299, 300})
    @DisplayName("emoji 원문 299·300 코드 포인트는 UTF-16 길이와 무관하게 저장한다")
    void save_acceptsCodePointBoundary(int length) throws Exception {
        User user = saveUser();
        String value = "🎁".repeat(length);
        putPreference(user.getId(), value).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preference").value(value));
        assertThat(preferenceRepository.findByUser_IdAndDeletedAtIsNull(user.getId())
                .orElseThrow().getPreference()).isEqualTo(value);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("누락·원문 길이 오류는 필드별 400과 원문 없는 응답을 반환한다")
    void save_rejectsInvalidFields(String body, String field, String reason) throws Exception {
        User user = saveUser();
        saveService.saveGiftPreference(user.getId(), "기존 취향");
        mockMvc.perform(put("/preferences").with(jwt().jwt(t -> t.subject(user.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details[0].field").value(field))
                .andExpect(jsonPath("$.error.details[0].reason").value(reason))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(preferenceRepository.findByUser_IdAndDeletedAtIsNull(user.getId())
                .orElseThrow().getPreference()).isEqualTo("기존 취향");
    }

    private static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("{}", "preference", "REQUIRED"),
                Arguments.of("{\"preference\":\"" + "🎁".repeat(301) + "\"}", "preference", "TOO_LONG"),
                Arguments.of("{\"preference\":\"" + " ".repeat(300) + "a\"}", "preference", "TOO_LONG")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "123", "true", "\"text\"", "null", "{invalid",
            "{\"preference\":[]}", "{\"preference\":{}}", "{\"preference\":null,\"extra\":123}"})
    @DisplayName("객체가 아닌 요청과 잘못된 JSON은 저장 없이 400이다")
    void save_rejectsInvalidBodyShape(String body) throws Exception {
        User user = saveUser();
        mockMvc.perform(put("/preferences").with(jwt().jwt(t -> t.subject(user.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(rowCount(user.getId())).isZero();
    }

    @Test
    @DisplayName("토큰 없는 GET·PUT은 401, 활성 사용자 미존재는 404이다")
    void api_requiresAuthenticationAndActiveUser() throws Exception {
        mockMvc.perform(get("/preferences")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\":null}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/preferences").with(jwt().jwt(t -> t.subject(Long.toString(Long.MAX_VALUE)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
        putPreference(Long.MAX_VALUE, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("삭제된 취향 행은 조회에서 제외한다")
    void get_excludesDeletedRow() throws Exception {
        User user = saveUser();
        saveService.saveGiftPreference(user.getId(), "삭제될 취향");
        jdbc.update("UPDATE user_gift_preferences SET deleted_at = CURRENT_TIMESTAMP(6) WHERE user_id = ?",
                user.getId());
        mockMvc.perform(get("/preferences").with(jwt().jwt(t -> t.subject(user.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.preference", nullValue()));
    }

    @Test
    @DisplayName("동시 최초 저장은 사용자 잠금으로 모두 성공하고 UNIQUE는 직접 중복 삽입을 차단한다")
    void save_serializesInitialWritesAndDatabaseRejectsDuplicates() throws Exception {
        User user = saveUser();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> concurrentSave(user.getId(), "향수", ready, start));
            Future<?> second = executor.submit(() -> concurrentSave(user.getId(), "문구", ready, start));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(rowCount(user.getId())).isEqualTo(1);
        assertThat(preferenceRepository.findByUser_IdAndDeletedAtIsNull(user.getId())
                .orElseThrow().getPreference()).isIn("향수", "문구");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO user_gift_preferences (user_id, preference, created_at, updated_at)
                VALUES (?, 'duplicate', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, user.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void concurrentSave(Long userId, String value, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 저장 시작 시간 초과");
            }
            saveService.saveGiftPreference(userId, value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private ResultActions putPreference(Long userId, String value) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("preference", value);
        return mockMvc.perform(put("/preferences").with(jwt().jwt(t -> t.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private User saveUser() {
        User user = userRepository.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53), "테스트", LocalDate.of(1990, 1, 1)));
        createdUserIds.add(user.getId());
        return user;
    }

    private long rowCount(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_gift_preferences WHERE user_id = ?", Long.class, userId);
    }

    private LocalDateTime updatedAt(Long userId) {
        return jdbc.queryForObject("SELECT updated_at FROM user_gift_preferences WHERE user_id = ?",
                LocalDateTime.class, userId);
    }
}
