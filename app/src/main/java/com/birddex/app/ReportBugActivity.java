package com.birddex.app;

import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.checkbox.MaterialCheckBox;

public class ReportBugActivity extends AppCompatActivity {

    private EditText etBugSubject, etBugDescription, etUserEmail;
    private Button btnSubmitBug;
    private ImageView btnBack;
    private MaterialCheckBox cbEmailOptIn;
    private LinearLayout layoutUserEmail;
    private FirebaseManager firebaseManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarHelper.applyStandardNavBar(this);
        setContentView(R.layout.activity_report_bug);

        // Initialize FirebaseManager
        firebaseManager = new FirebaseManager(this);

        etBugSubject = findViewById(R.id.etBugSubject);
        etBugDescription = findViewById(R.id.etBugDescription);
        etUserEmail = findViewById(R.id.etUserEmail);
        btnSubmitBug = findViewById(R.id.btnSubmitBug);
        btnBack = findViewById(R.id.btnBack);
        cbEmailOptIn = findViewById(R.id.cbEmailOptIn);
        layoutUserEmail = findViewById(R.id.layoutUserEmail);

        btnBack.setOnClickListener(v -> finish());

        cbEmailOptIn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutUserEmail.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        btnSubmitBug.setOnClickListener(v -> {
            String subject = etBugSubject.getText().toString().trim();
            String description = etBugDescription.getText().toString().trim();
            String userEmail = etUserEmail.getText().toString().trim();

            if (subject.isEmpty()) {
                etBugSubject.setError("Subject is required");
                return;
            }

            if (description.isEmpty()) {
                etBugDescription.setError("Description is required");
                return;
            }

            if (cbEmailOptIn.isChecked()) {
                if (userEmail.isEmpty()) {
                    etUserEmail.setError("Email address is required");
                    return;
                } else if (!Patterns.EMAIL_ADDRESS.matcher(userEmail).matches()) {
                    etUserEmail.setError("Please enter a valid email address");
                    return;
                }
            }

            submitReport(subject, description, cbEmailOptIn.isChecked() ? userEmail : null);
        });
    }

    private void submitReport(String subject, String description, String userEmail) {
        // UI Feedback: Disable button to prevent multiple clicks
        btnSubmitBug.setEnabled(false);
        btnSubmitBug.setText("Sending...");

        firebaseManager.submitBugReport(subject, description, userEmail, task -> {
            if (task.isSuccessful()) {
                Toast.makeText(this, "Thank you for voicing your concerns!", Toast.LENGTH_LONG).show();
                finish(); // Close activity and return to settings
            } else {
                // Re-enable on failure
                btnSubmitBug.setEnabled(true);
                btnSubmitBug.setText("Submit Report");
                Toast.makeText(this, "Failed to send report. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }
}