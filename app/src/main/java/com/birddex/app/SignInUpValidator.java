package com.birddex.app;

import android.text.TextUtils;
import android.util.Patterns;
import android.widget.EditText;

/**
 * Shared validation for sign-in, sign-up, and forgot-password flows.
 */
public final class SignInUpValidator {

    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MIN_USERNAME_LENGTH = 2;

    public boolean validateSignInForm(EditText emailField, EditText passwordField) {
        if (emailField == null || passwordField == null) {
            return false;
        }
        String email = safeTrim(emailField);
        String password = passwordField.getText() != null ? passwordField.getText().toString() : "";

        boolean ok = true;
        if (TextUtils.isEmpty(email)) {
            emailField.setError("Email is required.");
            ok = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailField.setError("Enter a valid email address.");
            ok = false;
        } else {
            emailField.setError(null);
        }

        if (TextUtils.isEmpty(password)) {
            passwordField.setError("Password is required.");
            ok = false;
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            passwordField.setError("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            ok = false;
        } else {
            passwordField.setError(null);
        }

        return ok;
    }

    public boolean validateSignUpForm(EditText usernameField, EditText emailField, EditText passwordField) {
        if (usernameField == null || emailField == null || passwordField == null) {
            return false;
        }
        String username = safeTrim(usernameField);
        String email = safeTrim(emailField);
        String password = passwordField.getText() != null ? passwordField.getText().toString() : "";

        boolean ok = true;

        if (TextUtils.isEmpty(username)) {
            usernameField.setError("Username is required.");
            ok = false;
        } else if (username.length() < MIN_USERNAME_LENGTH) {
            usernameField.setError("Username must be at least " + MIN_USERNAME_LENGTH + " characters.");
            ok = false;
        } else {
            usernameField.setError(null);
        }

        if (TextUtils.isEmpty(email)) {
            emailField.setError("Email is required.");
            ok = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailField.setError("Enter a valid email address.");
            ok = false;
        } else {
            emailField.setError(null);
        }

        if (TextUtils.isEmpty(password)) {
            passwordField.setError("Password is required.");
            ok = false;
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            passwordField.setError("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            ok = false;
        } else {
            passwordField.setError(null);
        }

        return ok;
    }

    public boolean validateForgotPasswordForm(EditText emailField) {
        if (emailField == null) {
            return false;
        }
        String email = safeTrim(emailField);
        if (TextUtils.isEmpty(email)) {
            emailField.setError("Email is required.");
            return false;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailField.setError("Enter a valid email address.");
            return false;
        }
        emailField.setError(null);
        return true;
    }

    private static String safeTrim(EditText field) {
        if (field.getText() == null) {
            return "";
        }
        return field.getText().toString().trim();
    }
}
