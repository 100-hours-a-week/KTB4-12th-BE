package com.gift.gift.domain.randombox.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.randombox.dto.response.RandomBoxResponse;
import com.gift.gift.domain.randombox.entity.RandomBoxTicket;
import com.gift.gift.domain.randombox.exception.RandomBoxNotFoundException;
import com.gift.gift.domain.randombox.repository.RandomBoxTicketRepository;

@Service
@RequiredArgsConstructor
public class RandomBoxService {
    private final RandomBoxTicketRepository tickets;
    private final Clock clock;

    @Transactional(readOnly = true)
    public RandomBoxResponse detail(Long userId, Long boxId) {
        return RandomBoxResponse.from(tickets.findByIdAndAssignedUserId(boxId, userId)
                .orElseThrow(RandomBoxNotFoundException::new));
    }

    @Transactional
    public RandomBoxResponse open(Long userId, Long boxId) {
        RandomBoxTicket ticket = tickets.findOwnedForUpdate(boxId, userId)
                .orElseThrow(RandomBoxNotFoundException::new);
        ticket.open(LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul")));
        return RandomBoxResponse.from(ticket);
    }
}
