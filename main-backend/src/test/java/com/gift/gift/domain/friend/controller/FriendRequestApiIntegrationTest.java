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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    @DisplayName("수락은 양쪽 친구 조회에 반영되고 삭제·반복 수락·새 요청 수락은 관계 이력을 유지한다")
    void accept_removeAndRestorePreservesRelationHistory() throws Exception {
        User first = user();
        User second = user();
        Long old = pending(first, second);
        action(second, old, "accept", 200, "ACCEPTED");
        Friendship relation = friendships.findByUser1_IdAndUser2_Id(first.getId(), second.getId()).orElseThrow();
        LocalDateTime createdAt = relation.getCreatedAt();
        for (User owner : List.of(first, second)) {
            mvc.perform(get("/friends").with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.friends.length()").value(1))
                    .andExpect(jsonPath("$.data.friends[0].friendId").value(relation.getId()));
        }
        mvc.perform(delete("/friends/{id}", relation.getId())
                        .with(jwt().jwt(j -> j.subject(first.getId().toString()))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        LocalDateTime deletedAt = friendships.findById(relation.getId()).orElseThrow().getDeletedAt();
        assertThat(deletedAt).isNotNull();
        mvc.perform(delete("/friends/{id}", relation.getId())
                        .with(jwt().jwt(j -> j.subject(second.getId().toString()))))
                .andExpect(status().isNoContent());
        action(second, old, "accept", 200, "ACCEPTED");
        assertThat(friendships.findById(relation.getId()).orElseThrow().getDeletedAt()).isEqualTo(deletedAt);
        for (User owner : List.of(first, second)) {
            mvc.perform(get("/friends").with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.friends.length()").value(0));
        }
        Long next = service.create(second.getId(), new FriendRequestCreateRequest(first.getId())).requestId();
        action(first, next, "accept", 200, "ACCEPTED");
        Friendship restored = friendships.findById(relation.getId()).orElseThrow();
        assertThat(restored.getDeletedAt()).isNull();
        assertThat(restored.getCreatedAt()).isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friendships WHERE user_id_1 = ? AND user_id_2 = ?",
                Long.class, first.getId(), second.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "reject", "cancel"})
    @DisplayName("같은 처리는 반복 성공하며 다른 처리는 409이고 종료 이력은 보존된다")
    void actions_areIdempotentAndRejectDifferentTerminalAction(String operation) throws Exception {
        User first = user();
        User second = user();
        Long id = pending(first, second);
        User actor = operation.equals("cancel") ? first : second;
        String terminal = switch (operation) {
            case "accept" -> "ACCEPTED";
            case "reject" -> "REJECTED";
            default -> "CANCELED";
        };
        action(actor, id, operation, 200, terminal);
        LocalDateTime updatedAt = requests.findById(id).orElseThrow().getUpdatedAt();
        action(actor, id, operation, 200, terminal);
        assertThat(requests.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(requests.findById(id).orElseThrow().getDeletedAt()).isNull();
        for (String other : List.of("accept", "reject", "cancel")) {
            if (!other.equals(operation)) {
                action(other.equals("cancel") ? first : second, id, other, 409, "FRIEND_REQUEST_ALREADY_PROCESSED");
            }
        }
        assertThat(friendships.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(first.getId(), second.getId()))
                .isEqualTo(operation.equals("accept"));
        if (!operation.equals("accept")) {
            assertThat(service.create(first.getId(), new FriendRequestCreateRequest(second.getId())).requestId())
                    .isNotEqualTo(id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "reject", "cancel"})
    @DisplayName("타인·삭제·없는 요청은 404이며 잘못된 역할은 종료 후에도 403이다")
    void actions_validateOwnershipBeforeTerminalState(String operation) throws Exception {
        User first = user();
        User second = user();
        User outsider = user();
        Long id = pending(first, second);
        User allowed = operation.equals("cancel") ? first : second;
        User forbidden = operation.equals("cancel") ? second : first;
        action(outsider, id, operation, 404, "FRIEND_REQUEST_NOT_FOUND");
        action(allowed, Long.MAX_VALUE, operation, 404, "FRIEND_REQUEST_NOT_FOUND");
        action(forbidden, id, operation, 403, "FRIEND_REQUEST_FORBIDDEN");
        assertThat(requests.findById(id).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        jdbc.update("UPDATE friend_requests SET deleted_at = NOW(6) WHERE id = ?", id);
        action(allowed, id, operation, 404, "FRIEND_REQUEST_NOT_FOUND");
        jdbc.update("UPDATE friend_requests SET deleted_at = NULL, status = 'ACCEPTED' WHERE id = ?", id);
        action(forbidden, id, operation, 403, "FRIEND_REQUEST_FORBIDDEN");
        action(outsider, id, operation, 404, "FRIEND_REQUEST_NOT_FOUND");
    }

    @Test
    @DisplayName("삭제는 관계 참여자만 가능하며 타인·없는 관계는 404다")
    void remove_rejectsOtherUsersWithoutChangingRelation() throws Exception {
        User first = user();
        User second = user();
        User outsider = user();
        Friendship relation = friendships.saveAndFlush(new Friendship(first, second));
        for (Long id : List.of(relation.getId(), Long.MAX_VALUE)) {
            mvc.perform(delete("/friends/{id}", id)
                            .with(jwt().jwt(j -> j.subject(outsider.getId().toString()))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("FRIEND_NOT_FOUND"));
        }
        assertThat(friendships.findById(relation.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("탈퇴·비활성 사용자의 요청 수락은 관계와 상태를 변경하지 않는다")
    void accept_rejectsUnavailableRequester(boolean deleted) throws Exception {
        User first = user();
        User second = user();
        Long id = pending(first, second);
        jdbc.update(deleted ? "UPDATE users SET deleted_at = NOW(6) WHERE id = ?"
                : "UPDATE users SET status = 'INACTIVE' WHERE id = ?", first.getId());
        action(second, id, "accept", 404, "USER_NOT_FOUND");
        assertThat(requests.findById(id).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        assertThat(friendships.findByUser1_IdAndUser2_Id(first.getId(), second.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("수락 트랜잭션 롤백은 요청 상태와 신규·복구 관계를 함께 되돌린다")
    void accept_rollsBackRequestAndFriendshipTogether(boolean restore) {
        User first = user();
        User second = user();
        Long id = pending(first, second);
        if (restore) {
            Friendship relation = friendships.saveAndFlush(new Friendship(first, second));
            jdbc.update("UPDATE friendships SET deleted_at = NOW(6) WHERE id = ?", relation.getId());
        }
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            service.accept(second.getId(), id);
            requests.flush();
            friendships.flush();
            throw new IllegalStateException("수락 후 트랜잭션 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(requests.findById(id).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        if (restore) {
            assertThat(friendships.findByUser1_IdAndUser2_Id(first.getId(), second.getId()).orElseThrow().getDeletedAt())
                    .isNotNull();
        } else {
            assertThat(friendships.findByUser1_IdAndUser2_Id(first.getId(), second.getId())).isEmpty();
        }
    }

    private void action(User actor, Long id, String operation, int expectedStatus, String expectedValue) throws Exception {
        mvc.perform(post("/friend-requests/{id}/{operation}", id, operation)
                        .with(jwt().jwt(j -> j.subject(actor.getId().toString()))))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath(expectedStatus == 200 ? "$.data.status" : "$.error.code").value(expectedValue));
    }

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
