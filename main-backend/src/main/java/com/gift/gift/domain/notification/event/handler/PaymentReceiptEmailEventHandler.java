package com.gift.gift.domain.notification.event.handler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryType;
import com.gift.gift.domain.order.repository.PaymentReceiptMailData;

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

    public void sendFromQueue(PaymentReceiptDeliveryType receiptType, PaymentReceiptMailData mailData) {
        String subject = switch (receiptType) {
            case PAYMENT -> PaymentReceiptEmailTemplate.SUBJECT;
            case CANCELLATION -> PaymentReceiptEmailTemplate.REFUND_SUBJECT;
        };

        String body = switch (receiptType) {
            case PAYMENT -> PaymentReceiptEmailTemplate.BODY_FORMAT.formatted(
                    mailData.orderId(),
                    mailData.productName(),
                    mailData.quantity(),
                    formatCredit(mailData.amount()),
                    formatCredit(mailData.balanceAfter()),
                    formatTime(mailData.paymentCompletedAt())
            );
            case CANCELLATION -> PaymentReceiptEmailTemplate.CANCELLATION_BODY_FORMAT.formatted(
                    mailData.orderId(),
                    mailData.productName(),
                    mailData.quantity(),
                    formatCredit(mailData.amount()),
                    formatCredit(mailData.balanceAfter()),
                    formatTime(mailData.paymentCompletedAt()),
                    formatTime(mailData.canceledAt())
            );
        };

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(mailData.recipientEmail());
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    private String formatCredit(long amount) {
        return String.format(Locale.KOREA, "%,d", amount);
    }

    private String formatTime(LocalDateTime time) {
        return time.format(RECEIPT_TIME_FORMAT);
    }
}
