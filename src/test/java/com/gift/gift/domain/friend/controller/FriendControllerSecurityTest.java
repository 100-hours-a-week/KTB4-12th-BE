package com.gift.gift.domain.friend.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.friend.repository.FriendRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FriendControllerSecurityTest {

    private static String passwordHash;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FriendRepository friendRepository;

    @BeforeAll
    static void initializePasswordHash() {
        passwordHash = new BCryptPasswordEncoder(4).encode("Password1!");
    }

    @Test
    @DisplayName("토큰이 없으면 친구 목록 조회는 401 공통 응답을 반환한다")
    void getFriends_rejectsMissingAccessToken() throws Exception {
        mockMvc.perform(get("/friends"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    @DisplayName("인증된 사용자는 토큰의 subject로 본인이 등록한 친구만 이름순으로 조회한다")
    void getFriends_returnsOnlyOwnFriends_forAuthenticatedUser() throws Exception {
        User owner = persistedUser("김소유");
        User second = persistedUser("김나다");
        User first = persistedUser("김가나");
        User stranger = persistedUser("김낯선");
        User strangersFriend = persistedUser("김다라");
        friendRepository.saveAndFlush(new Friend(owner, second));
        friendRepository.saveAndFlush(new Friend(owner, first));
        friendRepository.saveAndFlush(new Friend(stranger, strangersFriend));

        mockMvc.perform(get("/friends")
                        .with(jwt().jwt(token -> token.subject(owner.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("친구 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].userId").value(first.getId()))
                .andExpect(jsonPath("$.data.items[0].birth").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].userId").value(second.getId()))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(false));
    }

    @Test
    @DisplayName("잘못된 커서는 실제 처리 흐름에서도 400 INVALID_CURSOR로 응답한다")
    void getFriends_returnsInvalidCursor_forBrokenCursor() throws Exception {
        User owner = persistedUser("김소유");

        mockMvc.perform(get("/friends")
                        .param("cursor", "broken")
                        .with(jwt().jwt(token -> token.subject(owner.getId().toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    @DisplayName("빈 문자열 커서도 실제 처리 흐름에서 400 INVALID_CURSOR로 응답한다")
    void getFriends_returnsInvalidCursor_forBlankCursor() throws Exception {
        User owner = persistedUser("김소유");

        mockMvc.perform(get("/friends")
                        .param("cursor", "")
                        .with(jwt().jwt(token -> token.subject(owner.getId().toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
    }

    private User persistedUser(String name) {
        return userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                passwordHash,
                name,
                LocalDate.of(2000, 1, 1)
        ));
    }
}
