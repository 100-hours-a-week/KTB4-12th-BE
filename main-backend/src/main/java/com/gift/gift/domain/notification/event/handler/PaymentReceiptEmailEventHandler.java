package com.gift.gift.domain.notification.event.handler;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gift.gift.domain.order.event.PaymentReceiptEmailEvent;

@Slf4j
@Component
public class PaymentReceiptEmailEventHandler {

    private static final DateTimeFormatter RECEIPT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public PaymentReceiptEmailEventHandler(
            JavaMailSender mailSender,
            @Value("${app.email.from-address}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PaymentReceiptEmailEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(event.recipientEmail());
        message.setSubject(PaymentReceiptEmailTemplate.SUBJECT);
        message.setText(PaymentReceiptEmailTemplate.BODY_FORMAT.formatted(
                event.orderId(),
                event.productName(),
                event.quantity(),
                formatCredit(event.creditAmount()),
                formatCredit(event.balanceAfter()),
                event.completedAt().format(RECEIPT_TIME_FORMAT)
        ));

        try {
            mailSender.send(message);
        } catch (MailException exception) {
            // shortcut: SMTP 실패는 기록만 한다. 발송 보장이 필요해지면 Outbox와 재시도를 도입한다.
            log.error(
                    "크레딧 사용 영수증 메일 발송에 실패했습니다. orderId={}, errorType={}",
                    event.orderId(),
                    exception.getClass().getSimpleName()
            );
        }
    }

    private String formatCredit(long amount) {
        return String.format(Locale.KOREA, "%,d", amount);
    }
}
