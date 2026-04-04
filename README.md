# 🐦 BirdDex

### Computer Science Capstone Project

---

## 📖 Project Overview

BirdDex is an Android mobile application that allows users to **capture, identify, and catalog bird species using their own photos**.

The system integrates:

* real-time camera-based image capture
* a **hybrid AI identification pipeline (in-house + OpenAI)**
* cloud-based data storage and processing
* geospatial bird sighting visualization
* a moderated community forum

BirdDex functions as both:

* a **personal bird collection system**
* a **scalable wildlife observation platform**

---

## 🚀 System Status

BirdDex is a **fully integrated and functional system** demonstrating:

* end-to-end AI identification workflows
* real-time database synchronization
* scalable cloud architecture
* user-driven community interaction
* production-style moderation and validation systems

Recent development efforts focused on refining:

* AI identification reliability
* system performance and caching
* moderation and safety enforcement
* user experience and feature completeness

---

## ⚙️ Core Features

### 📸 Bird Capture

Users capture bird images using the CameraX API with on-device preprocessing.

---

### 🤖 AI Identification System

BirdDex uses a **multi-stage AI pipeline**:

* In-house model (primary identification)
* OpenAI integration (tie-break and fallback)
* Confidence-based decision logic
* User-assisted correction for uncertain results

---

### 🛡 Anti-Cheat Validation

Ensures valid captures using:

* screen detection
* motion validation (multi-frame capture)
* metadata inspection

This prevents invalid submissions and protects system integrity.

---

### 🐦 Bird Collection System

Users maintain a structured BirdDex collection:

* captured bird images
* species data
* rarity and points system
* duplicate detection

---

### 🌍 Near Me Feature

Displays nearby bird sightings using geospatial queries:

* powered by `userBirdSightings`
* real-time map rendering
* regional bird activity tracking

---

### 💬 Community Forum

The forum system supports:

* posts and threaded replies
* saved posts
* content reporting
* real-time updates

---

### 🛡 Moderation System

BirdDex includes a **fully implemented moderation system**:

* automated content filtering (text + image SafeSearch)
* report-based moderation thresholds
* warning and strike system
* suspensions and permanent bans
* appeal and audit workflows

---

## 🧰 Technology Stack

* **Platform:** Android
* **Language:** Java
* **Backend:** Firebase (Auth, Firestore, Storage, Cloud Functions)
* **APIs:** OpenAI, eBird, Nuthatch
* **Maps:** Google Maps SDK

---

# 🏗 System Architecture

![System Architecture](documents/diagrams/birddex_system_architecture_v2.pdf)

BirdDex follows a **layered architecture**:

Android App → Firebase Auth → Cloud Functions → AI Pipeline → Storage → Firestore → Feature Systems

This structure enables:

* scalable backend processing
* real-time updates
* modular system expansion

---

# 🔄 Core System Flows

## 🤖 AI Identification Flow

![AI Flow](documents/diagrams/birddex_ai_identification_flow_v2.pdf)

* In-house AI performs primary prediction
* OpenAI is used for tie-break and fallback scenarios
* Full identification pipeline is stored in Firestore
* Results update collection, sightings, and progression systems

---

## 📤 Upload Identification Flow

![Upload Flow](documents/diagrams/birddex_upload_flow_v2.pdf)

* Uses the same AI pipeline as live capture
* Includes additional validation (captureGuard)
* Allows user refinement for uncertain results
* Does not award points to maintain fairness

---

## 🌍 Near Me Flow

![Near Me Flow](documents/diagrams/birddex_near_me_flow.pdf)

* Triggered after verified identification
* Stores sightings in Firestore
* Uses geospatial queries to display nearby birds
* Renders results on an interactive map

---

## 💬 Forum Flow

![Forum Flow](documents/diagrams/birddex_forum_flow_v2.pdf)

* Backend-enforced moderation and validation
* Report thresholds trigger moderation states
* Supports appeals and content recovery
* Real-time UI updates through Firestore

---

# 🗄 Database Architecture

## 🔷 Core Data Model

![Core ERD](documents/diagrams/Main ERD-core app tables.pdf)

Includes:

* users
* birds
* userBirds
* identifications
* locations
* userBirdSightings

The `identifications` collection stores the **full AI pipeline**, enabling traceability and system analysis.

---

## 🛡 Moderation & Forum Data

![Moderation ERD](documents/diagrams/ERD-moderation, forum, and archive.pdf)

Includes:

* forumThreads + comments
* reports
* moderationEvents
* moderationAppeals
* audit logs
* archive collections

---

# 🔁 End-to-End System Flow

1. User captures or uploads image
2. Anti-cheat validation runs
3. Cloud Functions validate and route request
4. AI pipeline processes image:

   * in-house model
   * OpenAI (when needed)
5. Result verified with bird dataset (eBird)
6. Data stored:

   * image → Firebase Storage
   * pipeline → Firestore (`identifications`)
7. System updates:

   * collection
   * sightings
   * user stats
8. Event processing:

   * points
   * duplicates
   * logs
9. Features update:

   * collection
   * Near Me
   * forum
10. Real-time synchronization via Firestore

---

# 🧑‍💻 Setup Instructions

```bash
git clone https://github.com/Georgia-Southwestern-State-University/capstone-project-birddex.git
```

1. Open in Android Studio
2. Add `google-services.json` to `/app`
3. Sync Gradle
4. Run on emulator/device

---

# 📱 User Guide

### Capture Bird

* Open camera
* Take photo
* Wait for identification

### Save Bird

* Confirm result
* Add to collection

### View Collection

* Browse BirdDex grid

### Near Me

* Explore nearby bird sightings on the map

### Forum

* Create posts
* Reply to discussions
* Save posts
* Report content

---

# 📁 Project Structure

```text
app/
├── Activities
├── Fragments
├── API services
├── Firebase manager
└── UI adapters

documents/
└── diagrams/
```

---

# 📜 License

Educational use – Computer Science Capstone Project

---

## 📌 Notes

This documentation reflects the **current implemented system architecture and workflows**.
