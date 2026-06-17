package com.vivumate.coreapi.websocket.service;

import com.vivumate.coreapi.websocket.dto.MessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for broadcasting chat events (edits, recalls) to
 * conversation participants via WebSocket.
 * <p>
 * Used by REST controllers to push real-time notifications after mutations.
 * This bridges the REST → WebSocket boundary: REST handles the mutation,
 * this service pushes the notification.
 * <p>
 * <b>Destination:</b> {@code /user/queue/events} — separate from
 * {@code /user/queue/messages} to give clients clear semantic separation:
 * <ul>
 *   <li>{@code /queue/messages} = new messages to render</li>
 *   <li>{@code /queue/events} = mutations on existing messages (edit/recall)</li>
 *   <li>{@code /queue/ack} = send confirmations</li>
 *   <li>{@code /queue/errors} = error notifications</li>
 * </ul>
 *
 * <b>Scalability note:</b> With SimpleBroker (current setup), this is
 * synchronous and ~microseconds per call. When migrating to an external
 * broker (RabbitMQ/Redis Pub/Sub), this method naturally scales to
 * multi-server deployments without code changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "CHAT_EVENT_BROADCASTER")
public class ChatEventBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    private static final String QUEUE_EVENTS = "/queue/events";

    /**
     * Broadcasts a {@link MessageEvent} to all specified participants
     * via {@code /user/queue/events}.
     * <p>
     * Each participant receives the event on all their connected devices
     * (multi-device delivery via Spring's user destination resolution).
     *
     * @param event          the event to broadcast
     * @param participantIds all conversation participant IDs to notify
     */
    public void broadcastEvent(MessageEvent event, List<Long> participantIds) {
        for (Long participantId : participantIds) {
            messagingTemplate.convertAndSendToUser(
                    participantId.toString(), QUEUE_EVENTS, event
            );
        }

        log.debug("Event broadcast: type={}, messageId={}, conversationId={}, recipients={}",
                event.getType(), event.getMessageId(), event.getConversationId(), participantIds.size());
    }
}
