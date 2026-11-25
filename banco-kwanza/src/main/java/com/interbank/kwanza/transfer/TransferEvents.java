package com.interbank.kwanza.transfer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "interbank.events.enabled", havingValue = "true")
class TransferEvents {
    private static final String QUEUE = "interbank.notifications";
    private static final Logger log = LoggerFactory.getLogger(TransferEvents.class);
    private final JdbcClient db;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;

    TransferEvents(JdbcClient db, RabbitTemplate rabbit, TransactionTemplate transaction) {
        this.db = db;
        this.rabbit = rabbit;
        this.transaction = transaction;
    }

    @Bean
    Queue notificationsQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Scheduled(fixedDelay = 5000)
    void sendPending() {
        try {
            for (int sent = 0; sent < 20; sent++) {
                if (!Boolean.TRUE.equals(transaction.execute(status -> publishOne()))) {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Publicação pendente para nova tentativa ({})", exception.getClass().getSimpleName());
        }
    }

    private boolean publishOne() {
        var event = db.sql("""
                SELECT transfer_id, payload::text AS payload
                FROM transfer_outbox WHERE published_at IS NULL
                ORDER BY created_at, transfer_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query(OutboxEvent.class).optional();
        if (event.isEmpty()) {
            return false;
        }
        OutboxEvent item = event.get();
        var message = MessageBuilder.withBody(item.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId("KWANZA:" + item.transferId())
                .build();
        var confirm = new CorrelationData();
        rabbit.send("", QUEUE, message, confirm);
        try {
            if (!confirm.getFuture().get(3, TimeUnit.SECONDS).ack() || confirm.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ não encaminhou o evento");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação interrompida", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Publicação por confirmar", exception);
        }
        db.sql("UPDATE transfer_outbox SET published_at = now() WHERE transfer_id = :id")
                .param("id", item.transferId()).update();
        return true;
    }

    record OutboxEvent(UUID transferId, String payload) {}
}
