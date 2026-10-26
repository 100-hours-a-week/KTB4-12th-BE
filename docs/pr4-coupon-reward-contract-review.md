# PR4 쿠폰·보상 API 변경안 — 명세 반영 검토용

이 문서는 PR4 구현 결과와 응답 변경을 정리한다. 사용자 확인 후 [Google Sheets API 명세](https://docs.google.com/spreadsheets/d/1XOZnXAud8XNKR1QaoxsG7l7bxTVFgEqa4VTBkC3sKCs/edit?gid=66164690)에 결제 생성·상태 조회·쿠폰 조회 설명과 응답을 반영했다(E266·G266:G267, E285·G285, E335·G335:G336). Notion API 명세와 별도 공통 API 문서 인덱스는 수정하지 않았다. FE 화면 구현은 FE #269에서 진행한다. 사용자 결정에 따라 룰렛 API·참여 가능 정보·오늘 한도 연동은 이번 구현에서 제외한다.

## 적용 API

| API | 변경 | 인증·조회 범위 |
| --- | --- | --- |
| GET /coupons | data.baseRewardAmount 추가 | 인증된 본인, 기존 coupons 목록 유지 |
| POST /payments | 완료 응답에 쿠폰·예상/실제 보상 4개 필드 추가 | 인증된 결제자, 기존 요청·멱등키 유지 |
| GET /payments/{orderId} | 쿠폰·예상/실제 보상 4개 필드 추가 | 본인 결제만 조회, 타인·없는 주문 404 |
| POST /gifts/received/{giftId}/accept | 변경 없음 | 수신자가 호출, rewardAmount는 발신자의 실제 보상 |

새 API·테이블·컬럼·마이그레이션은 추가하지 않는다. 수락 API를 호출한 수신자의 잔액에 보상을 합산하지 않는다.

## 결제 전 쿠폰 선택

GET /coupons의 성공 응답 예시:

```json
{
  "message": "보유 쿠폰을 조회했습니다.",
  "data": {
    "coupons": [{ "multiplier": 2, "quantity": 1 }],
    "baseRewardAmount": 50000
  }
}
```

- 본인의 quantity > 0 항목만 배수 오름차순으로 반환한다. 최대 두 항목이며 페이지네이션은 없다.
- 미보유는 coupons: []이다. 이때도 baseRewardAmount는 제공하므로 쿠폰 미사용 예상 보상을 표시할 수 있다.
- FE의 결제 전 예상 보상은 baseRewardAmount × 선택 배수이며 미사용 배수는 1이다. 서버의 실제 지급·사후 조회도 동일한 GiftPolicy 값을 사용한다.
- POST /payments의 couponMultiplier는 2 또는 3이며, 미사용은 생략 또는 null이다. 1·0으로 보내지 않는다.
- 이 목록 조회는 쿠폰을 예약하지 않는다. 최종 수량 검증·예약은 주문 생성에서 수행한다.

## 결제 생성·상태 조회 응답의 추가 필드

아래 필드는 기존 data 객체에 추가한다. 기존 결제 필드는 삭제하거나 의미를 변경하지 않는다.

| 필드 | 타입 | null | 의미 |
| --- | --- | --- | --- |
| couponMultiplier | Number | 허용 | 해당 주문이 선택한 쿠폰 배수 2·3. 쿠폰 미사용은 null |
| couponStatus | String | 허용 | RESERVED·USED·RESTORED. 쿠폰 미사용은 null |
| expectedRewardAmount | Number | 허용 | 결제 완료·수락 대기 중의 예상 보상. 아직 지급한 금액이 아님 |
| rewardAmount | Number | 허용 | 기존 보상 원장에 저장된 실제 지급액. 미지급은 null |

미지급을 0으로 표시하지 않는다. couponMultiplier는 선택 기록이므로 쿠폰 복구 후에도 2·3을 보존하고 couponStatus로 복구 여부를 구분한다.

### 상태별 예시 — 2배 선택

| 상태 | couponMultiplier | couponStatus | expectedRewardAmount | rewardAmount |
| --- | --- | --- | --- | --- |
| 결제 진행·결과 미확정 | 2 | RESERVED | null | null |
| 결제·발송 완료, 수락 대기 | 2 | USED | 100000 | null |
| 상대방 수락·보상 지급 완료 | 2 | USED | null | 원장의 실제 금액 |
| 결제 실패 확정·예약 복구 | 2 | RESTORED | null | null |
| 미수락 취소·만료 확정 | 2 | RESTORED | null | null |

쿠폰 미사용이면 couponMultiplier·couponStatus 모두 null이고, 수락 대기 예상 보상은 50000이다. 3배는 예상 보상 150000이다. 수락 전 예상값은 지급 보장이 아니며, 미수락 취소·만료 확정 시 더 이상 제공하지 않는다.

결제 완료 직후 data에 추가되는 값:

```json
{
  "couponMultiplier": 2,
  "couponStatus": "USED",
  "expectedRewardAmount": 100000,
  "rewardAmount": null
}
```

수락 후 GET /payments/{orderId}의 data에 추가되는 값:

```json
{
  "couponMultiplier": 2,
  "couponStatus": "USED",
  "expectedRewardAmount": null,
  "rewardAmount": 100000
}
```

실제 지급액은 현재 기본 보상으로 재계산하지 않는다. 예전에 쿠폰 없이 100000을 지급한 기록은 rewardAmount: 100000으로 보존한다. 이미 수락 완료한 선물의 원장이 누락되면 0·예상액으로 대체하지 않고 시스템 오류로 처리한다.

## 재조회·호환 및 실패 처리

- 발신자는 기존 보낸 선물 목록·상세의 orderId로 GET /payments/{orderId}를 호출해 결제 후 화면·새로고침·알림 진입의 쿠폰·보상 결과를 복원한다. 별도 보상 조회 API는 만들지 않는다.
- 결제 생성 성공은 기존 201, 같은 요청 재사용은 기존 200, 진행 중 응답은 기존 202와 Retry-After를 유지한다. 202 응답에는 위 완료 필드를 억지로 넣지 않으며 기존 orderId로 상태를 조회한다.
- 같은 결제를 수락 후 다시 요청하면 상태·보상 정보는 현재 결과를 반영한다. 쿠폰 추가 차감·보상 추가 지급은 없으며 balanceAfter는 기존 결제 원장의 결제 직후 잔액 의미를 유지한다. 현재 잔액은 기존 크레딧 잔액 API로 조회한다.
- couponMultiplier를 보내지 않는 기존 요청은 유지한다. 같은 멱등키로 선택 배수를 바꾸면 기존 409 IDEMPOTENCY_KEY_CONFLICT이다.
- 쿠폰 수량 부족은 기존 422 COUPON_NOT_AVAILABLE, 지원하지 않는 배수는 기존 400 INVALID_REQUEST·couponMultiplier 필드 오류다.
- 기존 결제 결과 미확정은 쿠폰 예약을 유지한다. 네트워크·조회 오류만으로 FE가 쿠폰 복구나 결제 실패를 확정하지 않는다.
- GET은 쿠폰 수량·원장·선물 상태를 변경하지 않는다. 상태 조회는 REPEATABLE_READ 트랜잭션에서 주문·선물·쿠폰·원장을 읽어 동시 수락·취소의 서로 다른 시점 값이 섞이지 않게 한다.
- 결제 완료 직후에는 JPQL projection으로 쿠폰 상태를 읽어 벌크 갱신 전 영속성 컨텍스트의 RESERVED 값을 재사용하지 않는다.

## FE 표시 기준

- 쿠폰 미보유·조회 실패·로딩을 구분한다. 미보유여도 결제할 수 있다.
- 수락 전에는 '상대방이 수락하면 지급됩니다'로 안내하고 예상 보상을 balanceAfter에 합산하지 않는다.
- 실제 보상은 rewardAmount와 기존 GIFT_COMPLETED_REWARD 알림의 제목·본문을 사용한다. 알림 문구를 파싱해 금액·상태를 추정하지 않는다.
- 보상 알림 저장 시점은 기존 AFTER_COMMIT이며 상태 조회의 실제 지급 여부는 알림 생성 여부가 아니라 보상 원장을 기준으로 한다.

## 확인 범위

- MySQL·MockMvc로 미사용/2배/3배 결제 응답, 수락 전·후 재조회, 원장 금액 보존, 실패·취소·만료 상태, 소유권·미인증 및 멱등 재요청을 검증한다.
- Postman 기존 PR3 컬렉션에 기본 보상액·추가 필드와 수락 전·후/취소 후 상태 조회를 추가했다. 환경 변수와 pr3_ 실행 키는 기존 값을 재사용한다.
- 전체 회귀: 메인 서버 1746개 중 1737개 통과·9개 제외·실패 0개, 결제 서버 48개 통과. 마지막 import·테스트 정리 후 쿠폰 조회·보상 HTTP 계약·결제 상태 조회 관련 테스트도 재실행해 통과했다.
- Postman 스크립트 67개 문법 검사, PR4 검증 28건의 정상 fixture 통과 및 잘못된 fixture 28건 거부를 확인했다. 실제 서버를 대상으로 한 Collection Runner 실행은 하지 않았다.
- 룰렛·FE 화면은 이번 구현 범위가 아니다. API 시트는 사용자 요청에 따라 반영하고 JSON·인접 데이터·서식 보존을 확인했다. ERD·DB 컬럼 추가는 없다.

관련 이슈: BE #472 / FE 100-hours-a-week/KTB4-12th-FE#269
