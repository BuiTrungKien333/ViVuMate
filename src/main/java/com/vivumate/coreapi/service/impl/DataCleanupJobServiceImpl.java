package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.repository.mongodb.ConversationRepository;
import com.vivumate.coreapi.repository.mongodb.MessageRepository;
import com.vivumate.coreapi.service.DataCleanupJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class DataCleanupJobServiceImpl implements DataCleanupJobService {

    private static final int MAXIMUM_CLEANING_DAY = 30;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    // (Runs at 3:00 AM every day - when the Server has the lowest load)
    @Scheduled(cron = "0 0 3 * * ?")
    @Override
    public void hardDeleteExpiredConversations() {
        log.info("Starting nightly cleanup job for expired conversations...");

        // Calculate the 30-day ago milestone
        Instant thirtyDaysAgo = Instant.now().minus(MAXIMUM_CLEANING_DAY, ChronoUnit.DAYS);

        // 1. Find the list of groups with deletedAt < thirtyDaysAgo
        // (Get the list of expired Groups (Containing only IDs inside))
        List<ConversationDocument> expiredDocs = conversationRepository.findExpiredConversations(thirtyDaysAgo);
        if (expiredDocs.isEmpty()) {
            log.info("No expired conversations found. Cleanup job finished early.");
            return;
        }

        // (Extract the ID array to serve the deletion tasks)
        List<ObjectId> expiredGroupIds = expiredDocs.stream()
                .map(ConversationDocument::getId)
                .toList();

        log.info("Found {} expired groups. Proceeding to hard delete...", expiredGroupIds.size());

        // 2. TODO: Call MessageRepository to delete ALL messages belonging to these groups (Crucial to free up disk space!)
        // messageRepository.deleteAllByConversationIdIn(expiredGroupIds);

        // 3. Call ConversationRepository to permanently delete those Groups
        conversationRepository.deleteAllByIdIn(expiredGroupIds);

        log.info("Nightly cleanup job completed successfully. Freed up storage for {} groups.", expiredGroupIds.size());
    }
}
