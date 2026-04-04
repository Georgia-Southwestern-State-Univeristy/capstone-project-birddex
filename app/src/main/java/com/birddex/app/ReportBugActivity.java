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

/**
 * ReportBugActivity: Provides a form for users to submit bug reports or feedback.
 *
 * Collected reports include device information and optional user contact details
 * to assist in debugging and follow-up.
 */
public class ReportBugActivity extends AppCompatActivity {

    private EditText etBugSubject, etBugDescription, etUserEmail;
    private Button btnSubmitBug;
    private ImageView btnBack;
    private MaterialCheckBox cbEmailOptIn;
    private LinearLayout layoutUserEmail;
    private FirebaseManager firebaseManager;

    /**
     * Android calls this when the Activity is first created. This is where the screen
     * inflates its layout, grabs views, and wires listeners.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report_bug);

        // Initialize FirebaseManager to handle data persistence.
        firebaseManager = new FirebaseManager(this);

        // Bind UI components.
        etBugSubject = findViewById(R.id.etBugSubject);
        etBugDescription = findViewById(R.id.etBugDescription);
        etUserEmail = findViewById(R.id.etUserEmail);
        btnSubmitBug = findViewById(R.id.btnSubmitBug);
        btnBack = findViewById(R.id.btnBack);
        cbEmailOptIn = findViewById(R.id.cbEmailOptIn);
        layoutUserEmail = findViewById(R.id.layoutUserEmail);

        btnBack.setOnClickListener(v -> finish());

        // Toggle visibility of the email input field based on user opt-in for follow-up.
        cbEmailOptIn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutUserEmail.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Wires the submission logic.
        btnSubmitBug.setOnClickListener(v -> {
            String subject = etBugSubject.getText().toString().trim();
            String description = etBugDescription.getText().toString().trim();
            String userEmail = etUserEmail.getText().toString().trim();

            // Validate mandatory fields.
            if (subject.isEmpty()) {
                etBugSubject.setError("Subject is required");
                return;
            }

            if (description.isEmpty()) {
                etBugDescription.setError("Description is required");
                return;
            }

            // Validate email if the user opted in for contact.
            if (cbEmailOptIn.isChecked()) {
                if (userEmail.isEmpty()) {
                    etUserEmail.setError("Email address is required");
                    return;
                } else if (!Patterns.EMAIL_ADDRESS.matcher(userEmail).matches()) {
                    etUserEmail.setError("Please enter a valid email address");
                    return;
                }
            }

            // Proceed to submit the report to the backend.
            submitReport(subject, description, cbEmailOptIn.isChecked() ? userEmail : null);
        });
    }

    /**
     * Submits the bug report data via FirebaseManager.
     * Provides UI feedback (button state/text) during the asynchronous operation.
     */
    private void submitReport(String subject, String description, String userEmail) {
        // UI Feedback: Disable button to prevent multiple clicks while the request is in flight.
        btnSubmitBug.setEnabled(false);
        btnSubmitBug.setText("Sending...");

        firebaseManager.submitBugReport(subject, description, userEmail, task -> {
            if (task.isSuccessful()) {
                Toast.makeText(this, "Thank you for voicing your concerns!", Toast.LENGTH_LONG).show();
                finish(); // Close activity and return to settings on success.
            } else {
                // Re-enable input on failure so the user can try again.
                btnSubmitBug.setEnabled(true);
                btnSubmitBug.setText("Submit Report");
                Toast.makeText(this, "Failed to send report. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
