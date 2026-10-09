CREATE TABLE friendships (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id_1 BIGINT NOT NULL,
    user_id_2 BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT uk_friendships_pair UNIQUE (user_id_1, user_id_2),
    CONSTRAINT chk_friendships_distinct_users CHECK (user_id_1 < user_id_2),
    CONSTRAINT fk_friendships_user1 FOREIGN KEY (user_id_1) REFERENCES users(id),
    CONSTRAINT fk_friendships_user2 FOREIGN KEY (user_id_2) REFERENCES users(id),
    INDEX idx_friendships_user2 (user_id_2)
);

CREATE TABLE friend_requests (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    requester_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    pending_user_id_1 BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'PENDING' THEN LEAST(requester_id, receiver_id) ELSE NULL END) STORED,
    pending_user_id_2 BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'PENDING' THEN GREATEST(requester_id, receiver_id) ELSE NULL END) STORED,
    CONSTRAINT uk_friend_requests_pending_pair UNIQUE (pending_user_id_1, pending_user_id_2),
    CONSTRAINT chk_friend_requests_distinct_users CHECK (requester_id <> receiver_id),
    CONSTRAINT chk_friend_requests_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED')),
    CONSTRAINT fk_friend_requests_requester FOREIGN KEY (requester_id) REFERENCES users(id),
    CONSTRAINT fk_friend_requests_receiver FOREIGN KEY (receiver_id) REFERENCES users(id),
    INDEX idx_friend_requests_receiver_status (receiver_id, status, created_at DESC, id DESC),
    INDEX idx_friend_requests_requester_status (requester_id, status, created_at DESC, id DESC)
);

-- 날짜가 다른 역방향 행도 하나의 사용자 쌍으로 병합한다. 원본 friends는 대조를 위해 보존한다.
INSERT INTO friendships (user_id_1, user_id_2, created_at, updated_at)
SELECT LEAST(user_id, friend_user_id), GREATEST(user_id, friend_user_id),
       MIN(created_at), MAX(updated_at)
FROM friends
WHERE deleted_at IS NULL
GROUP BY LEAST(user_id, friend_user_id), GREATEST(user_id, friend_user_id);
