package com.birddex.app;

public class UserSettings {
    public boolean notificationsEnabled = false;
    public boolean repliesEnabled = true; // Default true
    public int notificationCooldownHours = 2; // Default 2 hours. 0 = Off (immediate)

    public UserSettings() { }

    public UserSettings(boolean notificationsEnabled, boolean repliesEnabled, int notificationCooldownHours) {
        this.notificationsEnabled = notificationsEnabled;
        this.repliesEnabled = repliesEnabled;
        this.notificationCooldownHours = notificationCooldownHours;
    }
}
