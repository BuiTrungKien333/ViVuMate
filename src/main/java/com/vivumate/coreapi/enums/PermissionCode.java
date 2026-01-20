package com.vivumate.coreapi.enums;

public enum PermissionCode {
    USER_READ,          // View user profiles
    USER_UPDATE,        // Update own profile
    USER_MANAGE,        // Ban/Unban users (Admin only)

    // --- POST/CONTENT MANAGEMENT ---
    POST_CREATE,        // Create a travel post
    POST_UPDATE,        // Edit own post
    POST_DELETE,        // Delete own post
    POST_MANAGE,        // Delete any post (Moderator/Admin)

    // --- LOCATION/TOUR MANAGEMENT ---
    LOCATION_VIEW,      // View locations/tours
    LOCATION_CREATE,    // Create new location (Guide/Admin)
    LOCATION_MANAGE
}
