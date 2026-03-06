# Capstone-Project-BirdDex

# BirdDex
### Computer Science Capstone Project

---

## Project Overview

BirdDex is an Android mobile application that allows users to **capture, identify, and catalog bird species using their own photos**. The application combines camera functionality, AI-assisted image recognition, and cloud-based storage to create a digital bird collection.

BirdDex enables users to photograph birds in the wild, receive AI-generated species identification, verify results using external bird databases, and store discoveries in a personal collection. The application also provides location-aware sightings and community discussion through integrated forum features.

The system serves as a **data modeling and scalable wildlife observation platform**, demonstrating the integration of mobile development, cloud infrastructure, and external APIs.

---

## Project Status

**In Development**

- ✔ Sprint 1 Complete – Core system foundations implemented  
- ✔ Sprint 2 Complete – Feature expansion and system stability improvements  

---

## Current Development Focus

Upcoming development work includes:

- Nuthatch API integration
- Improved bird identification fallback system
- Bird selection interface for uncertain matches
- Card rarity upgrade system
- In-app store mechanics
- Additional system performance optimizations

---

## Core System Features

### Bird Capture
Users capture bird images using the built-in device camera through the **CameraX API**.

### AI Identification
Captured images are processed through an AI pipeline that analyzes the bird and returns species identification data.

### Data Verification
Bird identification results are cross-referenced with the **eBird database** to validate species information.

### Bird Collection
Users can store identified birds in their personal **BirdDex collection**, including:

- Bird image
- Species information
- Capture location
- Capture timestamp

### Bird Facts
Bird facts are generated and cached to reduce repeated AI requests and improve system performance.

### Near Me Feature
Bird sightings are stored with geographic metadata and displayed on a map-based interface that allows users to view nearby bird activity.

### Community Forum
Users can share bird sightings and interact with the community using a forum system that supports:

- Threads
- Replies
- Pagination
- Content filtering
- Reporting

---

## Technology Stack

### Platform
Android

### Language
Java

### Build System
Gradle (Kotlin DSL)

### IDE
Android Studio

### Version Control
Git + GitHub

---

## Backend Infrastructure

### Firebase Services

BirdDex uses **Firebase** as its backend platform.

Services used:

- **Firebase Authentication** – user login and account management
- **Cloud Firestore** – structured cloud database
- **Firebase Storage** – image storage
- **Firebase Cloud Functions** – API processing and backend logic

Firebase manages authentication, stores bird metadata, and securely stores user-uploaded images.

Provider: Google Firebase  
https://firebase.google.com

---

## Third-Party APIs

### OpenAI API
Used for AI-assisted bird identification from captured images.

### eBird API
Bird observation database provided by the **Cornell Lab of Ornithology** used for species verification and bird metadata.

Provider: Cornell Lab of Ornithology  
https://ebird.org  

API Terms:  
https://ebird.org/api/terms-of-use

All eBird data used in this application is subject to the **eBird API Terms of Use and the eBird Data Access Terms**. Data is used for educational and research purposes only.

© Cornell Lab of Ornithology. Attribution is provided where applicable.

---

## System Architecture (Current Implementation)

The BirdDex system consists of a mobile client connected to cloud services and external data APIs.

### Mobile Application (Android)

Handles:

- User authentication
- Camera capture
- Image preprocessing
- API communication
- UI rendering
- Collection management
- Forum interaction
- Map-based sightings display

### Cloud Infrastructure

Firebase services manage:

- User accounts
- Bird data storage
- Image storage
- Bird fact caching
- Forum data
- Location-based sightings

### External APIs

External APIs provide:

- Bird species identification
- Bird metadata verification
- Reference bird images

---

### System Flow


User Captures Bird Image
↓
Image Processed in App
↓
AI Identification Request
↓
Verification via eBird Database
↓
Results Stored in Firestore
↓
Bird Added to User Collection
↓
Bird Sightings Stored for Near Me Feature


---

## Setup Instructions

### 1. Clone Repository

```bash
git clone https://github.com/Georgia-Southwestern-State-University/capstone-project-birddex.git
2. Open Project

Open the project in Android Studio.

3. Configure Firebase

Download the google-services.json file from Firebase and place it inside:

app/
4. Sync Gradle

Allow Android Studio to run Gradle Sync.

5. Run Application

Connect an Android device or emulator and run the application from Android Studio.

User Guide
Create Account

Launch the application

Register using an email and password

Sign in to access the main interface

Capture a Bird

Tap Camera

Take a photo of a bird

Wait for the AI identification process

Save Bird

Review the identification result

Confirm the species

Save the bird to your collection

View Collection

Navigate to BirdDex Collection to view all captured birds.

View Nearby Sightings

Open the Near Me screen to view nearby bird sightings displayed on the map.

Use Forum

Access the Forum to:

Share sightings

Reply to posts

Discuss bird observations with other users

License

This project is developed for educational purposes as part of a university Computer Science Capstone project.

Extras
==================================================================
Project Structure (UNDER MAINTENANCE)
==================================================================
capstone-project-birddex/

app/
├── src/main/java/com/example/birddex
│   ├── Activities
│   ├── Fragments
│   ├── API services
│   ├── Firebase manager
│   └── UI adapters
│
├── res/
│   ├── layout
│   ├── drawable
│   ├── values
│   └── menu
│
└── AndroidManifest.xml
