# 쿠폰 PR 2 — 주문 예약 및 결제 실패 복구

관련 이슈: #470, 상위 계획: #473. PR 1(#469, PR #474)이 선행한다.

## 결제 요청

기존 `POST /payments`에 선택적 정수 `couponMultiplier`를 추가한다.
`2` 또는 `3`만 허용하며 생략·`null`은 미사용이다. 응답 성공 구조는 기존과 같다.
인증된 결제자의 쿠폰만 선택하며 공개 예약·복구 API는 만들지 않는다.

| 상황 | HTTP | 오류 코드 |
| --- | --- | --- |
| 2·3 이외 배수 | 400 | INVALID_REQUEST |
| 본인의 사용 가능 수량 부족 | 422 | COUPON_NOT_AVAILABLE |
| 같은 결제자·멱등키로 쿠폰 선택 변경 | 409 | IDEMPOTENCY_KEY_CONFLICT |
| PR3 전 쿠폰 결제 차단 | 409 | CONFLICT |

오류는 기존 `{message, error: {code, traceId, details?}}` 구조다.
입력 범위 오류는 `details`에 `couponMultiplier` / `OUT_OF_RANGE`를 포함한다.
쿠폰 미사용 요청의 해시는 기존과 같으며 쿠폰 선택 시에만 배수를 해시에 추가한다.

## 테이블 및 상태

V39에서 `order_coupon_usages`를 추가한다. 주문에는 보상액·배수 스냅샷을 추가하지 않는다.

| 필드 | 타입 | NULL | 역할 |
| --- | --- | --- | --- |
| id | BIGINT | 불가 | 자동 증가 PK |
| order_record_id | BIGINT | 불가 | orders.id FK, UNIQUE로 주문당 한 기록 |
| coupon_inventory_id | BIGINT | 불가 | coupon_inventories.id FK |
| status | VARCHAR(20) | 불가 | RESERVED / USED / RESTORED CHECK |
| created_at | DATETIME(6) | 불가 | 생성 시각 |
| updated_at | DATETIME(6) | 불가 | 변경 시각 |

배수는 연결된 수량 행에서 확인한다. 상태 USED는 PR3 사용 확정을 위해 정의하며 PR2에서는 전이하지 않는다.
두 FK 모두 별도 PK를 참조하는 비식별 관계이며 삭제 cascade는 없다.

- 주문 생성과 수량 1개 차감·RESERVED 저장은 같은 트랜잭션이다.
- `quantity > 0` 조건부 UPDATE로 잔여 수량을 초과하는 예약을 막는다.
- 같은 주문의 예약·복구는 주문 행을 잠금 조회하고 반복 요청을 중복 반영하지 않는다.
- 결제 거절 확정 또는 로컬 처리 실패에 따른 자동 취소 확정 시 RESTORED로 바꾸고 1개 복구한다.
- 결과 미확정 및 운영 확인 대상은 예약을 유지한다. 복구 실패 시 주문 변경도 롤백한다.
- 이미 승인된 결과를 늦게 도착한 거절로 덮어쓰지 않는다.

## 개방 및 검증

`app.coupon.payment-enabled`는 기본 false다. PR3에서 사용 확정·배수 보상 지급을 연결하기 전에는
운영에서 true로 설정하지 않는다. 정상 결제 후 취소·미수락 만료 정책은 후속 범위다.

자동 테스트는 MySQL에서 동시 예약, 멱등성, 소유권, 실패 복구, 미확정 유지, 롤백을 검증한다.
전체 테스트의 컨텍스트 캐시는 최대 8개로 제한하며 힙 증설은 하지 않는다.

Postman `payment-api.postman_collection.json`의 `05. PR2 쿠폰 요청 검증`은
기존 00·01 폴더 실행 후 기본 차단 설정에서 배수 입력·차단·선택 변경 충돌을 확인한다.
컬렉션 JSON과 스크립트 문법만 확인했으며 실제 서버를 통한 PR2 수동 검증은 미실행이다.
실제 결제 서버의 쿠폰 성공 경로와 룰렛 연동은 후속 검증으로 남긴다.
