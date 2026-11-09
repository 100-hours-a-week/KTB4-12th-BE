package com.gift.gift.domain.bugreport.messaging;

import java.time.Instant;
import java.util.UUID;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.GiftApplication;
import com.gift.gift.domain.notification.service.NotificationReplayService;
import com.gift.gift.global.messaging.RabbitManagementProperties;
import com.gift.gift.global.messaging.RabbitPublisher;

public final class OutboxReplayCommand {
    private OutboxReplayCommand() {
    }

    public static void main(String[] args) {
        int exitCode = 1;
        try {
            var request = ReplayRequest.parse(System.getenv());
            String runId = UUID.randomUUID().toString();
            boolean report = request.target() == ReplayRequest.Target.DISCORD
                    || request.target() == ReplayRequest.Target.SHEETS;
            var application = new SpringApplication(GiftApplication.class);
            try (var context = application.run(startupArguments(report))) {
                java.util.function.BiConsumer<String, String> audit = (id, result) -> {
                    System.out.printf("REPLAY time=%s run=%s operator=%s target=%s execute=%s id=%s result=%s%n",
                            Instant.now(), runId, request.operator(), request.target(), request.execute(), id, result);
                    if (System.out.checkError()) throw new IllegalStateException("감사 출력을 저장하지 못했습니다.");
                };
                boolean complete = true;
                if (report) {
                    var factory = context.getBean("rabbitConnectionFactory", CachingConnectionFactory.class);
                    var management = context.getBean(RabbitManagementProperties.class);
                    new ReportTopologyPreflight(context.getBean("rabbitManagementRestClient", RestClient.class),
                            management.url(), factory.getVirtualHost()).verify();
                    try (var channel = factory.createConnection().createChannel(false)) {
                        try {
                            complete = new ReportDlqReplay(context.getBean(ObjectMapper.class),
                                    context.getBean(RabbitPublisher.class)).replay(channel, request, audit);
                        } finally {
                            RabbitUtils.setPhysicalCloseRequired(channel, true);
                        }
                    }
                } else {
                    var service = context.getBean(NotificationReplayService.class);
                    for (String id : request.ids()) {
                        audit.accept(id, "CHECKING");
                        String result = service.replay(Long.parseLong(id),
                                request.target() == ReplayRequest.Target.LEGACY_NOTIFICATION, request.execute());
                        audit.accept(id, result);
                        complete &= result.equals("REQUEUED") || result.equals("DRY_RUN_ELIGIBLE");
                    }
                }
                exitCode = complete ? 0 : 2;
            }
        } catch (Exception failure) {
            exitCode = 1;
            System.err.println("REPLAY_FAILED type=" + failure.getClass().getSimpleName()
                    + ". 원본 보존 상태와 감사 기록을 확인하세요.");
        }
        System.exit(exitCode);
    }

    static String[] startupArguments(boolean report) {
        return new String[]{
                "--spring.main.web-application-type=servlet", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.jpa.show-sql=false", "--spring.main.banner-mode=off",
                "--logging.level.root=OFF", "--logging.level.com.gift.gift=OFF", "--sentry.dsn=",
                "--app.scheduling.enabled=false", "--app.ai-profile.scheduling-enabled=false",
                "--app.ai-profile.amqp.enabled=false", "--app.product-search.outbox.enabled=false",
                "--app.ai-profile.result-consumer.enabled=false",
                "--app.product-search.sync.enabled=false", "--app.notification.common-outbox.enabled=false",
                "--app.report.discord.enabled=false", "--app.report.sheets.enabled=false",
                "--app.report.mq.enabled=" + report, "--app.outbox.enabled=" + report, "--spring.rabbitmq.dynamic=false"
        };
    }
}
