package com.gift.gift.domain.randombox.dto.response;

import java.time.LocalDateTime;

import com.gift.gift.domain.randombox.entity.RandomBoxStatus;
import com.gift.gift.domain.randombox.entity.RandomBoxTicket;

public record RandomBoxResponse(long boxId, RandomBoxStatus status, LocalDateTime acquiredAt,
        LocalDateTime openedAt, String prizeName, String voucherCode) {
    public static RandomBoxResponse from(RandomBoxTicket box) {
        boolean won = box.status() == RandomBoxStatus.WON;
        return new RandomBoxResponse(box.getId(), box.status(), box.getAssignedAt(), box.getOpenedAt(),
                won ? box.getPrize().getName() : null, won ? box.getPrize().getVoucherCode() : null);
    }

    @Override
    public String toString() {
        return "RandomBoxResponse[boxId=" + boxId + ", status=" + status + "]";
    }
}
