CREATE TABLE user_dislike_categories
(
    id          BIGINT NOT NULL AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    deleted_at  DATETIME(6) NULL,
    CONSTRAINT pk_user_dislike_categories PRIMARY KEY (id),
    CONSTRAINT uk_user_dislike_categories_user_category
        UNIQUE (user_id, category_id),
    CONSTRAINT fk_user_dislike_categories_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_dislike_categories_category
        FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
