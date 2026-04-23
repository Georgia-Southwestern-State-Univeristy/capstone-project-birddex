package com.birddex.app;

/**
 * Simple model used by the global leaderboard screen.
 */
public class LeaderboardEntry {
    private final String userId;
    private final String username;
    private final long totalPoints;
    private final String profilePictureUrl;
    private final int rank;

    public LeaderboardEntry(String userId, String username, long totalPoints, String profilePictureUrl, int rank) {
        this.userId = userId;
        this.username = username;
        this.totalPoints = totalPoints;
        this.profilePictureUrl = profilePictureUrl;
        this.rank = rank;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public long getTotalPoints() {
        return totalPoints;
    }

    public String getProfilePictureUrl() {
        return profilePictureUrl;
    }

    public int getRank() {
        return rank;
    }
}
