package com.birddex.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public final class SystemBarHelper {

    private SystemBarHelper() {
        // No instances.
    }

    public static void applyStandardNavBar(Activity activity) {
        if (activity == null) return;

        boolean isThreeButtonNav = isThreeButtonNavigationMode(activity);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());

        // Keep the top status bar matching app chrome.
        activity.getWindow().setStatusBarColor(
                ContextCompat.getColor(activity, R.color.nav_brown)
        );

        if (isThreeButtonNav) {
            // 3-button mode = black bottom system bar
            activity.getWindow().setNavigationBarColor(Color.BLACK);

            if (controller != null) {
                // Black background needs light icons
                controller.setAppearanceLightNavigationBars(false);
                controller.setAppearanceLightStatusBars(false);
            }
        } else {
            // Gesture mode = brown bottom system bar
            activity.getWindow().setNavigationBarColor(
                    ContextCompat.getColor(activity, R.color.nav_brown)
            );

            if (controller != null) {
                controller.setAppearanceLightNavigationBars(false);
                controller.setAppearanceLightStatusBars(false);
            }
        }
    }

    private static boolean isThreeButtonNavigationMode(Activity activity) {
        int resId = activity.getResources().getIdentifier(
                "config_navBarInteractionMode",
                "integer",
                "android"
        );

        if (resId > 0) {
            int mode = activity.getResources().getInteger(resId);
            return mode == 0; // 0 = 3-button, 1 = 2-button, 2 = gesture
        }

        return false;
    }
}