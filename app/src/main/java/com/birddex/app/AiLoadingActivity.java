package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * AiLoadingActivity: A transitional loading screen that simulates AI processing
 * between BirdInfoActivity and NotMyBirdActivity.
 */
public class AiLoadingActivity extends AppCompatActivity {

    private TextView tvLoadingMessage;
    private final String[] messages = {
            "Consulting AI...",
            "Analyzing plumage patterns...",
            "Checking beak morphology...",
            "Comparing with regional data...",
            "Almost there..."
    };
    private int messageIndex = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable messageSwitcher = new Runnable() {
        @Override
        public void run() {
            if (tvLoadingMessage != null) {
                tvLoadingMessage.setText(messages[messageIndex]);
                messageIndex = (messageIndex + 1) % messages.length;
                handler.postDelayed(this, 1200);
            }
        }
    };

    private final ActivityResultLauncher<Intent> notMyBirdLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // Pass the result back to BirdInfoActivity
                setResult(result.getResultCode(), result.getData());
                finish();
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_loading);

        tvLoadingMessage = findViewById(R.id.tvLoadingMessage);
        handler.post(messageSwitcher);

        // Simulate "AI work" for 3 seconds, then move to NotMyBirdActivity
        handler.postDelayed(() -> {
            Intent intent = new Intent(AiLoadingActivity.this, NotMyBirdActivity.class);
            // Pass all extras received from BirdInfoActivity
            if (getIntent().getExtras() != null) {
                intent.putExtras(getIntent().getExtras());
            }
            notMyBirdLauncher.launch(intent);
        }, 3000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(messageSwitcher);
    }
}
