package com.gift.gift.domain.roulette.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.roulette.dto.response.RouletteAttendanceResponse;
import com.gift.gift.domain.roulette.dto.response.RouletteSpinResponse;
import com.gift.gift.domain.roulette.dto.response.RouletteStatusResponse;
import com.gift.gift.domain.roulette.service.RouletteService;
import com.gift.gift.domain.roulette.service.RouletteSpinService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/roulette")
@RequiredArgsConstructor
public class RouletteController {

    private final RouletteService rouletteService;
    private final RouletteSpinService spinService;

    @GetMapping
    public ResponseEntity<ApiResponse<RouletteStatusResponse>> getStatus(@CurrentUserId Long userId) {
        return ResponseEntity.ok(ApiResponse.success("룰렛 참여 현황을 조회했습니다.", rouletteService.status(userId)));
    }

    @PostMapping("/attendance")
    public ResponseEntity<ApiResponse<RouletteAttendanceResponse>> attend(@CurrentUserId Long userId) {
        return ResponseEntity.ok(ApiResponse.success("출석 참여권을 확인했습니다.", rouletteService.attend(userId)));
    }

    @PostMapping("/spins")
    public ResponseEntity<ApiResponse<RouletteSpinResponse>> spin(
            @CurrentUserId Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.ok(ApiResponse.success("룰렛 실행 결과를 확인했습니다.",
                spinService.spin(userId, idempotencyKey)));
    }

}
