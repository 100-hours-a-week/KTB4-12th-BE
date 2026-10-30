-- 구버전 SSE 서버를 모두 종료한 뒤 적용한다. 원본 알림·읽음 상태·생성 Outbox는 유지한다.
DROP TABLE notification_sse_deliveries;
DROP TABLE notification_sse_connections;
