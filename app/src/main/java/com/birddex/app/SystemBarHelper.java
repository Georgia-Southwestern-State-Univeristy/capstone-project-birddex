package com.birddex.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Central place for drawing behind the status bar and sizing the {@link R.color#nav_brown} top strip.
 * Invoked for every BirdDex activity from {@link BirdDexAppCheck} after the content view is ready.
 */
public final class SystemBarHelper {

    private static final String TAG_STATUS_INSET_WRAPPER = "birdDexStatusInsetWrapper";

    private SystemBarHelper() {
        // No instances.
    }

    /**
     * Edge-to-edge system bars: transparent status bar, themed navigation bar, and a
     * {@link R.color#nav_brown} strip at the top whose height tracks
     * status bar and display cutout. Bottom inset is applied as padding on the wrapper on most
     * screens; {@link HomeActivity} skips that and applies the inset on {@code bottomNavContainer}
     * instead to avoid a double gap above the system navigation bar.
     */
    public static void applyStandardNavBar(Activity activity) {
        if (activity == null) return;

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        applyNavigationBarColorsAndAppearance(activity);
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        // Home has its own bottom nav: wrapper bottom padding would stack and leave a brown gap
        // between the bar and the system navigation area.
        boolean padBottomForSystemNav = !(activity instanceof HomeActivity);
        wrapContentWithSystemBarInsets(activity, padBottomForSystemNav);
    }

    /**
     * Splash / launch: same navigation bar treatment as {@link #applyStandardNavBar(Activity)}
     * but keeps {@code decorFitsSystemWindows(true)} and does not reparent the content view.
     * Reparenting while {@link android.view.animation.Animation} is running can cancel callbacks
     * and block the handoff to {@link LoadingActivity}.
     */
    public static void applySplashWindowBars(Activity activity) {
        if (activity == null) return;

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), true);
        applyNavigationBarColorsAndAppearance(activity);
        activity.getWindow().setStatusBarColor(ContextCompat.getColor(activity, R.color.nav_brown));
    }

    private static void applyNavigationBarColorsAndAppearance(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());

        // Same brown as the top inset strip so the reserved nav area matches (3-button and gesture).
        int navBrown = ContextCompat.getColor(activity, R.color.nav_brown);
        activity.getWindow().setNavigationBarColor(navBrown);

        if (controller != null) {
            controller.setAppearanceLightNavigationBars(false);
            controller.setAppearanceLightStatusBars(false);
        }
    }

    private static void wrapContentWithSystemBarInsets(Activity activity, boolean applyBottomSystemBarPadding) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;

        View first = content.getChildAt(0);
        if (TAG_STATUS_INSET_WRAPPER.equals(first.getTag())) {
            return;
        }

        View original = first;
        content.removeViewAt(0);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        wrapper.setTag(TAG_STATUS_INSET_WRAPPER);
        // When padding reserves space above the system nav, tint that band brown; Home omits padding.
        wrapper.setBackgroundColor(ContextCompat.getColor(activity,
                applyBottomSystemBarPadding ? R.color.nav_brown : R.color.page_cream));

        View insetBar = new View(activity);
        insetBar.setBackgroundColor(ContextCompat.getColor(activity, R.color.nav_brown));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        insetBar.setLayoutParams(barLp);

        LinearLayout.LayoutParams origLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        original.setLayoutParams(origLp);

        wrapper.addView(insetBar);
        wrapper.addView(original);
        content.addView(wrapper);

        ViewCompat.setOnApplyWindowInsetsListener(wrapper, (v, insets) -> {
            Insets statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            int topInset = Math.max(statusBars.top, cutout.top);
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            ViewGroup.LayoutParams lp = insetBar.getLayoutParams();
            if (lp.height != topInset) {
                lp.height = topInset;
                insetBar.setLayoutParams(lp);
            }
            int bottomPad = applyBottomSystemBarPadding ? nav.bottom : 0;
            if (v.getPaddingBottom() != bottomPad) {
                v.setPadding(0, 0, 0, bottomPad);
            }
            return insets;
        });
        // Defer so we do not re-enter layout/inset dispatch during the initial attach sequence.
        wrapper.post(() -> {
            ViewCompat.requestApplyInsets(wrapper);
            View decor = activity.getWindow().getDecorView();
            if (decor != null) {
                ViewCompat.requestApplyInsets(decor);
            }
        });
    }
}
