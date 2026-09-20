-- 로컬 개발용 초기 스키마
-- 엔티티 기준: users, terms, term_consents, friends, categories, products, product_images, gift_histories
-- 기존 로컬 DB(Hibernate ddl-auto로 생성된 테이블)에서도 그대로 적용할 수 있도록 IF NOT EXISTS 사용

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(30) NOT NULL,
    birth DATE NOT NULL,
    status VARCHAR(255) NOT NULL,
    is_birthday_public BIT NOT NULL DEFAULT 0,
    is_first_login BIT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS terms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    term_code VARCHAR(50) NOT NULL,
    version INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    is_required BIT NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_terms PRIMARY KEY (id),
    CONSTRAINT uk_terms_code_version UNIQUE (term_code, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NULL,
    name VARCHAR(100) NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_name UNIQUE (name),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    brand VARCHAR(255) NOT NULL,
    description TEXT NULL,
    price DECIMAL(12, 0) NOT NULL,
    quantity INT NOT NULL,
    views INT NOT NULL DEFAULT 0,
    sales INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS product_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    sort_order INT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_product_images PRIMARY KEY (id),
    CONSTRAINT uk_product_images_product_sort_order UNIQUE (product_id, sort_order),
    CONSTRAINT uk_product_images_object_key UNIQUE (object_key),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS term_consents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    term_id BIGINT NOT NULL,
    is_agreed BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_term_consents PRIMARY KEY (id),
    CONSTRAINT uk_term_consents_user_term UNIQUE (user_id, term_id),
    CONSTRAINT fk_term_consents_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_term_consents_term FOREIGN KEY (term_id) REFERENCES terms (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS friends (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    friend_user_id BIGINT NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_friends PRIMARY KEY (id),
    CONSTRAINT uk_friends_user_friend_user UNIQUE (user_id, friend_user_id),
    CONSTRAINT chk_friends_distinct_users CHECK (user_id <> friend_user_id),
    CONSTRAINT fk_friends_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_friends_friend_user FOREIGN KEY (friend_user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS gift_histories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    product_price_snapshot DECIMAL(12, 0) NOT NULL,
    product_name_snapshot VARCHAR(255) NOT NULL,
    status ENUM('COMPLETED', 'FAILED', 'PROCESSING') NOT NULL DEFAULT 'COMPLETED',
    idempotency_key BINARY(16) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_gift_histories PRIMARY KEY (id),
    CONSTRAINT uk_gift_histories_sender_idempotency UNIQUE (sender_id, idempotency_key),
    CONSTRAINT chk_gift_histories_distinct_users CHECK (sender_id <> recipient_id),
    CONSTRAINT chk_gift_histories_quantity_positive CHECK (quantity >= 1),
    CONSTRAINT chk_gift_histories_price_non_negative CHECK (product_price_snapshot >= 0),
    CONSTRAINT chk_gift_histories_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT fk_gift_histories_sender FOREIGN KEY (sender_id) REFERENCES users (id),
    CONSTRAINT fk_gift_histories_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT fk_gift_histories_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- MySQL은 CREATE INDEX IF NOT EXISTS를 지원하지 않아서,
-- Hibernate ddl-auto로 이미 생성된 로컬 DB와의 중복 생성 오류를 피하기 위해 필요한 경우에만 생성한다.
SET @idx_sender := (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_gift_histories_sender_completed ON gift_histories (sender_id, completed_at DESC, id DESC)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'gift_histories'
      AND index_name = 'idx_gift_histories_sender_completed'
);
PREPARE stmt_sender FROM @idx_sender;
EXECUTE stmt_sender;
DEALLOCATE PREPARE stmt_sender;

SET @idx_recipient := (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_gift_histories_recipient_completed ON gift_histories (recipient_id, completed_at DESC, id DESC)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'gift_histories'
      AND index_name = 'idx_gift_histories_recipient_completed'
);
PREPARE stmt_recipient FROM @idx_recipient;
EXECUTE stmt_recipient;
DEALLOCATE PREPARE stmt_recipient;
