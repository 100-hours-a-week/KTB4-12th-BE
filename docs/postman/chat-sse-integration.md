# PR3 — 실제 AI 테스트 서버 SSE 연동

## 준비

- Import: `chat-sse-integration.postman_collection.json`, `chat-sse-integration.postman_environment.json`.
- 환경 선택: `AI Chat PR3 SSE Integration Local`.
- `accessToken`: BE에서 로그인한 실제 access token. `Bearer ` 접두사 없이 입력한다.
- `recipientUserId`: BE DB에 있는 실제 활성 수신자 ID. 가이드의 합성 ID를 그대로 넣지 않는다.
- `baseUrl`: BE 주소, 기본 `http://localhost:8080`.
- BE 실행 환경: `AI_CHAT_BASE_URL=https://wjo-ai-chat-demo.hf.space/v1/chat/test/conversations`, AI 팀 서비스 토큰, connect 5s, JSON read 15s, SSE idle 60s/total 240s.
- AI 서비스 토큰은 Postman에 넣지 않는다. 모든 요청은 BE에 로그인 토큰으로 보낸다.
- Postman 요청 timeout은 SSE 관찰 시간보다 길게 설정한다(예: 300000ms).
- 제품 경로와 테스트 경로는 같은 대화 UUID를 공유하지 않는다.

## 실행 순서

요청은 **Send로 개별 실행**한다. 전체 Runner 실행은 하지 않는다. 11번은 실제 생성 취소 요청이다.

1. 01: 대화 생성. 새 UUID 자동 생성, conversationToken 저장. 다시 Send하면 같은 ID로 복구한다.
2. 02: 질문 접수. 기본 `/mock delayed_tokens`, 새 턴 UUID 자동 생성. 재전송은 최초 UUID와 메시지를 유지한다.
3. 03: SSE 최초 구독. 완료 전 조각이 표시되는지 확인한다. `turn.finished`의 상태와 최종 lastEventId도 확인한다.
4. 05: GET 턴 상태 확인. PROCESSING이면 후속 조회한다. GET 결과는 snapshot 변수에만 저장하여 수신 cursor를 덮어쓰지 않는다.
5. 06: 종료된 턴 마지막 ID로 연결해 200과 빈 스트림 종료 확인.
6. 07~10: 미래 cursor 409, 음수/추가 query 400, 토큰 위변조 404. SSE Accept 요청도 오류는 JSON이어야 한다.

## 재연결과 생성 계속 여부

새 시나리오를 `/mock reconnect`로 설정한다. 이전 턴이 종료됐는지 확인한 뒤 `sseTurnId`를 비우고 02를 실행한다. 같은 대화에서는 동시에 PROCESSING 턴을 하나만 만들 수 있다.

03에서 첫 answer.delta를 완전히 확인한 다음 연결을 닫는다. 마지막으로 확인한 숫자 id를 `reconnectAfterEventId`에 직접 입력하고 04를 실행한다. 이후 이벤트만 와야 한다. heartbeat 주석에는 id가 없으며 cursor를 증가시키지 않는다. 연결이 끊겨 스크립트가 실행되지 않아도 cursor를 추정하지 않는다.

05로 턴 상태도 확인한다. cancellation을 호출하지 않았다면 연결 종료만으로 CANCELLED가 되어서는 안 된다. EOF/200만으로 COMPLETED를 판단하지 않는다. 복구 상태가 불확실하면 GET 전체 턴을 반영하고 snapshotLastEventId를 새로운 복구 cursor로 직접 적용한다.

## 시나리오 바꾸기

이전 턴 종료 확인 → `mockMessage` 변경 → `sseTurnId` 비우기 → 02 실행. 재시도할 때는 비우지 않는다. 새 테스트 대화로 시작하려면 종료 확인 후 `sseConversationId`, `sseConversationToken`, `sseTurnId`를 비우고 01부터 실행한다.

| 메시지 | 확인 |
| --- | --- |
| /mock text | 한글·이모지 |
| /mock delayed_tokens | 점진적 답변 조각 |
| /mock reconnect | 첫 delta 이후 대기 구간에서 단절·재연결 |
| /mock slow | progress 이후 대기 중 heartbeat |
| /mock failed | 부분 답변 후 FAILED와 error.message |
| /mock timeout | AI fixture의 실패 종료; BE 60초 idle 테스트가 아님 |
| /mock cards | 가상 상품 카드. 실제 조회/구매하지 않는다 |

명시적 취소를 확인하려면 `/mock slow` 또는 `/mock delayed_tokens`를 새 턴으로 접수하고, 처리 중에 11을 실행한다. 완료가 먼저 저장되면 COMPLETED가 유지될 수 있다.

## 확인 항목과 제한

- SSE Headers: Content-Type text/event-stream, Cache-Control no-store, no-transform, X-Accel-Buffering no.
- JSON 스크립트는 응답 상태/기본 계약/traceId를 검증하고 대화·턴 상태를 저장한다.
- SSE 요청에는 자동 이벤트 파싱 스크립트를 넣지 않았다. Postman 이벤트 화면에서 도착 시간, id, event, data를 직접 확인한다. post-response script의 실행 시점에 기대어 점진적 전달·단절을 자동 통과 처리하지 않는다.
- 응답/헤더에 AI 서비스 토큰이 포함되지 않는지 확인한다. 서버 슬롯·upstream 정리는 앞서 작성한 자동 테스트와 BE 로그로 검증한다.
- 스크립트 구문과 pre-request의 UUID/body 보존·cursor/종료 상태 guard는 로컬에서 검증했다. 실제 토큰으로 Postman을 실행해 외부 연동이 성공했는지는 아직 확인하지 않았다.

참고: [AI 테스트 가이드](https://wjo-ai-chat-demo.hf.space/guide#testing), [Postman SSE 응답 화면](https://learning.postman.com/docs/use/send-requests/response-data/responses/#server-sent-events).
