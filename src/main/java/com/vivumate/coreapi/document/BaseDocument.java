package com.vivumate.coreapi.document;

import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * Base document class for all MongoDB documents.
 * Uses Instant (UTC) instead of LocalDateTime for consistent timezone handling.
 * Separated from JPA BaseEntity to avoid annotation conflicts.
 */
@Getter
@Setter
public abstract class BaseDocument {

    @Id
    private ObjectId id;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    private Instant deletedAt;
}
