package com.gift.gift.domain.coupon;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.service.CouponInitializationService;
import com.gift.gift.domain.coupon.service.CouponIssueService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CouponControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CouponInitializationService initializationService;

    @Autowired
    private CouponIssueService issueService;

    @Test
    @DisplayName("미인증 쿠폰 조회는 공통 401 응답을 반환한다")
    void getCoupons_rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/coupons"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("잘못된 토큰의 쿠폰 조회는 401을 반환한다")
    void getCoupons_rejectsInvalidToken() throws Exception {
        mockMvc.perform(get("/coupons").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("임의 사용자 파라미터와 관계없이 인증된 본인의 수량만 배수순으로 반환한다")
    void getCoupons_returnsOnlyOwnCouponsInMultiplierOrder() throws Exception {
        Long ownerId = initializedUser();
        Long strangerId = initializedUser();
        issueService.issue(ownerId, 3);
        issueService.issue(ownerId, 2);
        issueService.issue(ownerId, 2);
        issueService.issue(strangerId, 3);

        mockMvc.perform(get("/coupons")
                        .param("userId", strangerId.toString())
                        .with(jwt().jwt(token -> token.subject(ownerId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("보유 쿠폰을 조회했습니다."))
                .andExpect(jsonPath("$.data.coupons.length()").value(2))
                .andExpect(jsonPath("$.data.coupons[0].multiplier").value(2))
                .andExpect(jsonPath("$.data.coupons[0].quantity").value(2))
                .andExpect(jsonPath("$.data.coupons[1].multiplier").value(3))
                .andExpect(jsonPath("$.data.coupons[1].quantity").value(1))
                .andExpect(jsonPath("$.data.coupons[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.pagination").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("보유 수량이 모두 0이면 200과 빈 목록을 반환한다")
    void getCoupons_returnsEmptyList() throws Exception {
        Long userId = initializedUser();
        mockMvc.perform(get("/coupons")
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coupons").isEmpty());
    }

    @Test
    @DisplayName("수량이 0인 배수는 목록에서 제외한다")
    void getCoupons_excludesZeroQuantity() throws Exception {
        Long userId = initializedUser();
        issueService.issue(userId, 3);
        mockMvc.perform(get("/coupons")
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coupons.length()").value(1))
                .andExpect(jsonPath("$.data.coupons[0].multiplier").value(3))
                .andExpect(jsonPath("$.data.coupons[0].quantity").value(1));
    }

    private Long initializedUser() {
        User user = userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "쿠폰사용자",
                LocalDate.of(2000, 1, 1)
        ));
        initializationService.initialize(user.getId());
        return user.getId();
    }
}
