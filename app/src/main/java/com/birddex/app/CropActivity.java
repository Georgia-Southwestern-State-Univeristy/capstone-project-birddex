package com.birddex.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;

import com.canhub.cropper.CropImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CropActivity provides an interface for the user to crop an image before identification.
 * It uses the 'Android-Image-Cropper' library to allow manual cropping to a 1:1 aspect ratio.
 */
public class CropActivity extends AppCompatActivity {

    private static final String TAG = "CropActivity";

    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_AWARD_POINTS = "awardPoints";

    private CropImageView cropImageView;
    private Button btnCancel;
    private Button btnIdentify;

    private final AtomicBoolean identifyClicked = new AtomicBoolean(false);

    private Uri originalInputUri;
    private Uri cropDisplayUri;

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean imageReady = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        cropImageView = findViewById(R.id.cropImageView);
        btnCancel = findViewById(R.id.btnCancel);
        btnIdentify = findViewById(R.id.btnIdentify);

        String uriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriStr == null) {
            finish();
            return;
        }

        originalInputUri = Uri.parse(uriStr);

        cropImageView.setFixedAspectRatio(true);
        cropImageView.setAspectRatio(1, 1);

        btnIdentify.setEnabled(false);

        btnCancel.setOnClickListener(v -> {
            deleteTempFileIfOwnedByApp(originalInputUri);
            if (cropDisplayUri != null && !cropDisplayUri.equals(originalInputUri)) {
                deleteTempFileIfOwnedByApp(cropDisplayUri);
            }
            cleanupBurstFrameCache();
            finish();
        });

        btnIdentify.setOnClickListener(v -> {
            if (!imageReady) return;
            if (!identifyClicked.compareAndSet(false, true)) return;

            Bitmap cropped = cropImageView.getCroppedImage(1600, 1600);
            if (cropped == null) {
                identifyClicked.set(false);
                return;
            }

            Uri croppedImageUri = saveBitmapToFile(cropped);

            if (croppedImageUri != null) {
                boolean awardPoints = getIntent().getBooleanExtra(EXTRA_AWARD_POINTS, true);
                CaptureGuardHelper.GuardReport guardReport =
                        CaptureGuardHelper.readReportFromIntent(getIntent(), awardPoints);

                guardReport = CaptureGuardHelper.augmentWithMetadataIfNeeded(this, originalInputUri, guardReport);

                Intent intent = new Intent(this, IdentifyingActivity.class);
                intent.putExtra("imageUri", croppedImageUri.toString());
                intent.putExtra("awardPoints", awardPoints);
                CaptureGuardHelper.putGuardExtras(intent, guardReport);
                intent.putExtra(CaptureGuardHelper.EXTRA_CAPTURE_SOURCE, guardReport.captureSource);

                deleteTempFileIfOwnedByApp(originalInputUri);
                if (cropDisplayUri != null && !cropDisplayUri.equals(originalInputUri)) {
                    deleteTempFileIfOwnedByApp(cropDisplayUri);
                }

                startActivity(intent);
                finish();
            } else {
                identifyClicked.set(false);
            }
        });

        loadImageForCropper();
    }

    @Override
    protected void onResume() {
        super.onResume();
        identifyClicked.set(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        imageExecutor.shutdownNow();
    }

    private void loadImageForCropper() {
        imageExecutor.execute(() -> {
            Uri resolvedUri = normalizeImageOrientationIfNeeded(originalInputUri);
            if (resolvedUri == null) {
                resolvedUri = originalInputUri;
            }

            Uri finalResolvedUri = resolvedUri;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                cropDisplayUri = finalResolvedUri;
                cropImageView.setImageUriAsync(cropDisplayUri);
                imageReady = true;
                btnIdentify.setEnabled(true);
            });
        });
    }

    private void cleanupBurstFrameCache() {
        try {
            File dir = new File(getCacheDir(), "camera_burst_frames");
            if (!dir.exists()) return;

            File[] files = dir.listFiles();
            if (files == null) return;

            for (File file : files) {
                if (file != null && file.exists() && !file.delete()) {
                    Log.w(TAG, "Failed to delete burst temp file: " + file.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to clean burst frame cache.", e);
        }
    }

    private void deleteTempFileIfOwnedByApp(@Nullable Uri uri) {
        if (uri == null || uri.getPath() == null) return;

        try {
            File file = new File(uri.getPath());
            String cacheRoot = getCacheDir().getAbsolutePath();
            String filePath = file.getAbsolutePath();

            if (!filePath.startsWith(cacheRoot)) return;
            if (!file.exists()) return;

            if (!file.delete()) {
                Log.w(TAG, "Failed to delete temp file: " + filePath);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete temp file for uri.", e);
        }
    }

    private Uri saveBitmapToFile(Bitmap bmp) {
        FileOutputStream fos = null;
        try {
            File dir = new File(getCacheDir(), "cropped_images");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }

            File file = new File(dir, "cropped_" + System.currentTimeMillis() + ".jpg");
            fos = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();

            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL)
            );
            exif.saveAttributes();

            return Uri.fromFile(file);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save cropped bitmap.", e);
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Nullable
    private Uri normalizeImageOrientationIfNeeded(Uri sourceUri) {
        try {
            int orientation = readExifOrientation(sourceUri);

            if (orientation == ExifInterface.ORIENTATION_UNDEFINED ||
                    orientation == ExifInterface.ORIENTATION_NORMAL) {
                return sourceUri;
            }

            Bitmap originalBitmap = decodeSampledBitmapFromUri(sourceUri, 2048);
            if (originalBitmap == null) {
                return sourceUri;
            }

            Matrix matrix = buildMatrixFromExifOrientation(orientation);
            Bitmap transformedBitmap = Bitmap.createBitmap(
                    originalBitmap,
                    0,
                    0,
                    originalBitmap.getWidth(),
                    originalBitmap.getHeight(),
                    matrix,
                    true
            );

            if (transformedBitmap != originalBitmap) {
                originalBitmap.recycle();
            }

            return saveNormalizedBitmapToFile(transformedBitmap);
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of memory while normalizing image orientation.", oom);
            return sourceUri;
        } catch (Exception e) {
            Log.w(TAG, "Failed to normalize image orientation, using original URI.", e);
            return sourceUri;
        }
    }

    private int readExifOrientation(Uri uri) {
        InputStream inputStream = null;
        try {
            inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                return ExifInterface.ORIENTATION_UNDEFINED;
            }

            ExifInterface exif = new ExifInterface(inputStream);
            return exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
            );
        } catch (Exception e) {
            Log.w(TAG, "Could not read EXIF orientation.", e);
            return ExifInterface.ORIENTATION_UNDEFINED;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Nullable
    private Bitmap decodeSampledBitmapFromUri(Uri uri, int maxDimension) {
        InputStream boundsStream = null;
        InputStream decodeStream = null;

        try {
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;

            boundsStream = getContentResolver().openInputStream(uri);
            if (boundsStream == null) return null;
            BitmapFactory.decodeStream(boundsStream, null, boundsOptions);

            int srcWidth = boundsOptions.outWidth;
            int srcHeight = boundsOptions.outHeight;

            if (srcWidth <= 0 || srcHeight <= 0) {
                return null;
            }

            int inSampleSize = 1;
            while ((srcWidth / inSampleSize) > maxDimension || (srcHeight / inSampleSize) > maxDimension) {
                inSampleSize *= 2;
            }

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = inSampleSize;
            decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;

            decodeStream = getContentResolver().openInputStream(uri);
            if (decodeStream == null) return null;

            return BitmapFactory.decodeStream(decodeStream, null, decodeOptions);
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode sampled bitmap.", e);
            return null;
        } finally {
            if (boundsStream != null) {
                try {
                    boundsStream.close();
                } catch (IOException ignored) {
                }
            }
            if (decodeStream != null) {
                try {
                    decodeStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Matrix buildMatrixFromExifOrientation(int orientation) {
        Matrix matrix = new Matrix();

        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;

            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;

            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;

            case ExifInterface.ORIENTATION_NORMAL:
            case ExifInterface.ORIENTATION_UNDEFINED:
            default:
                break;
        }

        return matrix;
    }

    @Nullable
    private Uri saveNormalizedBitmapToFile(Bitmap bmp) {
        FileOutputStream fos = null;
        try {
            File dir = new File(getCacheDir(), "normalized_crop_inputs");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }

            File file = new File(dir, "normalized_" + System.currentTimeMillis() + ".jpg");
            fos = new FileOutputStream(file);

            bmp.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();

            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL)
            );
            exif.saveAttributes();

            return Uri.fromFile(file);
        } catch (Exception e) {
            Log.w(TAG, "Failed to save normalized bitmap.", e);
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}