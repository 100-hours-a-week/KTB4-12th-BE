-- 로컬 개발용 목 데이터 로그인 테스트를 위해 시드 사용자(1~11)의 비밀번호를 Test1234!로 설정한다.
-- 탈퇴 사용자(12)는 로그인할 수 없으므로 변경하지 않는다.
UPDATE users
SET password = '$2a$10$90EeqOYdmT5Vz4OYhJav0.9tc83uIdAaC1U8ncVare0Ax7ynF9Gaq'
WHERE id BETWEEN 1 AND 11;
