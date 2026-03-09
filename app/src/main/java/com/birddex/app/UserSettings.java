package com.birddex.app;

/**
 * UserSettings: Support/helper/model class used by other BirdDex screens so logic can stay reusable and organized.
 *
 * These comments focus on what the actual code blocks are doing so the file is easier to trace
 * when you are debugging or presenting the app. Only comments were added; runtime logic was not changed.
 */
public class UserSettings {
    public boolean notificationsEnabled = false;
    public boolean repliesEnabled = true; // Default true
    public int notificationCooldownHours = 2; // Default 2 hours. 0 = Off (immediate)

    public UserSettings() { }

    /**
     * Constructor that stores incoming dependencies/values so this object starts in a usable
     * state.
     */
    public UserSettings(boolean notificationsEnabled, boolean repliesEnabled, int notificationCooldownHours) {
        this.notificationsEnabled = notificationsEnabled;
        this.repliesEnabled = repliesEnabled;
        this.notificationCooldownHours = notificationCooldownHours;
    }
}
