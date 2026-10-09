package com.gift.gift.domain.preference.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import com.gift.gift.domain.preference.dto.request.SaveGiftPreferenceRequest;
import com.gift.gift.domain.preference.support.PreferencePolicy;
import com.gift.gift.global.exception.ValidationErrorReason;

public class GiftPreferenceValidator
        implements ConstraintValidator<ValidGiftPreference, SaveGiftPreferenceRequest> {

    @Override
    public boolean isValid(SaveGiftPreferenceRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        if (!request.hasPreference()) {
            addViolation(context, "preference", ValidationErrorReason.Message.REQUIRED);
            return false;
        }

        String original = request.preferenceValue();
        if (original == null) {
            return true;
        }
        if (original.codePointCount(0, original.length()) > PreferencePolicy.MAX_PREFERENCE_CODE_POINTS) {
            addViolation(context, "preference", ValidationErrorReason.Message.TOO_LONG);
            return false;
        }
        return true;
    }

    private void addViolation(ConstraintValidatorContext context, String field, String reason) {
        context.buildConstraintViolationWithTemplate(reason)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
