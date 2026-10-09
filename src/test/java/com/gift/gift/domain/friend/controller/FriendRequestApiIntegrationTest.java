package com.gift.gift.domain.friend.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.friend.dto.request.FriendRequestCreateRequest;
import com.gift.gift.domain.friend.dto.response.FriendRequestResponse;
import com.gift.gift.domain.friend.entity.FriendRequest;
import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.friend.repository.FriendRequestRepository;
import com.gift.gift.domain.friend.repository.FriendshipRepository;
import com.gift.gift.domain.friend.service.FriendRequestService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.pagination.CursorPageResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FriendRequestApiIntegrationTest {
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("Password1!");
    private final List<Long> userIds = new ArrayList<>();

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private FriendRequestRepository requests;
    @Autowired private FriendshipRepository friendships;
    @Autowired private FriendRequestService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        for (Long id : userIds) {
            jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", id);
            jdbc.update("DELETE FROM friend_requests WHERE requester_id = ? OR receiver_id = ?", id, id);
            jdbc.update("DELETE FROM friendships WHERE user_id_1 = ? OR user_id_2 = ?", id, id);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("요청 생성은 PENDING과 수신자 알림을 저장하고 친구 관계는 만들지 않는다")
    void create_persistsPendingWithoutFriendship() throws Exception {
        User first = user();
        User second = user();
        mvc.perform(post("/friend-requests").with(jwt().jwt(j -> j.subject(first.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":" + second.getId() + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.requesterId").value(first.getId()))
                .andExpect(jsonPath("$.data.receiverId").value(second.getId()));
        assertThat(requests.findPendingPair(first.getId(), second.getId())).isPresent();
        assertThat(friendships.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(first.getId(), second.getId())).isFalse();
        Long requestId = requests.findPendingPair(first.getId(), second.getId()).orElseThrow().getId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?",
                Long.class, first.getId())).isZero();
        assertThat(jdbc.queryForMap("SELECT type, reference_type, reference_id, title, message, deduplication_key "
                        + "FROM notifications WHERE recipient_id = ?", second.getId()))
                .containsEntry("type", "FRIEND_REQUEST_RECEIVED")
                .containsEntry("reference_type", "FRIEND_REQUEST")
                .containsEntry("reference_id", requestId)
                .containsEntry("title", "친구 요청이 도착했어요")
                .containsEntry("message", first.getName() + "님이 친구 요청을 보냈어요.")
                .containsEntry("deduplication_key", "FRIEND_REQUEST_RECEIVED:" + requestId + ":" + second.getId());
    }

    @Test
    @DisplayName("본인 요청은 422이며 DB 요청을 저장하지 않는다")
    void create_rejectsSelf() throws Exception {
        User owner = user();
        mvc.perform(post("/friend-requests").with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":" + owner.getId() + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("FRIEND_CANNOT_ADD_SELF"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friend_requests WHERE requester_id = ?",
                Long.class, owner.getId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "inactive", "deleted"})
    @DisplayName("없는·비활성·탈퇴 대상은 동일하게 404로 처리한다")
    void create_rejectsUnavailableTarget(String type) throws Exception {
        User owner = user();
        User receiver = user();
        Long target = receiver.getId();
        if (type.equals("missing")) target = Long.MAX_VALUE;
        if (type.equals("inactive")) jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", target);
        if (type.equals("deleted")) jdbc.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", target);
        mvc.perform(post("/friend-requests").with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":" + target + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("사용자 정보를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("생성 방향과 무관하게 이미 친구면 409로 요청을 차단한다")
    void create_rejectsExistingFriendship() throws Exception {
        User first = user();
        User second = user();
        friendships.saveAndFlush(new Friendship(second, first));
        mvc.perform(post("/friend-requests").with(jwt().jwt(j -> j.subject(second.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":" + first.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("FRIEND_ALREADY_EXISTS"));
        assertThat(requests.findPendingPair(first.getId(), second.getId())).isEmpty();
    }

    @Test
    @DisplayName("같은 방향·역방향 중복은 각각 409이며 오류 응답에 요청 ID가 없다")
    void create_rejectsSameAndConversePending() throws Exception {
        User first = user();
        User second = user();
        service.create(first.getId(), new FriendRequestCreateRequest(second.getId()));
        mvc.perform(post("/friend-requests").with(jwt().jwt(j -> j.subject(first.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":" + second.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("FRIEND_ALREADY_REQUESTED"))
                .andExpect(jsonPath("$.error.requestId").doesNotExist());
        mvc.perform(post("/friend-requests").with(jwt().jwt(j -> j.subject(second.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":" + first.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONVERSE_REQUEST_EXISTS"))
                .andExpect(jsonPath("$.error.requestId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friend_requests WHERE requester_id = ? OR receiver_id = ?",
                Long.class, first.getId(), first.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id IN (?, ?)",
                Long.class, first.getId(), second.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("알림은 요청 커밋 후에만 생성되며 롤백 시 요청과 알림이 모두 남지 않는다")
    void create_notifiesOnlyAfterCommit(boolean rollback) {
        User first = user();
        User second = user();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            service.create(first.getId(), new FriendRequestCreateRequest(second.getId()));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?",
                    Long.class, second.getId())).isZero();
            if (rollback) transaction.setRollbackOnly();
        });
        assertThat(requests.findPendingPair(first.getId(), second.getId()).isPresent()).isEqualTo(!rollback);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ?",
                Long.class, second.getId())).isEqualTo(rollback ? 0L : 1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "REJECTED", "CANCELED"})
    @DisplayName("종료 이력과 삭제된 관계는 새 요청 생성을 막지 않는다")
    void create_allowsRequestAfterTerminalHistory(String terminal) {
        User first = user();
        User second = user();
        Long old = pending(first, second);
        jdbc.update("UPDATE friend_requests SET status = ? WHERE id = ?", terminal, old);
        Friendship relation = friendships.saveAndFlush(new Friendship(first, second));
        jdbc.update("UPDATE friendships SET deleted_at = NOW(6) WHERE id = ?", relation.getId());
        FriendRequestResponse created = service.create(second.getId(), new FriendRequestCreateRequest(first.getId()));
        assertThat(created.requestId()).isNotEqualTo(old);
        assertThat(requests.findById(old).orElseThrow().getStatus().name()).isEqualTo(terminal);
    }

    @Test
    @DisplayName("받은·보낸 목록은 본인의 활성 PENDING만 방향별로 조회한다")
    void list_excludesTerminalDeletedUnavailableAndOtherUsers() throws Exception {
        User owner = user();
        Long received = pending(user(), owner);
        Long sent = pending(owner, user());
        for (String state : List.of("ACCEPTED", "REJECTED", "CANCELED")) {
            Long id = pending(user(), owner);
            jdbc.update("UPDATE friend_requests SET status = ? WHERE id = ?", state, id);
        }
        Long removed = pending(user(), owner);
        jdbc.update("UPDATE friend_requests SET deleted_at = NOW(6) WHERE id = ?", removed);
        User inactive = user();
        pending(inactive, owner);
        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", inactive.getId());
        User deleted = user();
        pending(deleted, owner);
        jdbc.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", deleted.getId());
        pending(user(), user());
        mvc.perform(get("/friend-requests/received").with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].requestId").value(received))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(false));
        mvc.perform(get("/friend-requests/sent").with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].requestId").value(sent));
    }

    @Test
    @DisplayName("생성 시각과 ID 최신순 20건 커서는 누락 없이 다음 페이지를 반환한다")
    void list_paginatesByCreationTimeThenIdAndScopesCursor() throws Exception {
        User owner = user();
        List<Long> ids = new ArrayList<>();
        LocalDateTime time = LocalDateTime.of(2026, 10, 8, 12, 0);
        for (int i = 0; i < 22; i++) {
            Long id = pending(user(), owner);
            ids.add(id);
            jdbc.update("UPDATE friend_requests SET created_at = ? WHERE id = ?", time, id);
        }
        Long newestId = ids.getLast();
        jdbc.update("UPDATE friend_requests SET created_at = ? WHERE id = ?", time.minusDays(1), newestId);
        CursorPageResponse<FriendRequestResponse> first = service.getRequests(owner.getId(), true, null);
        assertThat(first.items()).hasSize(20);
        assertThat(first.items().getFirst().requestId()).isEqualTo(ids.get(20));
        assertThat(first.pagination().hasNext()).isTrue();
        String cursor = first.pagination().nextCursor();
        CursorPageResponse<FriendRequestResponse> second = service.getRequests(owner.getId(), true, cursor);
        assertThat(second.items().stream().map(r -> r.requestId())).containsExactly(ids.getFirst(), newestId);
        assertThat(second.pagination().hasNext()).isFalse();
        assertThat(second.pagination().nextCursor()).isNull();
        User other = user();
        mvc.perform(get("/friend-requests/received").param("cursor", cursor)
                        .with(jwt().jwt(j -> j.subject(other.getId().toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        mvc.perform(get("/friend-requests/sent").param("cursor", cursor)
                        .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        MvcResult result = mvc.perform(get("/friend-requests/received").param("cursor", cursor)
                        .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("data").path("items").size()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "broken", "e30"})
    @DisplayName("비어 있거나 깨진·필수 값 없는 커서는 INVALID_CURSOR다")
    void list_rejectsInvalidCursor(String cursor) throws Exception {
        User owner = user();
        mvc.perform(get("/friend-requests/received").param("cursor", cursor)
                        .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("양의 ID가 아닌 커서도 INVALID_CURSOR다")
    void list_rejectsInvalidCursorValues() throws Exception {
        User owner = user();
        String cursor = java.util.Base64.getUrlEncoder().encodeToString(("{\"createdAt\":\"2026-10-08T12:00:00\","
                + "\"requestId\":0,\"userId\":" + owner.getId() + ",\"received\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/friend-requests/received").param("cursor", cursor)
                        .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("인증 없는 생성·받은·보낸 조회는 모두 401이다")
    void requests_rejectMissingAuthentication() throws Exception {
        mvc.perform(post("/friend-requests").contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":2}"))
                .andExpect(status().isUnauthorized());
        for (String direction : List.of("received", "sent")) {
            mvc.perform(get("/friend-requests/" + direction)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("기존 즉시 친구 추가 API는 성공하지 않고 요청이나 관계를 생성하지 않는다")
    void oldCreateFriend_isUnavailable() throws Exception {
        User owner = user();
        User other = user();
        mvc.perform(post("/friends").with(jwt().jwt(j -> j.subject(owner.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"friendUserId\":" + other.getId() + "}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        assertThat(requests.findPendingPair(owner.getId(), other.getId())).isEmpty();
        assertThat(friendships.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(
                Math.min(owner.getId(), other.getId()), Math.max(owner.getId(), other.getId()))).isFalse();
    }

    private Long pending(User requester, User receiver) {
        return requests.saveAndFlush(new FriendRequest(requester, receiver)).getId();
    }

    private User user() {
        User saved = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", PASSWORD_HASH,
                "김친구", LocalDate.of(2000, 1, 1)));
        userIds.add(saved.getId());
        return saved;
    }
}
