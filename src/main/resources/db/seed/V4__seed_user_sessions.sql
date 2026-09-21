-- 로컬 개발용 목 데이터: 로그인 세션
-- refresh_token은 실제 토큰이 아닌 더미 SHA-256 hex(64자)이며, 이 값으로는 재발급할 수 없다.
-- 시각은 마이그레이션 실행 시점(NOW()) 기준 상대값이다.

INSERT INTO user_sessions (user_id, refresh_token, authenticated_at, expires_at, revoked_at, deleted_at, created_at, updated_at) VALUES
-- 활성 세션 (user 1은 두 기기에서 로그인)
(1, SHA2('session-seed-1', 256), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 13 DAY), NULL, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY)),
(1, SHA2('session-seed-2', 256), DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 11 DAY), NULL, NULL, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)),
(2, SHA2('session-seed-3', 256), DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_ADD(NOW(), INTERVAL 14 DAY), NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_SUB(NOW(), INTERVAL 2 HOUR)),
(3, SHA2('session-seed-4', 256), DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_ADD(NOW(), INTERVAL 8 DAY), NULL, NULL, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY)),
-- 로그아웃(폐기)된 세션
(4, SHA2('session-seed-5', 256), DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_ADD(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
-- 만료된 세션
(5, SHA2('session-seed-6', 256), DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY), NULL, NULL, DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
-- 폐기 후 삭제 처리된 세션
(6, SHA2('session-seed-7', 256), DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 16 DAY), DATE_SUB(NOW(), INTERVAL 25 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY));

-- 탈퇴 사용자(id 12)의 세션은 탈퇴 시점에 폐기됨
INSERT INTO user_sessions (user_id, refresh_token, authenticated_at, expires_at, revoked_at, deleted_at, created_at, updated_at)
SELECT id, SHA2('session-seed-8', 256), '2026-09-01 09:00:00', '2026-09-15 09:00:00', deleted_at, deleted_at, '2026-09-01 09:00:00', deleted_at
FROM users
WHERE id = 12;
