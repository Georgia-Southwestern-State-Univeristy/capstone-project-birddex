package com.birddex.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ViewBirdCardActivity: Viewer for a finished bird card and its metadata/actions.
 */
public class ViewBirdCardActivity extends AppCompatActivity {

    private static final String TAG = "ViewBirdCard";
    public static final String EXTRA_ALLOW_IMAGE_CHANGE = "com.birddex.app.extra.ALLOW_IMAGE_CHANGE";

    private static final String EXTRA_CARD_INDEX = "com.birddex.app.extra.CARD_INDEX";
    private static final String EXTRA_CARD_IMAGE_URLS = "com.birddex.app.extra.CARD_IMAGE_URLS";
    private static final String EXTRA_CARD_COMMON_NAMES = "com.birddex.app.extra.CARD_COMMON_NAMES";
    private static final String EXTRA_CARD_SCI_NAMES = "com.birddex.app.extra.CARD_SCI_NAMES";
    private static final String EXTRA_CARD_STATES = "com.birddex.app.extra.CARD_STATES";
    private static final String EXTRA_CARD_LOCALITIES = "com.birddex.app.extra.CARD_LOCALITIES";
    private static final String EXTRA_CARD_BIRD_IDS = "com.birddex.app.extra.CARD_BIRD_IDS";
    private static final String EXTRA_CARD_SLOT_IDS = "com.birddex.app.extra.CARD_SLOT_IDS";
    private static final String EXTRA_CARD_RARITIES = "com.birddex.app.extra.CARD_RARITIES";
    private static final String EXTRA_CARD_FAVORITES = "com.birddex.app.extra.CARD_FAVORITES";
    private static final String EXTRA_CARD_CAUGHT_TIMES = "com.birddex.app.extra.CARD_CAUGHT_TIMES";

    private static final long CONTROL_LOCK_MS = 1000L;

    private ImageView imgBird;
    private ImageButton btnFavoriteToggle;
    private Button btnChangeCardImage, btnBirdInfo, btnUpgradeCard;
    private TextView txtLocation, txtDateCaught;

    private String currentImageUrl, currentBirdId, currentState, currentLocality;
    private String currentSlotId, currentRarity;
    private String currentCommonName, currentScientificName;
    private Date currentCaughtDate;
    private boolean currentIsFavorite = false;
    private boolean isSavingFavorite = false;
    private boolean allowImageChange = true;

    private String[] swipeImageUrls;
    private String[] swipeCommonNames;
    private String[] swipeScientificNames;
    private String[] swipeStates;
    private String[] swipeLocalities;
    private String[] swipeBirdIds;
    private String[] swipeSlotIds;
    private String[] swipeRarities;
    private boolean[] swipeFavorites;
    private long[] swipeCaughtTimes;
    private int currentCardIndex = -1;

    private ActivityResultLauncher<Intent> changeCardImageLauncher;
    private ActivityResultLauncher<Intent> upgradeCardLauncher;
    private GestureDetector swipeGestureDetector;
    private int swipeDistanceThresholdPx;
    private int swipeVelocityThresholdPx;

    // FIX: Generation counter to ignore stale resolution callbacks
    private int resolutionGeneration = 0;
    private boolean isResolving = false;
    private boolean isSwipeAnimating = false;
    private boolean areControlsTemporarilyBlocked = false;

    public static void attachSwipeExtras(@Nullable Intent intent, @Nullable List<CollectionSlot> sourceSlots, int clickedIndex) {
        attachSwipeExtras(intent, sourceSlots, clickedIndex, true);
    }

    public static void attachSwipeExtras(@Nullable Intent intent, @Nullable List<CollectionSlot> sourceSlots, int clickedIndex, boolean includeOwnerControls) {
        if (intent == null || sourceSlots == null || sourceSlots.isEmpty() || clickedIndex < 0) return;

        ArrayList<CollectionSlot> swipeableSlots = new ArrayList<>();
        int swipeIndex = -1;

        for (int i = 0; i < sourceSlots.size(); i++) {
            CollectionSlot slot = sourceSlots.get(i);
            if (slot == null || isBlankStatic(slot.getImageUrl())) continue;

            if (i == clickedIndex) {
                swipeIndex = swipeableSlots.size();
            }
            swipeableSlots.add(slot);
        }

        if (swipeableSlots.size() < 2 || swipeIndex < 0) return;

        String[] imageUrls = new String[swipeableSlots.size()];
        String[] commonNames = new String[swipeableSlots.size()];
        String[] sciNames = new String[swipeableSlots.size()];
        String[] states = new String[swipeableSlots.size()];
        String[] localities = new String[swipeableSlots.size()];
        String[] birdIds = new String[swipeableSlots.size()];
        String[] slotIds = new String[swipeableSlots.size()];
        String[] rarities = new String[swipeableSlots.size()];
        boolean[] favorites = new boolean[swipeableSlots.size()];
        long[] caughtTimes = new long[swipeableSlots.size()];

        for (int i = 0; i < swipeableSlots.size(); i++) {
            CollectionSlot slot = swipeableSlots.get(i);
            imageUrls[i] = slot.getImageUrl();
            commonNames[i] = slot.getCommonName();
            sciNames[i] = slot.getScientificName();
            states[i] = slot.getState();
            localities[i] = slot.getLocality();
            birdIds[i] = slot.getBirdId();
            slotIds[i] = includeOwnerControls ? slot.getId() : null;
            rarities[i] = CardRarityHelper.normalizeRarity(slot.getRarity());
            favorites[i] = includeOwnerControls && slot.isFavorite();
            caughtTimes[i] = slot.getTimestamp() != null ? slot.getTimestamp().getTime() : -1L;
        }

        intent.putExtra(EXTRA_CARD_INDEX, swipeIndex);
        intent.putExtra(EXTRA_CARD_IMAGE_URLS, imageUrls);
        intent.putExtra(EXTRA_CARD_COMMON_NAMES, commonNames);
        intent.putExtra(EXTRA_CARD_SCI_NAMES, sciNames);
        intent.putExtra(EXTRA_CARD_STATES, states);
        intent.putExtra(EXTRA_CARD_LOCALITIES, localities);
        intent.putExtra(EXTRA_CARD_BIRD_IDS, birdIds);
        intent.putExtra(EXTRA_CARD_SLOT_IDS, slotIds);
        intent.putExtra(EXTRA_CARD_RARITIES, rarities);
        intent.putExtra(EXTRA_CARD_FAVORITES, favorites);
        intent.putExtra(EXTRA_CARD_CAUGHT_TIMES, caughtTimes);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_bird_card);

        initSwipeGestureDetector();

        changeCardImageLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Intent data = result.getData();
            String url = data.getStringExtra(ChangeCardImageActivity.RESULT_IMAGE_URL);
            long ts = data.getLongExtra(ChangeCardImageActivity.RESULT_TIMESTAMP, -1L);
            String refId = data.getStringExtra(ChangeCardImageActivity.RESULT_USER_BIRD_REF_ID);

            if (isBlank(url)) {
                Toast.makeText(this, "Invalid selection.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (url.equals(currentImageUrl)) {
                Toast.makeText(this, "Already in use.", Toast.LENGTH_SHORT).show();
                return;
            }

            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            final int myGen = ++resolutionGeneration;
            isResolving = true;
            updateButtonsEnabled(false);

            resolveSelectionAndApply(
                    user.getUid(),
                    new ImageChoice(url, ts > 0 ? new Date(ts) : null, normalizeBlankToNull(refId)),
                    myGen
            );
        });

        // Setup launcher for UpgradeActivity to handle auto-update of UI
        upgradeCardLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String newRarity = result.getData().getStringExtra(UpgradeActivity.EXTRA_NEW_RARITY);
                if (newRarity != null && !newRarity.equals(currentRarity)) {
                    currentRarity = newRarity;
                    updateSwipeCardCacheFromCurrentState();
                    refreshCardUI(); // Re-inflate layout and re-bind views
                    applyCurrentCardStateToControls();
                }
            }
        });

        initUI();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeGestureDetector != null) {
            swipeGestureDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void initUI() {
        allowImageChange = getIntent().getBooleanExtra(EXTRA_ALLOW_IMAGE_CHANGE, true);
        initializeSwipeDeckFromIntent();
        loadInitialCardState();

        refreshCardUI();

        btnChangeCardImage = findViewById(R.id.btnChangeCardImage);
        btnBirdInfo = findViewById(R.id.btnBirdInfo);
        btnUpgradeCard = findViewById(R.id.btnUpgradeCard);
        btnFavoriteToggle = findViewById(R.id.btnFavoriteToggle);

        btnChangeCardImage.setOnClickListener(v -> {
            if (areControlsTemporarilyBlocked) return;
            openImagePicker();
        });

        btnBirdInfo.setOnClickListener(v -> {
            if (areControlsTemporarilyBlocked) return;
            if (isBlank(currentBirdId)) return;
            startActivity(new Intent(this, BirdWikiActivity.class)
                    .putExtra(BirdWikiActivity.EXTRA_BIRD_ID, currentBirdId));
        });

        btnUpgradeCard.setOnClickListener(v -> {
            if (areControlsTemporarilyBlocked) return;
            if (isBlank(currentBirdId) || isBlank(currentSlotId)) return;
            openUpgradeScreen();
        });

        btnFavoriteToggle.setOnClickListener(v -> {
            if (areControlsTemporarilyBlocked) return;
            toggleFavorite();
        });

        applyCurrentCardStateToControls();
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void initSwipeGestureDetector() {
        float density = getResources().getDisplayMetrics().density;
        swipeDistanceThresholdPx = Math.round(96f * density);
        swipeVelocityThresholdPx = Math.round(96f * density);

        swipeGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                if (!canSwipeBetweenCards() || isResolving || isSwipeAnimating) return false;

                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();

                if (Math.abs(diffX) <= Math.abs(diffY)) return false;
                if (Math.abs(diffX) < swipeDistanceThresholdPx || Math.abs(velocityX) < swipeVelocityThresholdPx) {
                    return false;
                }

                return diffX < 0 ? showAdjacentCard(1) : showAdjacentCard(-1);
            }
        });
    }

    private void initializeSwipeDeckFromIntent() {
        swipeImageUrls = getIntent().getStringArrayExtra(EXTRA_CARD_IMAGE_URLS);
        swipeCommonNames = getIntent().getStringArrayExtra(EXTRA_CARD_COMMON_NAMES);
        swipeScientificNames = getIntent().getStringArrayExtra(EXTRA_CARD_SCI_NAMES);
        swipeStates = getIntent().getStringArrayExtra(EXTRA_CARD_STATES);
        swipeLocalities = getIntent().getStringArrayExtra(EXTRA_CARD_LOCALITIES);
        swipeBirdIds = getIntent().getStringArrayExtra(EXTRA_CARD_BIRD_IDS);
        swipeSlotIds = getIntent().getStringArrayExtra(EXTRA_CARD_SLOT_IDS);
        swipeRarities = getIntent().getStringArrayExtra(EXTRA_CARD_RARITIES);
        swipeFavorites = getIntent().getBooleanArrayExtra(EXTRA_CARD_FAVORITES);
        swipeCaughtTimes = getIntent().getLongArrayExtra(EXTRA_CARD_CAUGHT_TIMES);
        currentCardIndex = getIntent().getIntExtra(EXTRA_CARD_INDEX, -1);

        if (!hasValidSwipeDeck()) {
            currentCardIndex = -1;
        } else if (currentCardIndex < 0 || currentCardIndex >= swipeImageUrls.length) {
            currentCardIndex = 0;
        }
    }

    private void loadInitialCardState() {
        if (hasValidSwipeDeck()) {
            loadCardFromSwipeDeck(currentCardIndex);
            return;
        }

        currentImageUrl = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_IMAGE_URL);
        currentCommonName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_COMMON_NAME);
        currentScientificName = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SCI_NAME);
        currentBirdId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_BIRD_ID);
        currentSlotId = getIntent().getStringExtra(CollectionCardAdapter.EXTRA_SLOT_ID);
        currentRarity = CardRarityHelper.normalizeRarity(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_RARITY));
        currentState = normalizeBlankToNull(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_STATE));
        currentLocality = normalizeBlankToNull(getIntent().getStringExtra(CollectionCardAdapter.EXTRA_LOCALITY));
        currentIsFavorite = getIntent().getBooleanExtra(CollectionCardAdapter.EXTRA_IS_FAVORITE, false);
        long time = getIntent().getLongExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, -1L);
        currentCaughtDate = time > 0 ? new Date(time) : null;
    }

    private boolean showAdjacentCard(int direction) {
        if (!canSwipeBetweenCards() || isSwipeAnimating) return false;

        int targetIndex = currentCardIndex + direction;
        if (targetIndex < 0 || targetIndex >= swipeImageUrls.length) return false;

        animateSwipeToCard(targetIndex, direction);
        return true;
    }

    private void animateSwipeToCard(int targetIndex, int direction) {
        final View currentCardView = findViewById(R.id.cardPlaceholder);

        if (currentCardView == null) {
            currentCardIndex = targetIndex;
            loadCardFromSwipeDeck(currentCardIndex);
            refreshCardUI();
            applyCurrentCardStateToControls();
            scrollToTop();
            blockControlsTemporarily();
            return;
        }

        isSwipeAnimating = true;
        blockControlsTemporarily();

        float currentWidth = currentCardView.getWidth();
        if (currentWidth <= 0f) currentWidth = dpToPx(220f);

        final float exitDistance = Math.max(currentWidth * 0.16f, dpToPx(36f));
        final float enterDistance = Math.max(currentWidth * 0.10f, dpToPx(24f));

        final float exitTranslation = direction > 0 ? -exitDistance : exitDistance;
        final float enterTranslation = direction > 0 ? enterDistance : -enterDistance;

        currentCardView.animate().cancel();
        currentCardView.animate()
                .translationX(exitTranslation)
                .alpha(0.84f)
                .setDuration(110)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        currentCardView.animate().setListener(null);

                        currentCardIndex = targetIndex;
                        loadCardFromSwipeDeck(currentCardIndex);
                        refreshCardUI();
                        applyCurrentCardStateToControls();
                        scrollToTop();

                        final View newCardView = findViewById(R.id.cardPlaceholder);
                        if (newCardView == null) {
                            isSwipeAnimating = false;
                            applyCurrentCardStateToControls();
                            return;
                        }

                        newCardView.animate().cancel();
                        newCardView.setTranslationX(enterTranslation);
                        newCardView.setAlpha(0.84f);
                        newCardView.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(160)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        newCardView.animate().setListener(null);
                                        newCardView.setTranslationX(0f);
                                        newCardView.setAlpha(1f);
                                        isSwipeAnimating = false;
                                        applyCurrentCardStateToControls();
                                    }

                                    @Override
                                    public void onAnimationCancel(Animator animation) {
                                        newCardView.animate().setListener(null);
                                        newCardView.setTranslationX(0f);
                                        newCardView.setAlpha(1f);
                                        isSwipeAnimating = false;
                                        applyCurrentCardStateToControls();
                                    }
                                });
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        currentCardView.animate().setListener(null);
                        currentCardView.setTranslationX(0f);
                        currentCardView.setAlpha(1f);
                        isSwipeAnimating = false;
                        applyCurrentCardStateToControls();
                    }
                });
    }

    private void blockControlsTemporarily() {
        areControlsTemporarilyBlocked = true;
        View root = findViewById(android.R.id.content);
        if (root != null) {
            root.removeCallbacks(unblockControlsRunnable);
            root.postDelayed(unblockControlsRunnable, CONTROL_LOCK_MS);
        }
    }

    private final Runnable unblockControlsRunnable = () -> {
        areControlsTemporarilyBlocked = false;
        setButtonsClickableVisualState(true);
    };

    private void loadCardFromSwipeDeck(int index) {
        currentImageUrl = valueAt(swipeImageUrls, index);
        currentCommonName = valueAt(swipeCommonNames, index);
        currentScientificName = valueAt(swipeScientificNames, index);
        currentBirdId = valueAt(swipeBirdIds, index);
        currentSlotId = valueAt(swipeSlotIds, index);
        currentRarity = CardRarityHelper.normalizeRarity(valueAt(swipeRarities, index));
        currentState = normalizeBlankToNull(valueAt(swipeStates, index));
        currentLocality = normalizeBlankToNull(valueAt(swipeLocalities, index));
        currentIsFavorite = valueAt(swipeFavorites, index, false);
        long time = valueAt(swipeCaughtTimes, index, -1L);
        currentCaughtDate = time > 0 ? new Date(time) : null;
    }

    /**
     * Completely refreshes the card UI by swapping the rarity layout and re-binding views.
     */
    private void refreshCardUI() {
        FrameLayout cardPlaceholder = findViewById(R.id.cardPlaceholder);
        if (cardPlaceholder != null) {
            cardPlaceholder.removeAllViews();
            getLayoutInflater().inflate(CardRarityHelper.getLayoutResId(currentRarity), cardPlaceholder, true);
        }

        imgBird = findViewById(R.id.imgBird);
        txtLocation = findViewById(R.id.txtLocation);
        txtDateCaught = findViewById(R.id.txtDateCaught);

        TextView txtBirdName = findViewById(R.id.txtBirdName);
        TextView txtScientific = findViewById(R.id.txtScientific);

        if (txtBirdName != null) {
            txtBirdName.setText(!isBlank(currentCommonName) ? currentCommonName : (!isBlank(currentScientificName) ? currentScientificName : "Unknown Bird"));
        }
        if (txtScientific != null) {
            txtScientific.setText(!isBlank(currentScientificName) ? currentScientificName : "--");
        }

        if (txtLocation != null) txtLocation.setText(CardFormatUtils.formatLocation(currentState, currentLocality));
        if (txtDateCaught != null) txtDateCaught.setText(CardFormatUtils.formatCaughtDate(currentCaughtDate));

        loadBirdImage(currentImageUrl);
        updateFavoriteUi(currentIsFavorite);
    }

    private void applyCurrentCardStateToControls() {
        if (btnChangeCardImage == null || btnBirdInfo == null || btnUpgradeCard == null || btnFavoriteToggle == null) return;

        btnChangeCardImage.setVisibility(allowImageChange ? View.VISIBLE : View.GONE);

        if (isBlank(currentSlotId)) {
            btnFavoriteToggle.setVisibility(View.GONE);
        } else {
            btnFavoriteToggle.setVisibility(View.VISIBLE);
            refreshFavoriteState();
        }

        if (isBlank(currentBirdId)) {
            if (allowImageChange) {
                btnChangeCardImage.setEnabled(false);
                btnChangeCardImage.setText("No ID");
            }
            btnBirdInfo.setEnabled(false);
            btnUpgradeCard.setEnabled(false);
        } else {
            if (allowImageChange) {
                btnChangeCardImage.setText("Change Image");
                btnChangeCardImage.setEnabled(!isResolving);
            }
            btnBirdInfo.setEnabled(!isResolving);
            btnUpgradeCard.setEnabled(!isResolving && !isBlank(currentSlotId));
        }

        updateFavoriteUi(currentIsFavorite);
        updateButtonsEnabled(!isResolving);

        setButtonsClickableVisualState(!areControlsTemporarilyBlocked);
    }

    private void setButtonsClickableVisualState(boolean clickable) {
        if (btnChangeCardImage != null) {
            btnChangeCardImage.setClickable(clickable);
            btnChangeCardImage.setAlpha(1f);
        }
        if (btnBirdInfo != null) {
            btnBirdInfo.setClickable(clickable);
            btnBirdInfo.setAlpha(1f);
        }
        if (btnUpgradeCard != null) {
            btnUpgradeCard.setClickable(clickable);
            btnUpgradeCard.setAlpha(1f);
        }
        if (btnFavoriteToggle != null) {
            btnFavoriteToggle.setClickable(clickable);
            btnFavoriteToggle.setAlpha(1f);
        }
    }

    private void openUpgradeScreen() {
        Intent i = new Intent(this, UpgradeActivity.class);
        i.putExtra(CollectionCardAdapter.EXTRA_SLOT_ID, currentSlotId);
        i.putExtra(CollectionCardAdapter.EXTRA_BIRD_ID, currentBirdId);
        i.putExtra(CollectionCardAdapter.EXTRA_RARITY, currentRarity);
        i.putExtra(CollectionCardAdapter.EXTRA_IMAGE_URL, currentImageUrl);
        i.putExtra(CollectionCardAdapter.EXTRA_COMMON_NAME, currentCommonName != null ? currentCommonName : "");
        i.putExtra(CollectionCardAdapter.EXTRA_SCI_NAME, currentScientificName != null ? currentScientificName : "");
        i.putExtra(CollectionCardAdapter.EXTRA_STATE, currentState);
        i.putExtra(CollectionCardAdapter.EXTRA_LOCALITY, currentLocality);
        if (currentCaughtDate != null) i.putExtra(CollectionCardAdapter.EXTRA_CAUGHT_TIME, currentCaughtDate.getTime());

        upgradeCardLauncher.launch(i);
    }

    private void loadBirdImage(String url) {
        if (isFinishing() || isDestroyed() || imgBird == null) return;
        Glide.with(this)
                .load(url)
                .override(800, 800)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_image_placeholder)
                .into(imgBird);
    }

    private void refreshFavoriteState() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || isBlank(currentSlotId)) return;

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .document(currentSlotId)
                .get(Source.CACHE)
                .addOnSuccessListener(this::applyFavoriteSnapshot);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .document(currentSlotId)
                .get(Source.SERVER)
                .addOnSuccessListener(this::applyFavoriteSnapshot)
                .addOnFailureListener(e -> Log.w(TAG, "Failed to refresh favorite state.", e));
    }

    private void applyFavoriteSnapshot(@Nullable DocumentSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return;
        currentIsFavorite = Boolean.TRUE.equals(snapshot.getBoolean("isFavorite"));
        updateSwipeCardCacheFromCurrentState();
        updateFavoriteUi(currentIsFavorite);
    }

    private void toggleFavorite() {
        if (isSavingFavorite || isBlank(currentSlotId)) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        final boolean targetValue = !currentIsFavorite;
        isSavingFavorite = true;
        if (btnFavoriteToggle != null) btnFavoriteToggle.setEnabled(false);

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .collection("collectionSlot")
                .document(currentSlotId)
                .update("isFavorite", targetValue)
                .addOnSuccessListener(unused -> {
                    currentIsFavorite = targetValue;
                    updateSwipeCardCacheFromCurrentState();
                    updateFavoriteUi(currentIsFavorite);
                    isSavingFavorite = false;
                    if (btnFavoriteToggle != null) btnFavoriteToggle.setEnabled(true);
                    Toast.makeText(this, currentIsFavorite ? "Added to favorites." : "Removed from favorites.", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    isSavingFavorite = false;
                    if (btnFavoriteToggle != null) btnFavoriteToggle.setEnabled(true);
                    Toast.makeText(this, "Failed to update favorite.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Favorite toggle failed.", e);
                });
    }

    private void updateFavoriteUi(boolean isFavorite) {
        if (btnFavoriteToggle != null) {
            btnFavoriteToggle.setImageResource(isFavorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        }
    }

    private void openImagePicker() {
        if (isResolving || !allowImageChange || isBlank(currentBirdId)) return;
        changeCardImageLauncher.launch(new Intent(this, ChangeCardImageActivity.class)
                .putExtra(ChangeCardImageActivity.EXTRA_BIRD_ID, currentBirdId)
                .putExtra(ChangeCardImageActivity.EXTRA_CURRENT_IMAGE_URL, currentImageUrl)
                .putExtra(ChangeCardImageActivity.EXTRA_COMMON_NAME, currentCommonName != null ? currentCommonName : ""));
    }

    private void resolveSelectionAndApply(String uid, ImageChoice choice, int gen) {
        if (choice.userBirdRefId != null) {
            resolveFromUserBirdDocument(uid, choice, choice.userBirdRefId, gen);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("userBirdImage")
                .whereEqualTo("imageUrl", choice.imageUrl)
                .limit(1)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    if (!snap.isEmpty()) {
                        DocumentSnapshot d = snap.getDocuments().get(0);
                        resolveFromUserBirdDocument(uid, choice, d.getString("userBirdRefId"), gen);
                    } else {
                        resolveFromUserBirdByImage(uid, choice, gen);
                    }
                })
                .addOnFailureListener(e -> resolveFromUserBirdByImage(uid, choice, gen));
    }

    private void resolveFromUserBirdByImage(String uid, ImageChoice choice, int gen) {
        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .whereEqualTo("imageUrl", choice.imageUrl)
                .limit(5)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    DocumentSnapshot found = null;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        if (uid.equals(d.getString("userId")) && currentBirdId.equals(d.getString("birdSpeciesId"))) {
                            found = d;
                            break;
                        }
                    }
                    if (found == null) {
                        applyResolvedSelection(uid, new ResolvedSelection(choice.imageUrl, choice.timestamp, null, null, null), gen);
                    } else {
                        resolveFromLocation(uid, choice.imageUrl, found.getDate("timeSpotted"), found.getId(), found.getString("locationId"), gen);
                    }
                })
                .addOnFailureListener(e -> applyResolvedSelection(uid, new ResolvedSelection(choice.imageUrl, choice.timestamp, null, null, null), gen));
    }

    private void resolveFromUserBirdDocument(String uid, ImageChoice choice, String refId, int gen) {
        if (isBlank(refId)) {
            resolveFromUserBirdByImage(uid, choice, gen);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("userBirds")
                .document(refId)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    if (!snap.exists()) {
                        resolveFromUserBirdByImage(uid, choice, gen);
                    } else {
                        resolveFromLocation(uid, choice.imageUrl, snap.getDate("timeSpotted"), refId, snap.getString("locationId"), gen);
                    }
                })
                .addOnFailureListener(e -> resolveFromUserBirdByImage(uid, choice, gen));
    }

    private void resolveFromLocation(String uid, String url, Date ts, String refId, String locId, int gen) {
        if (isBlank(locId)) {
            applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, null, null), gen);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("locations")
                .document(locId)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, snap.getString("state"), snap.getString("locality")), gen);
                })
                .addOnFailureListener(e -> applyResolvedSelection(uid, new ResolvedSelection(url, ts, refId, null, null), gen));
    }

    private void applyResolvedSelection(String uid, ResolvedSelection resolved, int gen) {
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("collectionSlot")
                .whereEqualTo("birdId", currentBirdId)
                .limit(1)
                .get()
                .addOnSuccessListener(snap -> {
                    if (gen != resolutionGeneration || isFinishing()) return;
                    if (snap.isEmpty()) {
                        finalizeResolution(false, null);
                        return;
                    }

                    WriteBatch b = FirebaseFirestore.getInstance().batch();
                    Map<String, Object> u = new HashMap<>();
                    u.put("imageUrl", resolved.imageUrl);
                    u.put("state", normalizeBlankToNull(resolved.state));
                    u.put("locality", normalizeBlankToNull(resolved.locality));
                    if (resolved.timestamp != null) u.put("timestamp", resolved.timestamp);
                    u.put("userBirdId", normalizeBlankToNull(resolved.userBirdRefId));

                    b.update(snap.getDocuments().get(0).getReference(), u);
                    b.commit()
                            .addOnSuccessListener(v -> {
                                if (gen == resolutionGeneration) finalizeResolution(true, resolved);
                            })
                            .addOnFailureListener(e -> finalizeResolution(false, null));
                })
                .addOnFailureListener(e -> finalizeResolution(false, null));
    }

    private void finalizeResolution(boolean success, @Nullable ResolvedSelection resolved) {
        isResolving = false;
        updateButtonsEnabled(true);

        if (success && resolved != null) {
            currentImageUrl = resolved.imageUrl;
            if (resolved.timestamp != null) currentCaughtDate = resolved.timestamp;
            currentState = normalizeBlankToNull(resolved.state);
            currentLocality = normalizeBlankToNull(resolved.locality);

            updateSwipeCardCacheFromCurrentState();
            refreshCardUI();
            applyCurrentCardStateToControls();

            Toast.makeText(this, "Updated.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to update card.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateButtonsEnabled(boolean enabled) {
        if (btnChangeCardImage != null && allowImageChange) {
            btnChangeCardImage.setEnabled(enabled && !isBlank(currentBirdId));
        }
        if (btnBirdInfo != null) {
            btnBirdInfo.setEnabled(enabled && !isBlank(currentBirdId));
        }
        if (btnUpgradeCard != null) {
            btnUpgradeCard.setEnabled(enabled && !isBlank(currentBirdId) && !isBlank(currentSlotId));
        }
        if (btnFavoriteToggle != null && !isBlank(currentSlotId) && !isSavingFavorite) {
            btnFavoriteToggle.setEnabled(enabled);
        }
    }

    private void updateSwipeCardCacheFromCurrentState() {
        if (!hasValidSwipeDeck() || currentCardIndex < 0 || currentCardIndex >= swipeImageUrls.length) return;

        swipeImageUrls[currentCardIndex] = currentImageUrl;
        if (swipeCommonNames != null && currentCardIndex < swipeCommonNames.length) swipeCommonNames[currentCardIndex] = currentCommonName;
        if (swipeScientificNames != null && currentCardIndex < swipeScientificNames.length) swipeScientificNames[currentCardIndex] = currentScientificName;
        if (swipeBirdIds != null && currentCardIndex < swipeBirdIds.length) swipeBirdIds[currentCardIndex] = currentBirdId;
        if (swipeSlotIds != null && currentCardIndex < swipeSlotIds.length) swipeSlotIds[currentCardIndex] = currentSlotId;
        if (swipeRarities != null && currentCardIndex < swipeRarities.length) swipeRarities[currentCardIndex] = currentRarity;
        if (swipeStates != null && currentCardIndex < swipeStates.length) swipeStates[currentCardIndex] = currentState;
        if (swipeLocalities != null && currentCardIndex < swipeLocalities.length) swipeLocalities[currentCardIndex] = currentLocality;
        if (swipeFavorites != null && currentCardIndex < swipeFavorites.length) swipeFavorites[currentCardIndex] = currentIsFavorite;
        if (swipeCaughtTimes != null && currentCardIndex < swipeCaughtTimes.length) {
            swipeCaughtTimes[currentCardIndex] = currentCaughtDate != null ? currentCaughtDate.getTime() : -1L;
        }
    }

    private boolean canSwipeBetweenCards() {
        return hasValidSwipeDeck() && swipeImageUrls.length > 1;
    }

    private boolean hasValidSwipeDeck() {
        return swipeImageUrls != null && swipeImageUrls.length > 0;
    }

    private void scrollToTop() {
        ScrollView scrollView = findViewById(R.id.scroll);
        if (scrollView != null) {
            scrollView.post(() -> scrollView.scrollTo(0, 0));
        }
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private String normalizeBlankToNull(String v) {
        return isBlank(v) ? null : v.trim();
    }

    private static boolean isBlankStatic(String v) {
        return v == null || v.trim().isEmpty();
    }

    private String valueAt(@Nullable String[] values, int index) {
        if (values == null || index < 0 || index >= values.length) return null;
        return values[index];
    }

    private boolean valueAt(@Nullable boolean[] values, int index, boolean fallback) {
        if (values == null || index < 0 || index >= values.length) return fallback;
        return values[index];
    }

    private long valueAt(@Nullable long[] values, int index, long fallback) {
        if (values == null || index < 0 || index >= values.length) return fallback;
        return values[index];
    }

    private static class ImageChoice {
        final String imageUrl;
        final String userBirdRefId;
        final Date timestamp;

        ImageChoice(String u, Date t, String r) {
            this.imageUrl = u;
            this.timestamp = t;
            this.userBirdRefId = r;
        }
    }

    private static class ResolvedSelection {
        final String imageUrl;
        final String userBirdRefId;
        final String state;
        final String locality;
        final Date timestamp;

        ResolvedSelection(String u, Date t, String r, String s, String l) {
            this.imageUrl = u;
            this.timestamp = t;
            this.userBirdRefId = r;
            this.state = s;
            this.locality = l;
        }
    }
}