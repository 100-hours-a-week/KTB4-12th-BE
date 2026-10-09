CREATE TABLE user_gift_preferences
(
    id          BIGINT NOT NULL AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    preference  TEXT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    deleted_at  DATETIME(6) NULL,
    CONSTRAINT pk_user_gift_preferences PRIMARY KEY (id),
    CONSTRAINT uk_user_gift_preferences_user UNIQUE (user_id),
    CONSTRAINT fk_user_gift_preferences_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
