package com.birddex.app;

import android.widget.EditText;

/**
 * sign_IN_upValidator is a utility class for validating user input fields in forms.
 * It provides methods to check for required fields, proper email formatting, 
 * and password length constraints for sign-up, sign-in, and password reset.
 */
public class sign_IN_upValidator {

    /**
     * Validates the fields for the Sign Up form.
     * @param fullName EditText for user's full name.
     * @param email EditText for user's email.
     * @param password EditText for user's password.
     * @return true if all fields are valid, false otherwise.
     */
    public boolean validateSignUpForm(EditText fullName, EditText email, EditText password) {
        boolean valid = true;

        String fullNameStr = fullName.getText().toString();
        String emailStr = email.getText().toString();
        String passwordStr = password.getText().toString();

        // Check if full name is empty.
        if (fullNameStr.isEmpty()) {
            fullName.setError("Required.");
            valid = false;
        } else {
            fullName.setError(null);
        }

        // Check if email is empty or incorrectly formatted.
        if (emailStr.isEmpty()) {
            email.setError("Required.");
            valid = false;
        } else if (!emailStr.contains("@") || emailStr.substring(emailStr.indexOf("@") + 1).isEmpty()) {
            email.setError("Please enter a valid email address.");
            valid = false;
        } else {
            email.setError(null);
        }

        // Check if password is empty or too short.
        if (passwordStr.isEmpty()) {
            password.setError("Required.");
            valid = false;
        } else if (passwordStr.length() < 6) {
            password.setError("Password must be at least 6 characters long.");
            valid = false;
        } else {
            password.setError(null);
        }

        return valid;
    }

    /**
     * Validates the fields for the Sign In form.
     * @param email EditText for user's email.
     * @param password EditText for user's password.
     * @return true if both fields are valid, false otherwise.
     */
    public boolean validateSignInForm(EditText email, EditText password) {
        boolean valid = true;

        String emailStr = email.getText().toString();
        String passwordStr = password.getText().toString();

        // Check if email is empty or incorrectly formatted.
        if (emailStr.isEmpty()) {
            email.setError("Required.");
            valid = false;
        } else if (!emailStr.contains("@") || emailStr.substring(emailStr.indexOf("@") + 1).isEmpty()) {
            email.setError("Please enter a valid email address.");
            valid = false;
        } else {
            email.setError(null);
        }

        // Check if password is empty or too short.
        if (passwordStr.isEmpty()) {
            password.setError("Required.");
            valid = false;
        } else if (passwordStr.length() < 6) {
            password.setError("Password must be at least 6 characters long.");
            valid = false;
        } else {
            password.setError(null);
        }

        return valid;
    }

    /**
     * Validates the field for the Forgot Password form.
     * @param email EditText for user's email.
     * @return true if the email field is valid, false otherwise.
     */
    public boolean validateForgotPasswordForm(EditText email) {
        boolean valid = true;
        String emailStr = email.getText().toString();
        
        // Check if email is empty or incorrectly formatted.
        if (emailStr.isEmpty()) {
            email.setError("Required.");
            valid = false;
        } else if (!emailStr.contains("@") || emailStr.substring(emailStr.indexOf("@") + 1).isEmpty()) {
            email.setError("Please enter a valid email address.");
            valid = false;
        } else {
            email.setError(null);
        }
        return valid;
    }
}
