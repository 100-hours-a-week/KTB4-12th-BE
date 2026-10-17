# 채팅 오류 처리

## 채팅 도메인 예외 — 2026-10-17

채팅 전용 오류 의미와 메시지는 BE의 `ChatErrorCode`에서 관리하고 `ChatException`은 기존 `BusinessException` 공통 처리기를 사용한다. 공개 `error.code`는 기존 API 계약을 유지하며 내부 Enum 이름을 응답 코드로 반환하지 않는다.

| 내부 ChatErrorCode | 공개 error.code | HTTP | 메시지 |
| --- | --- | --- | --- |
| INVALID_REQUEST | INVALID_REQUEST | 400 | 요청 형식이나 값이 올바르지 않습니다. |
| INVALID_MESSAGE | INVALID_REQUEST | 400 | 입력값을 확인해 주세요. |
| RECIPIENT_NOT_FOUND | RECIPIENT_NOT_FOUND | 404 | 선택한 수신자 정보를 확인할 수 없습니다. |
| RESOURCE_NOT_FOUND | NOT_FOUND | 404 | 대화 또는 질문을 찾을 수 없습니다. |
| CONVERSATION_CONFLICT | CONFLICT | 409 | 같은 대화 ID에 다른 사용자 정보를 사용할 수 없습니다. |
| TURN_CONFLICT | CONFLICT | 409 | 질문 ID 또는 현재 대화 처리 상태가 요청과 충돌합니다. |
| PAYLOAD_TOO_LARGE | PAYLOAD_TOO_LARGE | 413 | 요청 본문 크기가 너무 큽니다. |
| RATE_LIMIT_EXCEEDED | TOO_MANY_REQUESTS | 429 | 요청이 너무 많아요. 잠시 후 다시 시도해주세요. |
| DAILY_LIMIT_EXCEEDED | AI_DAILY_LIMIT_EXCEEDED | 429 | 오늘 사용할 수 있는 AI 채팅 횟수를 모두 사용했습니다. |
| AI_SERVICE_UNAVAILABLE | SERVICE_UNAVAILABLE | 503 | AI 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요. |

- 로그인 실패는 기존 `401 / UNAUTHORIZED`를 사용한다.
- 대화 토큰 누락·위변조·만료·소유권 불일치와 후속 요청의 수신자 비활성화는 `404 / NOT_FOUND`와 동일한 채팅 메시지를 반환한다. 실패 원인을 공개 응답으로 구분하지 않는다.
- `ChatInputException`·처리기는 `message` 필드의 REQUIRED·TOO_LONG `details`를 유지한다. JSON 역직렬화 등 공통 형식 오류는 기존 공통 처리기를 사용한다.
- AI의 오류 코드도 기존 문자열로 검증한다. AI 인증·통신·응답 계약 오류는 BE의 `503 / SERVICE_UNAVAILABLE`로 변환한다. FAILED Turn의 내부 `error.message`와 HTTP 오류 봉투는 구분한다.
- 서울 시간 기준 사용자별 신규 `turnId`는 하루 10개까지 허용한다. 동일 `turnId` 재요청과 조회·SSE 연결은 사용 횟수에 포함하지 않는다.
- 공통 `CONFLICT` 기본 메시지는 “요청이 현재 상태와 충돌합니다.”, `SERVICE_UNAVAILABLE`은 “서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.”로 일반화한다. 채팅 응답에는 위의 전용 메시지를 사용한다.
- SSE의 이벤트 cursor 오류는 PR 3에서 기존 명세에 맞춰 구현한다. 이번 PR은 JSON API 5개와 대화 소유권 검증에 적용한다.

