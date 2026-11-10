package com.gift.gift.domain.recommendation.profiling.support;

import java.time.LocalDateTime;

import org.springframework.boot.SpringApplication;

import com.gift.gift.GiftApplication;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingRecoveryService;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingRecoveryService.Action;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingRecoveryService.Command;

public final class ProfilingRecoveryCommand {

    private ProfilingRecoveryCommand() {
    }

    public static void main(String[] args) {
        int exitCode = 0;

        try {
            String action = required("PROFILING_RECOVERY_ACTION");

            int page = Integer.parseInt(
                    System.getenv().getOrDefault(
                            "PROFILING_RECOVERY_PAGE",
                            "0"
                    )
            );

            Command command = "LIST".equals(action)
                    ? null
                    : new Command(
                    Action.valueOf(action),
                    Long.valueOf(required(
                            "PROFILING_RECOVERY_PROFILE_ID"
                    )),
                    required("PROFILING_RECOVERY_REQUEST_ID"),
                    Long.parseLong(required(
                            "PROFILING_RECOVERY_SOURCE_VERSION"
                    )),
                    LocalDateTime.parse(required(
                            "PROFILING_RECOVERY_UPDATED_AT"
                    )),
                    required("PROFILING_RECOVERY_OPERATOR"),
                    required("PROFILING_RECOVERY_REASON")
            );

            SpringApplication application =
                    new SpringApplication(GiftApplication.class);

            try (var context = application.run(
                    "--spring.main.web-application-type=servlet",
                    "--server.address=127.0.0.1",
                    "--server.port=0",
                    "--spring.flyway.enabled=false",
                    "--spring.jpa.hibernate.ddl-auto=validate",
                    "--spring.jpa.show-sql=false",
                    "--app.scheduling.enabled=false",
                    "--app.ai-profile.scheduling-enabled=false",
                    "--app.ai-profile.amqp.enabled=false",
                    "--app.ai-profile.result-consumer.enabled=false",
                    "--app.outbox.enabled=false",
                    "--spring.rabbitmq.dynamic=false"
            )) {
                var service = context.getBean(
                        ProfilingRecoveryService.class
                );

                if (command == null) {
                    System.out.println(
                            "requestId\tprofileId\tversion\tcurrentVersion"
                                    + "\tanalyzedVersion\tattempts"
                                    + "\terrorCode\tupdatedAt"
                    );

                    for (var job : service.listBlocked(page)) {
                        System.out.printf(
                                "%s\t%d\t%d\t%d\t%d\t%d\t%s\t%s%n",
                                job.getRequestId(),
                                job.getProfileId(),
                                job.getSourceVersion(),
                                job.getCurrentSourceVersion(),
                                job.getAnalyzedSourceVersion(),
                                job.getAttemptCount(),
                                job.getLastErrorCode(),
                                job.getUpdatedAt()
                        );
                    }
                } else {
                    var result = service.recover(command);

                    System.out.printf(
                            "RECOVERY_OK action=%s profileId=%d "
                                    + "previousRequestId=%s "
                                    + "nextRequestId=%s "
                                    + "previousVersion=%d nextVersion=%d%n",
                            result.action(),
                            result.profileId(),
                            result.previousRequestId(),
                            result.nextRequestId(),
                            result.previousSourceVersion(),
                            result.nextSourceVersion()
                    );
                }
            }
        } catch (Exception exception) {
            // 예외 메시지에 포함될 수 있는 접속·입력 정보를 출력하지 않는다.
            System.err.println(
                    "RECOVERY_FAILED type="
                            + exception.getClass().getSimpleName()
                            + ". 대상 상태·버전·수정 시각과 실행 환경을 확인하세요."
            );
            exitCode = 1;
        }

        System.exit(exitCode);
    }

    private static String required(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "필수 환경변수가 없습니다: " + name
            );
        }

        return value;
    }
}
