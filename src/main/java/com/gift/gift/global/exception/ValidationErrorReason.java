package com.gift.gift.global.exception;

public enum ValidationErrorReason {

    INVALID_FORMAT,
    AGE_REQUIREMENT_NOT_MET,
    REQUIRED,
    TOO_SHORT,
    TOO_LONG,
    DUPLICATE_CATEGORY_ID,
    OUT_OF_RANGE,
    MAX_LENGTH_EXCEEDED,
    DUPLICATE_TERM_ID,
    INVALID_VALUE,
    INVALID_TYPE,
    INVALID_DATE,
    UNKNOWN_FIELD,
    EMPTY_UPDATE_FIELDS;

    public static final class Message {

        public static final String INVALID_FORMAT = "INVALID_FORMAT";
        public static final String AGE_REQUIREMENT_NOT_MET =
                "AGE_REQUIREMENT_NOT_MET";
        public static final String REQUIRED = "REQUIRED";
        public static final String TOO_SHORT = "TOO_SHORT";
        public static final String TOO_LONG = "TOO_LONG";
        public static final String DUPLICATE_CATEGORY_ID =
                "DUPLICATE_CATEGORY_ID";
        public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
        public static final String MAX_LENGTH_EXCEEDED =
                "MAX_LENGTH_EXCEEDED";
        public static final String INVALID_VALUE = "INVALID_VALUE";
        public static final String DUPLICATE_TERM_ID =
                "DUPLICATE_TERM_ID";
        public static final String INVALID_TYPE = "INVALID_TYPE";
        public static final String INVALID_DATE = "INVALID_DATE";
        public static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
        public static final String EMPTY_UPDATE_FIELDS =
                "EMPTY_UPDATE_FIELDS";

        private Message() {
        }
    }
}
