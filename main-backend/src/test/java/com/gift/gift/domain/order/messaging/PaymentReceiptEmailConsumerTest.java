package com.gift.gift.domain.order.messaging;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailSendException;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import com.rabbitmq.client.Channel;
import com.gift.gift.domain.notification.event.handler.PaymentReceiptEmailEventHandler;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryType;
import com.gift.gift.domain.order.repository.PaymentReceiptMailData;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService.Action;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService.Claim;
import com.gift.gift.global.messaging.MessageEnvelope;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReceiptEmailConsumerTest {

    private static final long SOURCE_ID = 101L;
    private static final long DELIVERY_TAG = 17L;
    private static final String EVENT_ID = "payment-receipt-event-101";
    private static final String REPLAY_TOKEN = "46d9b7b9-9574-43f5-8f05-69f7f7417333";
    private static final LocalDateTime PAYMENT_COMPLETED_AT = LocalDateTime.of(2026, 11, 7, 12, 30, 45);
    private static final LocalDateTime CANCELED_AT = LocalDateTime.of(2026, 11, 8, 10, 45, 0);

    private static final PaymentReceiptMailData PAYMENT_MAIL_DATA = new PaymentReceiptMailData(
            "payer@example.com",
            "order-101",
            "테스트 선물",
            2,
            40_000,
            60_000,
            PAYMENT_COMPLETED_AT,
            null
    );

    private static final PaymentReceiptMailData CANCELLATION_MAIL_DATA = new PaymentReceiptMailData(
            "payer@example.com",
            "order-101",
            "테스트 선물",
            2,
            40_000,
            100_000,
            PAYMENT_COMPLETED_AT,
            CANCELED_AT
    );

    @Mock
    private PaymentReceiptDeliveryTransactionService transactionService;

    @Mock
    private PaymentReceiptEmailEventHandler emailHandler;

    @Mock
    private Channel channel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PaymentReceiptEmailConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentReceiptEmailConsumer(
                objectMapper,
                transactionService,
                emailHandler
        );
    }

    @Test
    @DisplayName("결제 이벤트는 메일과 SENT 기록이 끝난 뒤 ACK한다")
    void onMessage_paymentEvent_sendsThenRecordsThenAcknowledges() throws Exception {
        Claim claim = sendClaim(
                PaymentReceiptDeliveryType.PAYMENT,
                SOURCE_ID,
                PAYMENT_MAIL_DATA,
                false
        );
        when(transactionService.claim(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null))
                .thenReturn(claim);
        when(transactionService.markAccepted(claim)).thenReturn(Action.ACK);

        consumer.onMessage(message(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null, false), channel);

        InOrder order = inOrder(emailHandler, transactionService, channel);
        order.verify(transactionService).claim(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null);
        order.verify(emailHandler).sendFromQueue(PaymentReceiptDeliveryType.PAYMENT, PAYMENT_MAIL_DATA);
        order.verify(transactionService).markAccepted(claim);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("취소 이벤트는 결제 영수증 상태 조회 없이 독립 처리한다")
    void onMessage_cancellationEventProcessesItsOwnDelivery() throws Exception {
        Claim claim = sendClaim(
                PaymentReceiptDeliveryType.CANCELLATION,
                SOURCE_ID,
                CANCELLATION_MAIL_DATA,
                false
        );
        when(transactionService.claim(PaymentReceiptDeliveryType.CANCELLATION, SOURCE_ID, null)).thenReturn(claim);
        when(transactionService.markAccepted(claim)).thenReturn(Action.ACK);

        consumer.onMessage(
                message(PaymentReceiptDeliveryType.CANCELLATION, SOURCE_ID, null, false),
                channel
        );

        verify(emailHandler).sendFromQueue(PaymentReceiptDeliveryType.CANCELLATION, CANCELLATION_MAIL_DATA);
        verify(transactionService).markAccepted(claim);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("이미 발송되었거나 발송 제외된 건은 SMTP를 호출하지 않고 ACK한다")
    void onMessage_alreadyCompletedDeliveryAcknowledgesWithoutSending() throws Exception {
        Claim claim = new Claim(
                Action.ACK,
                PaymentReceiptDeliveryType.PAYMENT,
                SOURCE_ID,
                null,
                null,
                false,
                0
        );
        when(transactionService.claim(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null))
                .thenReturn(claim);

        consumer.onMessage(message(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null, false), channel);

        verifyNoInteractions(emailHandler);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("payload와 aggregate ID가 다르면 재전달 없이 DLQ로 보낸다")
    void onMessage_mismatchedBusinessIdRejectsWithoutRequeue() throws Exception {
        consumer.onMessage(
                message(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, "999", false),
                channel
        );

        verifyNoInteractions(transactionService, emailHandler);
        verify(channel).basicReject(DELIVERY_TAG, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("SMTP 4xx 오류는 실패 이력을 저장한 뒤 재시도를 요청한다")
    void onMessage_smtpTemporaryRejectionRecordsFailureAndRetries() throws Exception {
        Claim claim = sendClaim(
                PaymentReceiptDeliveryType.PAYMENT,
                SOURCE_ID,
                PAYMENT_MAIL_DATA,
                false
        );
        when(transactionService.claim(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null))
                .thenReturn(claim);
        doThrow(smtpFailure(450))
                .when(emailHandler)
                .sendFromQueue(PaymentReceiptDeliveryType.PAYMENT, PAYMENT_MAIL_DATA);
        when(transactionService.recordFailure(claim, "SMTP_TEMPORARY_REJECTION", false))
                .thenReturn(Action.RETRY);

        consumer.onMessage(message(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null, false), channel);

        verify(transactionService).recordFailure(claim, "SMTP_TEMPORARY_REJECTION", false);
        verify(channel).basicReject(DELIVERY_TAG, true);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("SMTP 5xx 오류는 영구 실패로 저장한 뒤 DLQ로 보낸다")
    void onMessage_smtpPermanentRejectionRecordsFailureAndDeadLetters() throws Exception {
        Claim claim = sendClaim(
                PaymentReceiptDeliveryType.PAYMENT,
                SOURCE_ID,
                PAYMENT_MAIL_DATA,
                false
        );
        when(transactionService.claim(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null))
                .thenReturn(claim);
        doThrow(smtpFailure(550))
                .when(emailHandler)
                .sendFromQueue(PaymentReceiptDeliveryType.PAYMENT, PAYMENT_MAIL_DATA);
        when(transactionService.recordFailure(claim, "SMTP_PERMANENT_REJECTION", true))
                .thenReturn(Action.DEAD_LETTER);

        consumer.onMessage(message(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null, false), channel);

        verify(transactionService).recordFailure(claim, "SMTP_PERMANENT_REJECTION", true);
        verify(channel).basicReject(DELIVERY_TAG, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("SMTP 수락 여부가 불명확한 오류는 제한적으로 재시도한다")
    void onMessage_unknownSmtpResultRecordsFailureAndRetries() throws Exception {
        Claim claim = sendClaim(
                PaymentReceiptDeliveryType.PAYMENT,
                SOURCE_ID,
                PAYMENT_MAIL_DATA,
                false
        );
        when(transactionService.claim(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null))
                .thenReturn(claim);
        doThrow(new MailSendException("SMTP connection lost"))
                .when(emailHandler)
                .sendFromQueue(PaymentReceiptDeliveryType.PAYMENT, PAYMENT_MAIL_DATA);
        when(transactionService.recordFailure(claim, "SMTP_ACCEPTANCE_UNKNOWN", false))
                .thenReturn(Action.RETRY);

        consumer.onMessage(message(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null, false), channel);

        verify(transactionService).recordFailure(claim, "SMTP_ACCEPTANCE_UNKNOWN", false);
        verify(channel).basicReject(DELIVERY_TAG, true);
    }

    @Test
    @DisplayName("DB 오류는 RabbitMQ의 실패 전달·지연 재시도 경로로 보낸다")
    void onMessage_databaseFailureRejectsForRetry() throws Exception {
        when(transactionService.claim(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        consumer.onMessage(message(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null, false), channel);

        verifyNoInteractions(emailHandler);
        verify(channel).basicReject(DELIVERY_TAG, true);
    }

    @Test
    @DisplayName("수동 재처리는 큐 이름 라우팅과 UUID 토큰이 일치해야 처리한다")
    void onMessage_operatorReplayPassesOneTimeTokenToClaim() throws Exception {
        Claim claim = sendClaim(
                PaymentReceiptDeliveryType.PAYMENT,
                SOURCE_ID,
                PAYMENT_MAIL_DATA,
                true
        );
        when(transactionService.claim(
                PaymentReceiptDeliveryType.PAYMENT,
                SOURCE_ID,
                REPLAY_TOKEN
        )).thenReturn(claim);
        when(transactionService.markAccepted(claim)).thenReturn(Action.ACK);

        consumer.onMessage(
                message(PaymentReceiptDeliveryType.PAYMENT, SOURCE_ID, null, true),
                channel
        );

        verify(transactionService).claim(
                PaymentReceiptDeliveryType.PAYMENT,
                SOURCE_ID,
                REPLAY_TOKEN
        );
        verify(emailHandler).sendFromQueue(
                PaymentReceiptDeliveryType.PAYMENT,
                PAYMENT_MAIL_DATA
        );
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    private Claim sendClaim(
            PaymentReceiptDeliveryType receiptType,
            long sourceId,
            PaymentReceiptMailData mailData,
            boolean manualReplay
    ) {
        return new Claim(
                Action.SEND,
                receiptType,
                sourceId,
                "claim-token",
                mailData,
                manualReplay,
                manualReplay ? 0 : 1
        );
    }

    private MailSendException smtpFailure(int replyCode) throws Exception {
        Exception smtpException = new SMTPAddressFailedException(
                new InternetAddress("payer@example.com"),
                "RCPT TO",
                replyCode,
                "SMTP rejected recipient"
        );

        return new MailSendException(
                "SMTP rejected recipient",
                smtpException,
                Map.<Object, Exception>of("message", smtpException)
        );
    }

    private Message message(
            PaymentReceiptDeliveryType receiptType,
            long sourceId,
            String aggregateId,
            boolean operatorReplay
    ) throws Exception {
        String eventType = receiptType == PaymentReceiptDeliveryType.PAYMENT
                ? "payment.order.completed.v1"
                : "payment.order.canceled.v1";
        String aggregateType = receiptType == PaymentReceiptDeliveryType.PAYMENT
                ? "ORDER"
                : "PAYMENT_CANCELLATION";
        String payloadField = receiptType == PaymentReceiptDeliveryType.PAYMENT
                ? "orderRecordId"
                : "cancellationId";
        String routingKey = receiptType == PaymentReceiptDeliveryType.PAYMENT
                ? PaymentRabbitTopologyConfiguration.ORDER_COMPLETED_ROUTING_KEY
                : PaymentRabbitTopologyConfiguration.ORDER_CANCELED_ROUTING_KEY;
        String sourceIdText = Long.toString(sourceId);

        var payload = objectMapper.createObjectNode().put(payloadField, sourceId);
        MessageEnvelope envelope = new MessageEnvelope(
                EVENT_ID,
                eventType,
                1,
                sourceIdText,
                aggregateType,
                aggregateId == null ? sourceIdText : aggregateId,
                null,
                payload,
                PAYMENT_COMPLETED_AT,
                null
        );

        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        properties.setMessageId(EVENT_ID);
        properties.setType(eventType);
        properties.setReceivedExchange(operatorReplay
                ? ""
                : PaymentRabbitTopologyConfiguration.EVENTS_EXCHANGE);
        properties.setReceivedRoutingKey(operatorReplay
                ? PaymentRabbitTopologyConfiguration.RECEIPT_QUEUE
                : routingKey);
        if (operatorReplay) {
            properties.setHeader(
                    PaymentReceiptEmailConsumer.OPERATOR_REPLAY_HEADER,
                    REPLAY_TOKEN
            );
        }

        return new Message(objectMapper.writeValueAsBytes(envelope), properties);
    }
}
