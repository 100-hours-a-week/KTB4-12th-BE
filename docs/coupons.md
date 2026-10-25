# 쿠폰 PR 1 — 수량 관리 및 보유 목록

관련 이슈: #469, 상위 계획: #473.

## API

`GET /coupons`는 Bearer Access Token으로 인증한 본인의 사용 가능한 쿠폰 수량을 조회한다.
Path·Query·Body는 없으며 사용자 ID는 인증 정보에서 가져온다.
`quantity > 0`인 항목만 `multiplier ASC`로 반환한다. 최대 두 종류이므로 페이지네이션은 없다.

```json
{
  "message": "보유 쿠폰을 조회했습니다.",
  "data": {
    "coupons": [
      { "multiplier": 2, "quantity": 1 }
    ]
  }
}
```

두 수량이 모두 0이면 200과 `coupons: []`를 반환한다. 미인증·유효하지 않은 토큰은
401 / UNAUTHORIZED, 내부 오류는 500 / INTERNAL_SERVER_ERROR이며 기존 공통 오류 응답을 사용한다.

## 테이블

`coupon_inventories`는 개별 쿠폰이 아닌 사용자별 배수의 사용 가능 수량을 저장한다.

| 필드 | 타입 | NULL | 기본값·역할 |
| --- | --- | --- | --- |
| id | BIGINT | 불가 | 자동 증가 PK |
| user_id | BIGINT | 불가 | users.id FK |
| multiplier | INT | 불가 | 2 또는 3 |
| quantity | INT | 불가 | 기본 0, 사용 가능 수량 |
| created_at | DATETIME(6) | 불가 | JPA Auditing으로 생성 시각 저장 |
| updated_at | DATETIME(6) | 불가 | 수량 일괄 갱신에서는 Clock 기준 시각 명시 |

- UNIQUE(user_id, multiplier), CHECK(multiplier IN (2, 3)), CHECK(quantity >= 0).
- FK 삭제 cascade는 없다. user_id는 PK에 포함되지 않는 비식별 관계다.
- 만료일·삭제일·개별 사용 상태는 없다. 수량이 0이어도 행을 유지한다.
- V37에서 테이블 생성, V38에서 기존 회원의 누락 행만 수량 0으로 보충한다.

## 초기화·내부 발급

회원가입 트랜잭션에서 `CouponInitializationService.initialize(userId)`로 두 행을 생성한다.
발급은 `CouponIssueService.issue(userId, multiplier)`를 같은 DB의 호출자 트랜잭션에서 사용한다.
두 서비스 모두 MANDATORY이며 외부 발급 API는 없다.

발급은 원자적 UPDATE로 1개 증가시키며 초기 행 누락·허용되지 않은 입력·INT 한도 초과는 실패한다.
이 서비스는 자체적으로 멱등하지 않다. Ava의 룰렛 호출자가 같은 실행을 한 번만 처리하고
결과 저장·수량 증가를 같은 트랜잭션에서 커밋해야 한다. 실제 룰렛 연동 검증은 후속 작업이다.

## 배포

회원가입 일시 중단 → 구버전 종료 → V37·V38 및 새 버전 적용 → 누락 확인 → 가입 재개 순으로 적용한다.
롤링 배포한다면 구버전 종료 후 V38의 누락 행 보완 SQL을 실행할 별도 절차가 필요하다.
이 절차는 배포 계획이며 실제 운영 실행을 확인한 기록은 아니다.

```sql
SELECT users.id, multipliers.multiplier
FROM users
CROSS JOIN (
    SELECT 2 AS multiplier
    UNION ALL
    SELECT 3 AS multiplier
) AS multipliers
LEFT JOIN coupon_inventories AS inventory
    ON inventory.user_id = users.id
    AND inventory.multiplier = multipliers.multiplier
WHERE inventory.id IS NULL;
```

결과가 0행인지 확인한다. 이미 지급한 수량은 보완 작업에서 변경하지 않는다.

## 검증 및 제외 범위

[Postman 사용 안내](postman/coupon-api.md)를 따른다.
자동 테스트는 초기화·가입 롤백·인증·소유권·0 제외·동시 증가·DB 제약·수량 상한·V38 재실행을 검증한다.
결제 예약·쿠폰 차감/복구·배수 보상 지급·룰렛 참여권·비동기 발급은 이번 PR에 포함하지 않는다.
기존 가입·친구·선물 보상 금액도 변경하지 않는다.
