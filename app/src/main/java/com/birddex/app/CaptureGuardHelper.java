package com.birddex.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CaptureGuardHelper performs a lightweight on-device burst analysis so BirdDex can reduce
 * screen-photo cheating before points are considered on the backend.
 */
public final class CaptureGuardHelper {

    public static final String CAPTURE_SOURCE_CAMERA_BURST = "camera_burst";
    public static final String CAPTURE_SOURCE_EXTERNAL_CAMERA = "external_camera";
    public static final String CAPTURE_SOURCE_GALLERY_IMPORT = "gallery_import";
    public static final String CAPTURE_SOURCE_UNKNOWN = "unknown";

    public static final String EXTRA_CAPTURE_SOURCE = "captureSource";
    public static final String EXTRA_CAPTURE_GUARD_VERSION = "captureGuardVersion";
    public static final String EXTRA_CAPTURE_GUARD_SCORE = "captureGuardScore";
    public static final String EXTRA_CAPTURE_GUARD_SUSPICIOUS = "captureGuardSuspicious";
    public static final String EXTRA_CAPTURE_GUARD_FRAME_COUNT = "captureGuardFrameCount";
    public static final String EXTRA_CAPTURE_GUARD_BURST_SPAN_MS = "captureGuardBurstSpanMs";
    public static final String EXTRA_CAPTURE_GUARD_SELECTED_INDEX = "captureGuardSelectedIndex";
    public static final String EXTRA_CAPTURE_GUARD_FRAME_SIMILARITY = "captureGuardFrameSimilarity";
    public static final String EXTRA_CAPTURE_GUARD_ALIASING = "captureGuardAliasing";
    public static final String EXTRA_CAPTURE_GUARD_SCREEN_ARTIFACT = "captureGuardScreenArtifact";
    public static final String EXTRA_CAPTURE_GUARD_BORDER = "captureGuardBorder";
    public static final String EXTRA_CAPTURE_GUARD_GLARE = "captureGuardGlare";
    public static final String EXTRA_CAPTURE_GUARD_SHARPNESS = "captureGuardSharpness";
    public static final String EXTRA_CAPTURE_GUARD_METADATA_SCORE = "captureGuardMetadataScore";
    public static final String EXTRA_CAPTURE_GUARD_METADATA_SUSPICIOUS = "captureGuardMetadataSuspicious";
    public static final String EXTRA_CAPTURE_GUARD_EDITED_SOFTWARE = "captureGuardEditedSoftwareTagPresent";
    public static final String EXTRA_CAPTURE_GUARD_MAKE_MODEL_MISSING = "captureGuardCameraMakeModelMissing";
    public static final String EXTRA_CAPTURE_GUARD_DATETIME_ORIGINAL_MISSING = "captureGuardDateTimeOriginalMissing";
    public static final String EXTRA_CAPTURE_GUARD_EXIF_LATITUDE = "captureGuardExifLatitude";
    public static final String EXTRA_CAPTURE_GUARD_EXIF_LONGITUDE = "captureGuardExifLongitude";
    public static final String EXTRA_CAPTURE_GUARD_EXIF_DATETIME = "captureGuardExifDateTime";
    public static final String EXTRA_CAPTURE_GUARD_REASONS = "captureGuardReasons";

    private static final String ANALYZER_VERSION = "capture_guard_v3";
    private static final int TARGET_ANALYSIS_SIZE = 256;

    private CaptureGuardHelper() { }

    public static final class GuardReport {
        @NonNull public String captureSource = CAPTURE_SOURCE_UNKNOWN;
        @NonNull public String analyzerVersion = ANALYZER_VERSION;
        public double suspicionScore;
        public boolean suspicious;
        public int burstFrameCount;
        public long burstSpanMs;
        public int selectedFrameIndex;
        public double frameSimilarity;
        public double aliasingScore;
        public double screenArtifactScore;
        public double borderScore;
        public double glareScore;
        public double selectedFrameSharpness;
        public double metadataScore;
        public boolean metadataSuspicious;
        public boolean editedSoftwareTagPresent;
        public boolean cameraMakeModelMissing;
        public boolean dateTimeOriginalMissing;
        @Nullable public Double exifLatitude;
        @Nullable public Double exifLongitude;
        @Nullable public String exifDateTime;
        @NonNull public ArrayList<String> reasons = new ArrayList<>();
    }

    private static final class MetadataSignals {
        double metadataScore;
        boolean metadataSuspicious;
        boolean editedSoftwareTagPresent;
        boolean cameraMakeModelMissing;
        boolean dateTimeOriginalMissing;
        @Nullable Double exifLatitude;
        @Nullable Double exifLongitude;
        @Nullable String exifDateTime;
        @NonNull ArrayList<String> reasons = new ArrayList<>();
    }

    @NonNull
    public static GuardReport buildFallbackReport(@NonNull String captureSource, int frameCount) {
        GuardReport report = new GuardReport();
        report.captureSource = captureSource;
        report.burstFrameCount = Math.max(0, frameCount);
        if (CAPTURE_SOURCE_CAMERA_BURST.equals(captureSource) && frameCount < 3) {
            report.suspicionScore = 1.0;
            report.suspicious = true;
            report.reasons.add("burst_incomplete");
        }
        return report;
    }

    @NonNull
    public static GuardReport analyzeBurst(@NonNull Context context,
                                           @NonNull List<Uri> frameUris,
                                           @Nullable List<Long> captureTimesMs) {
        GuardReport report = new GuardReport();
        report.captureSource = CAPTURE_SOURCE_CAMERA_BURST;
        report.burstFrameCount = frameUris.size();

        if (captureTimesMs != null && captureTimesMs.size() >= 2) {
            long first = captureTimesMs.get(0);
            long last = captureTimesMs.get(captureTimesMs.size() - 1);
            report.burstSpanMs = Math.max(0L, last - first);
        }

        if (frameUris.isEmpty()) {
            report.suspicionScore = 1.0;
            report.suspicious = true;
            report.reasons.add("burst_missing");
            return report;
        }

        ArrayList<int[]> grayscaleFrames = new ArrayList<>();
        ArrayList<Double> sharpnessValues = new ArrayList<>();
        ArrayList<Double> aliasingValues = new ArrayList<>();
        ArrayList<Double> screenArtifactValues = new ArrayList<>();
        ArrayList<Double> borderValues = new ArrayList<>();
        ArrayList<Double> glareValues = new ArrayList<>();

        int selectedIndex = 0;
        double bestSharpness = -1.0;

        for (int i = 0; i < frameUris.size(); i++) {
            Bitmap frame = decodeScaledBitmap(context, frameUris.get(i), TARGET_ANALYSIS_SIZE);
            if (frame == null) {
                continue;
            }

            int[] gray = bitmapToGrayscale(frame, TARGET_ANALYSIS_SIZE, TARGET_ANALYSIS_SIZE);
            grayscaleFrames.add(gray);

            double sharpness = computeSharpness(gray, TARGET_ANALYSIS_SIZE, TARGET_ANALYSIS_SIZE);
            double aliasing = computeAliasingScore(gray, TARGET_ANALYSIS_SIZE, TARGET_ANALYSIS_SIZE);
            double screenArtifact = computeScreenArtifactScore(frame, gray, TARGET_ANALYSIS_SIZE, TARGET_ANALYSIS_SIZE);
            double border = computeBorderScore(gray, TARGET_ANALYSIS_SIZE, TARGET_ANALYSIS_SIZE);
            double glare = computeGlareScore(gray);

            sharpnessValues.add(sharpness);
            aliasingValues.add(aliasing);
            screenArtifactValues.add(screenArtifact);
            borderValues.add(border);
            glareValues.add(glare);

            if (sharpness > bestSharpness) {
                bestSharpness = sharpness;
                selectedIndex = i;
            }

            frame.recycle();
        }

        if (grayscaleFrames.isEmpty()) {
            report.suspicionScore = 1.0;
            report.suspicious = true;
            report.reasons.add("burst_decode_failed");
            return report;
        }

        double similaritySum = 0.0;
        int similarityPairs = 0;
        for (int i = 1; i < grayscaleFrames.size(); i++) {
            similaritySum += computeFrameSimilarity(grayscaleFrames.get(i - 1), grayscaleFrames.get(i));
            similarityPairs++;
        }

        report.selectedFrameIndex = Math.max(0, Math.min(selectedIndex, frameUris.size() - 1));
        report.selectedFrameSharpness = bestSharpness > 0 ? bestSharpness : 0.0;
        report.frameSimilarity = similarityPairs > 0 ? (similaritySum / similarityPairs) : 0.0;
        report.aliasingScore = average(aliasingValues);
        report.screenArtifactScore = Math.max(average(screenArtifactValues), valueAt(screenArtifactValues, report.selectedFrameIndex));
        report.borderScore = average(borderValues);
        report.glareScore = average(glareValues);

        if (report.selectedFrameIndex >= 0 && report.selectedFrameIndex < frameUris.size()) {
            applyMetadataSignals(report, inspectMetadataSignals(context, frameUris.get(report.selectedFrameIndex)));
        }

        recomputeSuspicion(report);
        return report;
    }

    @NonNull
    public static GuardReport augmentWithMetadataIfNeeded(@NonNull Context context,
                                                          @NonNull Uri imageUri,
                                                          @Nullable GuardReport existingReport) {
        GuardReport report = existingReport != null
                ? existingReport
                : buildFallbackReport(CAPTURE_SOURCE_UNKNOWN, 0);

        if (!report.metadataSuspicious && report.metadataScore <= 0.0 && !report.editedSoftwareTagPresent) {
            applyMetadataSignals(report, inspectMetadataSignals(context, imageUri));
            recomputeSuspicion(report);
        }
        return report;
    }

    private static void applyMetadataSignals(@NonNull GuardReport report, @NonNull MetadataSignals metadata) {
        report.metadataScore = metadata.metadataScore;
        report.metadataSuspicious = metadata.metadataSuspicious;
        report.editedSoftwareTagPresent = metadata.editedSoftwareTagPresent;
        report.cameraMakeModelMissing = metadata.cameraMakeModelMissing;
        report.dateTimeOriginalMissing = metadata.dateTimeOriginalMissing;
        report.exifLatitude = metadata.exifLatitude;
        report.exifLongitude = metadata.exifLongitude;
        report.exifDateTime = metadata.exifDateTime;
        for (String reason : metadata.reasons) {
            if (!report.reasons.contains(reason)) {
                report.reasons.add(reason);
            }
        }
    }

    private static void recomputeSuspicion(@NonNull GuardReport report) {
        double staticFrameScore = clamp((report.frameSimilarity - 0.90) / 0.08);
        double normalizedAliasing = clamp(report.aliasingScore / 0.14);
        double normalizedScreenArtifact = clamp(report.screenArtifactScore / 0.18);
        double normalizedBorder = clamp(report.borderScore / 0.38);
        double normalizedGlare = clamp(report.glareScore / 0.08);
        double normalizedMetadata = clamp(report.metadataScore / 0.55);

        report.suspicionScore = clamp(
                (0.30 * staticFrameScore) +
                        (0.20 * normalizedAliasing) +
                        (0.24 * normalizedScreenArtifact) +
                        (0.10 * normalizedBorder) +
                        (0.04 * normalizedGlare) +
                        (0.12 * normalizedMetadata)
        );

        if (report.burstFrameCount < 3 && !report.reasons.contains("burst_incomplete")) report.reasons.add("burst_incomplete");
        if (report.frameSimilarity >= 0.95 && !report.reasons.contains("burst_frames_nearly_identical")) report.reasons.add("burst_frames_nearly_identical");
        if (report.aliasingScore >= 0.06 && !report.reasons.contains("screen_aliasing_pattern")) report.reasons.add("screen_aliasing_pattern");
        if (report.screenArtifactScore >= 0.11 && !report.reasons.contains("screen_pixel_grid_pattern")) report.reasons.add("screen_pixel_grid_pattern");
        if (report.borderScore >= 0.22 && !report.reasons.contains("dark_rectangular_border")) report.reasons.add("dark_rectangular_border");
        if (report.glareScore >= 0.04 && !report.reasons.contains("clipped_screen_glare")) report.reasons.add("clipped_screen_glare");

        boolean strongVisualSignal =
                (report.frameSimilarity >= 0.95
                        && (report.aliasingScore >= 0.06
                        || report.borderScore >= 0.22
                        || report.screenArtifactScore >= 0.11))
                        || (report.screenArtifactScore >= 0.15
                        && (report.aliasingScore >= 0.05
                        || report.frameSimilarity >= 0.93
                        || report.borderScore >= 0.18));
        boolean metadataSupportSignal = report.editedSoftwareTagPresent
                || (report.metadataScore >= 0.45
                && (report.aliasingScore >= 0.05
                || report.borderScore >= 0.18
                || report.frameSimilarity >= 0.93
                || report.screenArtifactScore >= 0.10));

        report.suspicious = report.suspicionScore >= 0.58 || strongVisualSignal || metadataSupportSignal;
    }

    public static void putGuardExtras(@NonNull Intent intent, @Nullable GuardReport report) {
        if (report == null) return;
        intent.putExtra(EXTRA_CAPTURE_SOURCE, report.captureSource);
        intent.putExtra(EXTRA_CAPTURE_GUARD_VERSION, report.analyzerVersion);
        intent.putExtra(EXTRA_CAPTURE_GUARD_SCORE, report.suspicionScore);
        intent.putExtra(EXTRA_CAPTURE_GUARD_SUSPICIOUS, report.suspicious);
        intent.putExtra(EXTRA_CAPTURE_GUARD_FRAME_COUNT, report.burstFrameCount);
        intent.putExtra(EXTRA_CAPTURE_GUARD_BURST_SPAN_MS, report.burstSpanMs);
        intent.putExtra(EXTRA_CAPTURE_GUARD_SELECTED_INDEX, report.selectedFrameIndex);
        intent.putExtra(EXTRA_CAPTURE_GUARD_FRAME_SIMILARITY, report.frameSimilarity);
        intent.putExtra(EXTRA_CAPTURE_GUARD_ALIASING, report.aliasingScore);
        intent.putExtra(EXTRA_CAPTURE_GUARD_SCREEN_ARTIFACT, report.screenArtifactScore);
        intent.putExtra(EXTRA_CAPTURE_GUARD_BORDER, report.borderScore);
        intent.putExtra(EXTRA_CAPTURE_GUARD_GLARE, report.glareScore);
        intent.putExtra(EXTRA_CAPTURE_GUARD_SHARPNESS, report.selectedFrameSharpness);
        intent.putExtra(EXTRA_CAPTURE_GUARD_METADATA_SCORE, report.metadataScore);
        intent.putExtra(EXTRA_CAPTURE_GUARD_METADATA_SUSPICIOUS, report.metadataSuspicious);
        intent.putExtra(EXTRA_CAPTURE_GUARD_EDITED_SOFTWARE, report.editedSoftwareTagPresent);
        intent.putExtra(EXTRA_CAPTURE_GUARD_MAKE_MODEL_MISSING, report.cameraMakeModelMissing);
        intent.putExtra(EXTRA_CAPTURE_GUARD_DATETIME_ORIGINAL_MISSING, report.dateTimeOriginalMissing);
        if (report.exifLatitude != null) intent.putExtra(EXTRA_CAPTURE_GUARD_EXIF_LATITUDE, report.exifLatitude);
        if (report.exifLongitude != null) intent.putExtra(EXTRA_CAPTURE_GUARD_EXIF_LONGITUDE, report.exifLongitude);
        if (report.exifDateTime != null) intent.putExtra(EXTRA_CAPTURE_GUARD_EXIF_DATETIME, report.exifDateTime);
        intent.putStringArrayListExtra(EXTRA_CAPTURE_GUARD_REASONS, report.reasons);
    }

    @NonNull
    public static GuardReport readReportFromIntent(@NonNull Intent intent, boolean awardPointsRequested) {
        GuardReport report = buildFallbackReport(inferCaptureSource(intent, awardPointsRequested), 0);

        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_VERSION)) {
            String version = intent.getStringExtra(EXTRA_CAPTURE_GUARD_VERSION);
            if (version != null && !version.trim().isEmpty()) {
                report.analyzerVersion = version.trim();
            }
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_SCORE)) {
            report.suspicionScore = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_SCORE, report.suspicionScore);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_SUSPICIOUS)) {
            report.suspicious = intent.getBooleanExtra(EXTRA_CAPTURE_GUARD_SUSPICIOUS, report.suspicious);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_FRAME_COUNT)) {
            report.burstFrameCount = intent.getIntExtra(EXTRA_CAPTURE_GUARD_FRAME_COUNT, report.burstFrameCount);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_BURST_SPAN_MS)) {
            report.burstSpanMs = intent.getLongExtra(EXTRA_CAPTURE_GUARD_BURST_SPAN_MS, report.burstSpanMs);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_SELECTED_INDEX)) {
            report.selectedFrameIndex = intent.getIntExtra(EXTRA_CAPTURE_GUARD_SELECTED_INDEX, report.selectedFrameIndex);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_FRAME_SIMILARITY)) {
            report.frameSimilarity = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_FRAME_SIMILARITY, report.frameSimilarity);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_ALIASING)) {
            report.aliasingScore = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_ALIASING, report.aliasingScore);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_SCREEN_ARTIFACT)) {
            report.screenArtifactScore = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_SCREEN_ARTIFACT, report.screenArtifactScore);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_BORDER)) {
            report.borderScore = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_BORDER, report.borderScore);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_GLARE)) {
            report.glareScore = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_GLARE, report.glareScore);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_SHARPNESS)) {
            report.selectedFrameSharpness = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_SHARPNESS, report.selectedFrameSharpness);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_METADATA_SCORE)) {
            report.metadataScore = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_METADATA_SCORE, report.metadataScore);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_METADATA_SUSPICIOUS)) {
            report.metadataSuspicious = intent.getBooleanExtra(EXTRA_CAPTURE_GUARD_METADATA_SUSPICIOUS, report.metadataSuspicious);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_EDITED_SOFTWARE)) {
            report.editedSoftwareTagPresent = intent.getBooleanExtra(EXTRA_CAPTURE_GUARD_EDITED_SOFTWARE, report.editedSoftwareTagPresent);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_MAKE_MODEL_MISSING)) {
            report.cameraMakeModelMissing = intent.getBooleanExtra(EXTRA_CAPTURE_GUARD_MAKE_MODEL_MISSING, report.cameraMakeModelMissing);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_DATETIME_ORIGINAL_MISSING)) {
            report.dateTimeOriginalMissing = intent.getBooleanExtra(EXTRA_CAPTURE_GUARD_DATETIME_ORIGINAL_MISSING, report.dateTimeOriginalMissing);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_EXIF_LATITUDE)) {
            report.exifLatitude = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_EXIF_LATITUDE, 0.0);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_EXIF_LONGITUDE)) {
            report.exifLongitude = intent.getDoubleExtra(EXTRA_CAPTURE_GUARD_EXIF_LONGITUDE, 0.0);
        }
        if (intent.hasExtra(EXTRA_CAPTURE_GUARD_EXIF_DATETIME)) {
            report.exifDateTime = intent.getStringExtra(EXTRA_CAPTURE_GUARD_EXIF_DATETIME);
        }

        ArrayList<String> reasons = intent.getStringArrayListExtra(EXTRA_CAPTURE_GUARD_REASONS);
        if (reasons != null) {
            report.reasons = new ArrayList<>(reasons);
        }

        if (CAPTURE_SOURCE_CAMERA_BURST.equals(report.captureSource)
                && !intent.hasExtra(EXTRA_CAPTURE_GUARD_SCORE)
                && awardPointsRequested) {
            report.suspicionScore = 1.0;
            report.suspicious = true;
            if (!report.reasons.contains("burst_guard_missing")) {
                report.reasons.add("burst_guard_missing");
            }
        }

        return report;
    }

    @NonNull
    public static String inferCaptureSource(@NonNull Intent intent, boolean awardPointsRequested) {
        String explicitSource = intent.getStringExtra(EXTRA_CAPTURE_SOURCE);
        if (explicitSource != null && !explicitSource.trim().isEmpty()) {
            return explicitSource.trim();
        }
        if (!awardPointsRequested) {
            return CAPTURE_SOURCE_GALLERY_IMPORT;
        }
        return CAPTURE_SOURCE_UNKNOWN;
    }

    @NonNull
    private static MetadataSignals inspectMetadataSignals(@NonNull Context context, @NonNull Uri uri) {
        MetadataSignals signals = new MetadataSignals();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return signals;
            }
            ExifInterface exif = new ExifInterface(in);
            String software = safeLower(exif.getAttribute(ExifInterface.TAG_SOFTWARE));
            String make = trimToNull(exif.getAttribute(ExifInterface.TAG_MAKE));
            String model = trimToNull(exif.getAttribute(ExifInterface.TAG_MODEL));
            String dateTimeOriginal = trimToNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));

            signals.cameraMakeModelMissing = make == null && model == null;
            signals.dateTimeOriginalMissing = dateTimeOriginal == null;
            signals.exifDateTime = dateTimeOriginal;

            float[] latLong = new float[2];
            if (exif.getLatLong(latLong)) {
                signals.exifLatitude = (double) latLong[0];
                signals.exifLongitude = (double) latLong[1];
            }

            if (software != null && containsSuspiciousSoftwareTag(software)) {
                signals.editedSoftwareTagPresent = true;
                signals.metadataScore += 0.75;
                signals.reasons.add("edited_metadata_software_tag");
            }

            if (signals.cameraMakeModelMissing) {
                signals.metadataScore += 0.08;
                signals.reasons.add("camera_make_model_missing");
            }
            if (signals.dateTimeOriginalMissing) {
                signals.metadataScore += 0.06;
                signals.reasons.add("datetime_original_missing");
            }

            signals.metadataScore = clamp(signals.metadataScore);
            signals.metadataSuspicious = signals.editedSoftwareTagPresent || signals.metadataScore >= 0.45;
        } catch (Exception ignored) {
        }
        return signals;
    }

    private static boolean containsSuspiciousSoftwareTag(@NonNull String software) {
        return software.contains("photoshop")
                || software.contains("lightroom")
                || software.contains("snapseed")
                || software.contains("picsart")
                || software.contains("canva")
                || software.contains("facetune")
                || software.contains("remini")
                || software.contains("instagram")
                || software.contains("editor")
                || software.contains("image editor")
                || software.contains("photo editor")
                || software.contains("adobe")
                || software.contains("prequel");
    }

    @Nullable
    private static String safeLower(@Nullable String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.US);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private static Bitmap decodeScaledBitmap(@NonNull Context context, @NonNull Uri uri, int targetPx) {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;

        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException e) {
            return null;
        }

        int sampleSize = 1;
        int maxDimension = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxDimension / sampleSize > targetPx * 2) {
            sampleSize *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sampleSize);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return null;
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);
            if (bitmap == null) return null;
            if (bitmap.getWidth() == targetPx && bitmap.getHeight() == targetPx) {
                return bitmap;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetPx, targetPx, true);
            if (scaled != bitmap) {
                bitmap.recycle();
            }
            return scaled;
        } catch (IOException e) {
            return null;
        }
    }

    @NonNull
    private static int[] bitmapToGrayscale(@NonNull Bitmap bitmap, int width, int height) {
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] gray = new int[pixels.length];

        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            gray[i] = (int) ((0.299 * r) + (0.587 * g) + (0.114 * b));
        }

        return gray;
    }

    private static double computeFrameSimilarity(@NonNull int[] first, @NonNull int[] second) {
        int len = Math.min(first.length, second.length);
        if (len == 0) return 0.0;
        long diffSum = 0L;
        for (int i = 0; i < len; i++) {
            diffSum += Math.abs(first[i] - second[i]);
        }
        double meanDiff = (double) diffSum / len;
        return clamp(1.0 - (meanDiff / 255.0));
    }

    private static double computeSharpness(@NonNull int[] gray, int width, int height) {
        if (width < 3 || height < 3) return 0.0;
        double sum = 0.0;
        double sumSq = 0.0;
        int count = 0;

        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x;
                double lap = (4.0 * gray[idx])
                        - gray[idx - 1]
                        - gray[idx + 1]
                        - gray[idx - width]
                        - gray[idx + width];
                sum += lap;
                sumSq += lap * lap;
                count++;
            }
        }

        if (count == 0) return 0.0;
        double mean = sum / count;
        return Math.max(0.0, (sumSq / count) - (mean * mean));
    }

    private static double computeAliasingScore(@NonNull int[] gray, int width, int height) {
        if (width < 4 || height < 4) return 0.0;
        long alternations = 0L;
        long opportunities = 0L;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int d1 = gray[row + x] - gray[row + x - 1];
                int d2 = gray[row + x + 1] - gray[row + x];
                if (Math.abs(d1) >= 8 && Math.abs(d2) >= 8) {
                    opportunities++;
                    if ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) {
                        alternations++;
                    }
                }
            }
        }

        for (int y = 1; y < height - 1; y++) {
            for (int x = 0; x < width; x++) {
                int d1 = gray[(y * width) + x] - gray[((y - 1) * width) + x];
                int d2 = gray[((y + 1) * width) + x] - gray[(y * width) + x];
                if (Math.abs(d1) >= 8 && Math.abs(d2) >= 8) {
                    opportunities++;
                    if ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) {
                        alternations++;
                    }
                }
            }
        }

        if (opportunities == 0L) return 0.0;
        return clamp((double) alternations / opportunities);
    }

    private static double computeScreenArtifactScore(@NonNull Bitmap bitmap,
                                                     @NonNull int[] gray,
                                                     int width,
                                                     int height) {
        if (width < 4 || height < 4) return 0.0;
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        double colorFringing = computeColorFringingScore(pixels, gray, width, height);
        double pixelGrid = computePixelGridScore(gray, width, height);
        return clamp((0.65 * colorFringing) + (0.35 * pixelGrid));
    }

    private static double computeColorFringingScore(@NonNull int[] pixels,
                                                    @NonNull int[] gray,
                                                    int width,
                                                    int height) {
        long hits = 0L;
        long opportunities = 0L;

        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x;

                if (isFringingEdge(pixels[idx - 1], pixels[idx + 1], Math.abs(gray[idx + 1] - gray[idx - 1]))) {
                    hits++;
                }
                if (Math.abs(gray[idx + 1] - gray[idx - 1]) >= 24) {
                    opportunities++;
                }

                if (isFringingEdge(pixels[idx - width], pixels[idx + width], Math.abs(gray[idx + width] - gray[idx - width]))) {
                    hits++;
                }
                if (Math.abs(gray[idx + width] - gray[idx - width]) >= 24) {
                    opportunities++;
                }
            }
        }

        if (opportunities == 0L) return 0.0;
        return clamp((double) hits / opportunities);
    }

    private static boolean isFringingEdge(int firstColor, int secondColor, int luminanceDiff) {
        if (luminanceDiff < 24) return false;

        int dr = Math.abs(((secondColor >> 16) & 0xFF) - ((firstColor >> 16) & 0xFF));
        int dg = Math.abs(((secondColor >> 8) & 0xFF) - ((firstColor >> 8) & 0xFF));
        int db = Math.abs((secondColor & 0xFF) - (firstColor & 0xFF));

        int maxDelta = Math.max(dr, Math.max(dg, db));
        int minDelta = Math.min(dr, Math.min(dg, db));
        int midDelta = dr + dg + db - maxDelta - minDelta;

        return maxDelta >= 30 && (maxDelta - minDelta) >= 18 && (maxDelta - midDelta) >= 10;
    }

    private static double computePixelGridScore(@NonNull int[] gray, int width, int height) {
        long hits = 0L;
        long opportunities = 0L;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width - 2; x++) {
                int a = gray[row + x];
                int b = gray[row + x + 1];
                int c = gray[row + x + 2];
                int ab = Math.abs(a - b);
                int bc = Math.abs(b - c);
                int ac = Math.abs(a - c);
                int edge = Math.max(ab, bc);
                if (edge >= 12) {
                    opportunities++;
                    if (ac + 6 < ((ab + bc) / 2.0)) {
                        hits++;
                    }
                }
            }
        }

        for (int y = 0; y < height - 2; y++) {
            for (int x = 0; x < width; x++) {
                int a = gray[(y * width) + x];
                int b = gray[((y + 1) * width) + x];
                int c = gray[((y + 2) * width) + x];
                int ab = Math.abs(a - b);
                int bc = Math.abs(b - c);
                int ac = Math.abs(a - c);
                int edge = Math.max(ab, bc);
                if (edge >= 12) {
                    opportunities++;
                    if (ac + 6 < ((ab + bc) / 2.0)) {
                        hits++;
                    }
                }
            }
        }

        if (opportunities == 0L) return 0.0;
        return clamp((double) hits / opportunities);
    }

    private static double computeBorderScore(@NonNull int[] gray, int width, int height) {
        int band = Math.max(6, Math.min(width, height) / 16);
        if (band * 2 >= width || band * 2 >= height) return 0.0;

        double outerSum = 0.0;
        double innerSum = 0.0;
        int outerCount = 0;
        int innerCount = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean outer = x < band || y < band || x >= width - band || y >= height - band;
                boolean innerBand = !outer && (x < band * 2 || y < band * 2 || x >= width - (band * 2) || y >= height - (band * 2));
                int value = gray[(y * width) + x];
                if (outer) {
                    outerSum += value;
                    outerCount++;
                } else if (innerBand) {
                    innerSum += value;
                    innerCount++;
                }
            }
        }

        if (outerCount == 0 || innerCount == 0) return 0.0;
        double outerMean = outerSum / outerCount;
        double innerMean = innerSum / innerCount;
        return clamp((innerMean - outerMean - 18.0) / 70.0);
    }

    private static double computeGlareScore(@NonNull int[] gray) {
        if (gray.length == 0) return 0.0;
        int clipped = 0;
        for (int value : gray) {
            if (value >= 245) clipped++;
        }
        return clamp((double) clipped / gray.length);
    }

    private static double valueAt(@NonNull List<Double> values, int index) {
        if (index < 0 || index >= values.size()) return 0.0;
        Double value = values.get(index);
        return value != null ? value : 0.0;
    }

    private static double average(@NonNull List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double sum = 0.0;
        for (Double value : values) {
            sum += value != null ? value : 0.0;
        }
        return sum / values.size();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    @NonNull
    public static String summarizeForLog(@Nullable GuardReport report) {
        if (report == null) return "capture_guard_missing";
        return String.format(
                Locale.US,
                "%s score=%.3f suspicious=%s frames=%d similarity=%.3f alias=%.3f screen=%.3f border=%.3f glare=%.3f metadata=%.3f edited=%s",
                report.captureSource,
                report.suspicionScore,
                report.suspicious,
                report.burstFrameCount,
                report.frameSimilarity,
                report.aliasingScore,
                report.screenArtifactScore,
                report.borderScore,
                report.glareScore,
                report.metadataScore,
                report.editedSoftwareTagPresent
        );
    }
}
