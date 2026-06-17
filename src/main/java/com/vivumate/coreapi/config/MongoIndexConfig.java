package com.vivumate.coreapi.config;

import com.mongodb.client.model.IndexOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void initIndicesAfterStartup() {
        log.info("Starting to initialize MongoDB Indexes...");

        // 1. Indexes for CONVERSATIONS Collection
        IndexOperations conversationOps = mongoTemplate.indexOps("conversations");

        conversationOps.createIndex(new Index()
                .on("participant_ids", Sort.Direction.ASC)
                .on("last_activity_at", Sort.Direction.DESC)
                .on("_id", Sort.Direction.DESC)
                .named("idx_user_conversations_latest"));

//        conversationOps.createIndex(new Index()
//                .on("type", Sort.Direction.ASC)
//                .on("last_activity_at", Sort.Direction.DESC)
//                .named("idx_type_activity"));

        conversationOps.createIndex(new Index()
                .on("dm_hash", Sort.Direction.ASC)
                .unique()
                .sparse() // Sparse: NO index for records has dm_hash is null (Group chat)
                .named("idx_dm_hash_unique"));

        // 2. Indexes for MESSAGES Collection
        IndexOperations messageOps = mongoTemplate.indexOps("messages");

        messageOps.createIndex(new Index()
                .on("conversation_id", Sort.Direction.ASC)
                .on("_id", Sort.Direction.DESC)
                .named("idx_conversation_messages_cursor"));

        messageOps.createIndex(new Index()
                .on("sender.user_id", Sort.Direction.ASC)
                .on("conversation_id", Sort.Direction.ASC)
                .on("_id", Sort.Direction.DESC)
                .named("idx_sender_messages"));

        // 2.1 Full-text Search Index on field content.text of a conversation
        Document compoundTextIndex = new Document("conversation_id", 1)
                .append("content.text", "text");

        IndexOptions textIndexOptions = new IndexOptions()
                .name("idx_conversation_text_search")
                .defaultLanguage("none");
        mongoTemplate.getCollection("messages").createIndex(compoundTextIndex, textIndexOptions);

        log.info("MongoDB Indexes initialized successfully.");
    }
}