CREATE TABLE reviews (
                         id BIGINT NOT NULL AUTO_INCREMENT,
                         gift_history_id BIGINT NOT NULL,
                         user_id BIGINT NOT NULL,
                         rating SMALLINT NOT NULL,
                         review_text TEXT NULL,
                         deleted_at DATETIME(6) NULL,
                         created_at DATETIME(6) NOT NULL,
                         updated_at DATETIME(6) NOT NULL,
                         active_gift_history_id BIGINT
                             GENERATED ALWAYS AS (
                                 CASE
                                     WHEN deleted_at IS NULL THEN gift_history_id
                                     ELSE NULL
                                     END
                                 ) STORED,
                         CONSTRAINT pk_reviews PRIMARY KEY (id),
                         CONSTRAINT fk_reviews_gift_history
                             FOREIGN KEY (gift_history_id) REFERENCES gift_histories (id),
                         CONSTRAINT fk_reviews_user
                             FOREIGN KEY (user_id) REFERENCES users (id),
                         CONSTRAINT chk_reviews_rating
                             CHECK (rating BETWEEN 1 AND 5),
                         CONSTRAINT chk_reviews_text_length
                             CHECK (
                                 review_text IS NULL
                                     OR CHAR_LENGTH(review_text) <= 300
                                 ),
                         CONSTRAINT uk_reviews_active_gift_history
                             UNIQUE (active_gift_history_id),
                         INDEX idx_reviews_gift_history (gift_history_id),
                         INDEX idx_reviews_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
