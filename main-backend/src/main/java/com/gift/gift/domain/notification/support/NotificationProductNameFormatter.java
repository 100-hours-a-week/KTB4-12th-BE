package com.gift.gift.domain.notification.support;

public final class NotificationProductNameFormatter {

    private static final int MAX_CODE_POINTS = 30;

    private NotificationProductNameFormatter() {
    }

    public static String abbreviate(String productName) {
        if (productName.codePointCount(0, productName.length()) <= MAX_CODE_POINTS) {
            return productName;
        }
        return productName.substring(0, productName.offsetByCodePoints(0, MAX_CODE_POINTS)) + "...";
    }
}
