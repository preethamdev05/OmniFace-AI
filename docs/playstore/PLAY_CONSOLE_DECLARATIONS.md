# 📋 Google Play Console Declarations & Review Guide — OmniFace AI

This document provides exact, pre-filled answers to all mandatory questionnaires and policy declarations in the Google Play Console for **OmniFace AI**.

---

## 1. App Content & Target Audience

* **Target Age Group**: Ages 18 and over (Administrators / School & Office Operators).  
  * *Note*: Although the app may be used to take attendance of children in schools, the app user/operator is the teacher, administrator, or organization manager.
* **Could this app appeal to children?**: No. The app interface is an administrative biometric kiosk and reporting utility.

---

## 2. Data Safety Form Responses

### A. Data Collection and Security
* **Does your app collect or share any of the required user data types?**: **Yes**
* **Is all of the user data collected by your app encrypted in transit?**: **Yes** (All optional sync communication uses TLS 1.3).
* **Do you provide a way for users to request that their data be deleted?**: **Yes** (Built-in DPDP Data Wipe button in System Settings, plus manual profile deletion).

### B. Specific Data Types Declared

#### 1. Personal Info
* **Name**:
  * *Collected?*: Yes (Stored locally by the administrator for student/employee rosters).
  * *Shared?*: No.
  * *Ephemeral?*: No. Stored in local SQLite database.
  * *Required or Optional?*: Required for student identification.
  * *Purposes*: App functionality, Account management.

#### 2. Photos and Videos
* **Photos**:
  * *Collected?*: **No.** (The camera is used in real-time ephemerally; frames are processed in volatile RAM only and discarded immediately).
  * *Shared?*: No.
  * *Ephemeral processing declaration*: Camera frames are processed strictly in volatile memory for biometric embedding extraction. No photographs or video recordings are saved to storage or uploaded.

#### 3. Biometrics
* **Biometric information (Facial embeddings)**:
  * *Collected?*: Yes (512-dimensional mathematical float vectors).
  * *Shared?*: No.
  * *Processed Ephemerally?*: The camera image is processed ephemerally. The resulting mathematical vector is stored in local encrypted database.
  * *Purposes*: App functionality (Attendance verification).

---

## 3. Financial Features & Monetization

* **Financial Features**: No loans, banking, or credit.
* **In-App Purchases**: Yes. (Uses Google Play In-App Billing for Premium Plan at ₹199/month).
* **Subscription Terms**: 
  * Free Starter: Up to 25 students.
  * Premium Pro: ₹199/month (recurring, cancellable anytime in Google Play Subscriptions).
  * Business Plan: ₹999/month (invoiced for institutional fleets).

---

## 4. Permissions Justification Declarations

### `android.permission.CAMERA`
* **Prominent Disclosure**: Displayed in Onboarding Wizard (Step 1) and before first camera launch.
* **User-Facing Description**: "OmniFace AI requires camera access to detect facial landmarks and verify attendance in real-time. Photographs are processed ephemerally and are never saved or uploaded."

### `android.permission.POST_NOTIFICATIONS`
* **Purpose**: Alerts administrators when automated cloud synchronization completes or if a kiosk battery runs low.

### `android.permission.BLUETOOTH_CONNECT`
* **Purpose**: Local peer-to-peer kiosk mesh synchronization in multi-gate offline environments.

---

## 5. App Access / Testing Credentials for Google Play Reviewers

Provide these credentials in the **"App access"** section of Play Console:

* **Access Type**: All functionality is available without special access restrictions.
* **Demo Data**: The app features a built-in **"Preload 5 Demo Students"** button directly inside the first-launch Onboarding Wizard. Reviewers can click this button to instantly test face attendance in 30 seconds without enrolling custom identities.
