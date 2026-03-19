package com.birddex.app;

/**
 * UserSettings: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 */
public class UserSettings {
    public boolean notificationsEnabled = false;
    public boolean repliesEnabled = true; // Default true
    public int notificationCooldownHours = 2; // Default 2 hours. 0 = Off (immediate)
    
    // New fields for Tracked Birds
    public boolean trackedBirdsNotificationsEnabled = false;
    public int trackedBirdsCooldownHours = 0; // Default 0 = Every spotting

    // Distance setting
    public int trackedBirdsMaxDistanceMiles = -1; // -1 = Any distance, 150 = Within 150 miles

    public UserSettings() { }

    public UserSettings(boolean notificationsEnabled, boolean repliesEnabled, int notificationCooldownHours) {
        this.notificationsEnabled = notificationsEnabled;
        this.repliesEnabled = repliesEnabled;
        this.notificationCooldownHours = notificationCooldownHours;
    }

    public UserSettings(boolean notificationsEnabled, boolean repliesEnabled, int notificationCooldownHours,
                        boolean trackedBirdsNotificationsEnabled, int trackedBirdsCooldownHours) {
        this.notificationsEnabled = notificationsEnabled;
        this.repliesEnabled = repliesEnabled;
        this.notificationCooldownHours = notificationCooldownHours;
        this.trackedBirdsNotificationsEnabled = trackedBirdsNotificationsEnabled;
        this.trackedBirdsCooldownHours = trackedBirdsCooldownHours;
    }

    public UserSettings(boolean notificationsEnabled, boolean repliesEnabled, int notificationCooldownHours,
                        boolean trackedBirdsNotificationsEnabled, int trackedBirdsCooldownHours,
                        int trackedBirdsMaxDistanceMiles) {
        this.notificationsEnabled = notificationsEnabled;
        this.repliesEnabled = repliesEnabled;
        this.notificationCooldownHours = notificationCooldownHours;
        this.trackedBirdsNotificationsEnabled = trackedBirdsNotificationsEnabled;
        this.trackedBirdsCooldownHours = trackedBirdsCooldownHours;
        this.trackedBirdsMaxDistanceMiles = trackedBirdsMaxDistanceMiles;
    }
}
