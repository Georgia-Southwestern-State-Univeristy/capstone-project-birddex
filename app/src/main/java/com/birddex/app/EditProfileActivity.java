package com.birddex.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class EditProfileActivity extends AppCompatActivity {

    private TextInputEditText etUsername;
    private TextInputEditText etBio;
    private String initialUsername;
    private String initialBio;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_profile);

        etUsername = findViewById(R.id.etUsername);
        etBio = findViewById(R.id.etBio);

        initialUsername = getIntent().getStringExtra("username");
        initialBio = getIntent().getStringExtra("bio");

        etUsername.setText(initialUsername);
        etBio.setText(initialBio);

        MaterialButton btnSave = findViewById(R.id.btnSave);
        TextView tvCancel = findViewById(R.id.tvCancel);

        btnSave.setOnClickListener(v -> {
            String newUsername = etUsername.getText().toString();
            String newBio = etBio.getText().toString();

            if (newUsername.trim().isEmpty()) {
                etUsername.setError("Username cannot be empty");
                return;
            }

            Intent resultIntent = new Intent();
            if (!newUsername.equals(initialUsername)) {
                resultIntent.putExtra("newUsername", newUsername);
            }
            if (!newBio.equals(initialBio)) {
                resultIntent.putExtra("newBio", newBio);
            }
            setResult(RESULT_OK, resultIntent);
            finish();
        });

        tvCancel.setOnClickListener(v -> finish());
    }
}
