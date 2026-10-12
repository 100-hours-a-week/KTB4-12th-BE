# 추천 기능 패키지 구조

이슈 #338: 프로파일링과 채팅 코드를 기능별로 분리한다. 클래스 이름과 실행 로직은 유지한다.

```text
domain/recommendation/
├── profiling/
│   ├── controller/       내부 프로파일 콜백 API
│   ├── dto/request/      프로파일 요청·콜백 입력
│   ├── dto/response/     AI 접수 응답
│   ├── entity/           프로파일·추천 상품 영속 모델
│   ├── exception/        기존 프로파일링 예외·오류 코드
│   ├── repository/       프로파일·추천 상품 조회 및 저장
│   ├── scheduler/        프로파일 전송·복구 실행
│   ├── service/          콜백·전송·트랜잭션 처리
│   ├── support/          운영 설정·전송 준비 데이터
│   └── validation/       콜백 입력 검증
└── chat/
    ├── config/           대화 토큰 설정·설정 검증
    ├── controller/       공개 채팅 JSON API
    ├── dto/request/      대화 생성·질문 입력
    ├── dto/response/     Conversation·Turn·응답 블록 (ChatModels)
    ├── exception/        기존 채팅 입력 예외·처리기
    ├── model/            대화 UUID·발신자·수신자 식별 정보
    ├── security/         대화 토큰 발급·검증·발급 결과
    ├── service/          사용자 검증·대화 소유권 검사·AI 호출 흐름
    └── web/              HTTP 요청 Body 크기 제한

infrastructure/ai/
├── profiling/            프로파일링 HTTP 클라이언트·설정
└── chat/                 채팅 HTTP 클라이언트·설정·설정값
```

테스트도 대상 클래스의 패키지에 맞춰 이동한다. 다른 도메인에서 사용하는 프로파일 저장소·서비스와 공통 인증 설정의 import도 함께 수정한다.

채팅 식별 정보는 DB Entity가 아니므로 `model`에 둔다. 외부 AI 서버와 통신하는 코드는 기존대로 `infrastructure`에 둔다.

API URL·요청·응답·오류 코드, JWT audience·만료 규칙, Bean 이름과 설정 키, DB 테이블·마이그레이션은 변경하지 않는다. 채팅 예외를 별도 도메인 예외로 정리하는 작업은 후속 변경으로 진행한다.
