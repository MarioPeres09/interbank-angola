package com.interbank.notificacoes;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
class NotificationConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private final JsonMapper json;
    private final Path directory;

    NotificationConsumer(JsonMapper json, @Value("${interbank.notifications.directory}") String directory) {
        this.json = json;
        this.directory = Path.of(directory);
    }

    @RabbitListener(queuesToDeclare = @Queue(value = "interbank.notifications", durable = "true"))
    public void consume(Message message) throws IOException {
        var event = readEvent(message);
        var target = directory.resolve(event.sourceBank() + "-" + event.transferId() + ".json");
        try {
            if (store(target, message.getBody())) {
                log.info("Notificação de transferência concluída registada: {} {}", event.sourceBank(), event.transferId());
            }
        } catch (IOException exception) {
            // Keep the message unacknowledged briefly before the container requeues it.
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw exception;
        }
    }

    private Event readEvent(Message message) {
        Event event;
        try {
            event = json.readValue(message.getBody(), Event.class);
        } catch (RuntimeException exception) {
            throw new AmqpRejectAndDontRequeueException("Evento inválido");
        }
        if (event == null || !"transfer.completed".equals(event.type())
                || !("KWANZA".equals(event.sourceBank()) || "HORIZONTE".equals(event.sourceBank()))
                || event.transferId() == null || event.amount() == null || event.amount().signum() <= 0
                || !"AOA".equals(event.currency()) || event.completedAt() == null) {
            throw new AmqpRejectAndDontRequeueException("Evento de transferência inválido");
        }
        return event;
    }

    private boolean store(Path target, byte[] payload) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".notification-", ".part");
        try {
            Files.write(temp, payload);
            try (var file = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                file.force(true);
            }
            // Linking the complete file atomically makes duplicate deliveries safe.
            try {
                Files.createLink(target, temp);
                return true;
            } catch (FileAlreadyExistsException exception) {
                return false;
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    record Event(String type, String sourceBank, UUID transferId, BigDecimal amount, String currency,
                 OffsetDateTime completedAt) {}
}
