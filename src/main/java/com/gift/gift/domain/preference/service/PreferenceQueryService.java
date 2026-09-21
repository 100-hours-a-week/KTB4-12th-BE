package com.gift.gift.domain.preference.service;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.preference.dto.response.PreferenceWarningResult;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PreferenceQueryService {

    private final UserDislikeCategoryRepository userDislikeCategoryRepository;

    /*
       상품의 세부 카테고리가 속한 대분류를 수신자가 비선호로 등록했으면 대분류 정보를 반환한다.
       수신자와 상품의 유효성은 호출하는 서비스에서 검증한다.
     */
    public Optional<PreferenceWarningResult> findMatchingWarning(
            Long userId,
            Long productCategoryId
    ) {
        return userDislikeCategoryRepository
                .findActiveByUserIdAndProductCategoryId(userId, productCategoryId)
                .map(dislike -> PreferenceWarningResult.from(dislike.getCategory()));
    }
}
