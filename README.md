# capstone-project-birddex
BirdDex 🐦
Computer Science Capstone Project

📖 Project Overview
BirdDex is a mobile Android application designed to help users capture, identify, and catalog bird species using their own photos.
The application integrates camera functionality, AI-assisted identification, and cloud-based storage to create an interactive digital “BirdDex” collection.
This repository documents the completion of Sprint 1 (MVP Foundations) and the transition into Sprint 2, which focuses on refining current functionality and expanding features.

🚧 Project Status
In Development
✔ Sprint 1 Complete – Core Functionality Implemented
🚀 Sprint 2 In Progress – Polishing & Feature Expansion

This README reflects the current state of the project. Features listed as planned are scheduled for future sprints.
🎯 Sprint 1 Goal

The primary goal of Sprint 1 was to establish a stable project foundation that future development can build upon.
Sprint 1 focused on:
Project setup and configuration
Repository and version control structure
Android project scaffolding
Initial integration of camera, AI services, and cloud database(s)

✅ Completed in Sprint 1

Android Studio project successfully created
Gradle build system configured
GitHub Classroom repository initialized
.gitignore configured to prevent committing local/build artifacts
Core Android project structure established
Team coordination and sprint planning completed
Firebase Authentication integration
Firebase Firestore and Cloud Storage integration
CameraX API integration
AI / Image Recognition API integration
Functional collection view for stored birds


🛠 Technology Stack

Platform: Android
Language: Java
Build System: Gradle (Kotlin DSL)
IDE: Android Studio
Version Control: Git & GitHub (GitHub Classroom)
API’s: Open AI API, Ebird API, Firebase Firestore
Planned for Future Sprints
Regional Board functionality
Redesigned index with placeholder cards and filtering
Hunters’ section
Additional features and refinements

**Third-Party Services**

Firebase (Google)
Firebase is used as the backend service for authentication, data storage, and media storage.
Service Provider: Google Firebase
Website: https://firebase.google.com
Services Used:
Firebase Authentication
Cloud Firestore
Firebase Cloud Storage
Usage: Non-commercial, educational use as part of a university capstone project

Firebase manages user accounts, stores bird metadata, and securely stores user-uploaded images. All usage complies with Google Firebase Terms of Service.
Shape
====================================================================================================
**Third-Party APIs & Data**

eBird API (Cornell Lab of Ornithology)
This project uses bird observation data accessed through the eBird API, provided by the Cornell Lab of Ornithology.
API Provider: Cornell Lab of Ornithology (eBird)
Website: https://ebird.org
API Terms of Use: https://ebird.org/api/terms-of-use
Usage: Non-commercial, educational use as part of a university capstone project

All eBird data used in this application is subject to the eBird API Terms of Use and the eBird Data Access Terms. Data is used for educational and research purposes only.
© Cornell Lab of Ornithology. Attribution is provided where applicable.
====================================================================================================

User Guide

Clone BirdDex Repo
Locate and Install BirdDex
Sign up with your email and create your user account
From the home screen, tap Camera and take a picture of a bird
Open the collection and view the saved bird images

 
========================================================================

Extras
 
========================================================================


📂Project Structure (UNDER MAINTENANCE)

capstone-project-birddex/

├── app/                                # Main Android application module
│   ├── build/                          # Compiled outputs (auto-generated)
│   ├── src/
│   │   ├── androidTest/                # Instrumented UI tests
│   │   ├── test/                       # Local unit tests
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/example/birddex/
│   │       │       ├── MainActivity.java
│   │       │       ├── LoginActivity.java
│   │       │       ├── SignUpActivity.java
│   │       │       ├── HomeActivity.java
│   │       │       ├── CameraFragment.java
│   │       │       ├── IdentifyingActivity.java
│   │       │       ├── ImageResultActivity.java
│   │       │       ├── BirdInfoActivity.java
│   │       │       ├── BirdLookupActivity.java
│   │       │       ├── ImageUploadActivity.java
│   │       │       ├── FirebaseManager.java
│   │       │       ├── OpenAiApi.java
│   │       │       ├── NuthatchApi.java
│   │       │       ├── ProfileFragment.java
│   │       │       ├── NearbyFragment.java
│   │       │       ├── ForumFragment.java
│   │       │       ├── SearchCollectionFragment.java
│   │       │       ├── FavoritesAdapter.java
│   │       │       ├── SimpleGridAdapter.java
│   │       │       └── SettingsApi.java
│   │       │
│   │       ├── res/                    # UI and resource files
│   │       │   ├── layout/             # Activity & Fragment layouts
│   │       │   ├── drawable/            # Images and vector assets
│   │       │   ├── menu/                # App menus
│   │       │   ├── values/              # Colors, styles, strings
│   │       │   ├── values-night/        # Dark mode resources
│   │       │   └── xml/                 # Configuration XML files
│   │       │
│   │       └── AndroidManifest.xml      # App configuration & permissions
│   │
│   ├── build.gradle.kts                 # App-level Gradle configuration
│   ├── google-services.json             # Firebase configuration
│   └── proguard-rules.pro               # ProGuard/R8 rules
│
├── gradle/                              # Gradle wrapper support files
├── .gitignore                           # Git ignore rules
├── README.md                            # Project documentation
├── build.gradle.kts                     # Project-level Gradle configuration
├── settings.gradle.kts                  # Module declarations
├── gradle.properties                    # Gradle settings
├── gradlew                              # Gradle wrapper (macOS/Linux)
├── gradlew.bat                          # Gradle wrapper (Windows)
├── local.properties                     # Local SDK paths (auto-generated)
├── .gradle/                             # Local Gradle cache (auto-generated)
├── .idea/                               # Android Studio settings (auto-generated)
└── build/                               # Root build outputs (auto-generated)
