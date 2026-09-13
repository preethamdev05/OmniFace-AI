# OmniFace — Final Technical & Product Plan

This is the consolidated plan after the architecture decisions, implementation audit, UI direction, biometric pipeline refactor, and hardware-adaptation design.

## 1. Product Definition

**OmniFace** is an offline-first face-recognition attendance platform.

```text
ANDROID
Capture
Recognize
Enroll
Store offline
Sync

BACKEND
Own truth
Authenticate
Authorize
Manage subscriptions
Synchronize
Audit

WEB
Manage organization
Manage people
Analyze attendance
Manage devices
Generate reports
```

The four technical pillars are:

```text
Recognition Correctness
        ↓
Attendance Durability
        ↓
Cloud Synchronization
        ↓
Blockchain/Audit Processing
```

That separation is now the architectural foundation. 

---

# 2. Final Commercial Model

| Plan            | People |   Price | Dashboard                |      Devices | Ads |
| --------------- | -----: | ------: | ------------------------ | -----------: | --- |
| **Free**        |     25 |      ₹0 | No operational dashboard |            1 | Yes |
| **Premium**     |    250 | ₹199/mo | Yes                      |            1 | No  |
| **Pro**         |    500 | ₹349/mo | Yes                      |      Up to 3 | No  |
| **Institution** |   500+ |  Custom | Full                     | Configurable | No  |

### Free

* Android attendance
* Offline recognition
* Local storage
* 25-person limit
* One device
* Ads
* Login/onboarding/subscription pages on web
* No operational web dashboard

### Premium

* 250 people
* Cloud synchronization
* Operational dashboard
* Basic reports
* One device
* Ad-free

### Pro

* 500 people
* Advanced reports
* XLSX/PDF
* Up to 3 devices
* Priority support
* Ad-free

### Institution

* 500+ people
* Full administration
* Multiple admins
* Departments
* Classes/sections
* Multiple devices
* Audit logs
* API
* Advanced reporting
* Custom onboarding

---

# 3. Identity Architecture

Use one Firebase identity system across Android and Web.

```text
Google
  ↓
Firebase Authentication
  ↓
Firebase UID
  ↓
OmniFace User
  ↓
Organization Membership
  ↓
Organization
```

Never:

```text
Firebase UID = Organization
```

Subscription belongs to the appropriate organization/account relationship, not to arbitrary Android local state.

The same authenticated identity on Android and Web must resolve to the same OmniFace account and entitlement.

---

# 4. Organization Model

```text
Organization
├── Owner
├── Admins
├── Teachers
├── Viewers
├── People
├── Classes
├── Departments
├── Devices
├── Attendance
├── Subscription
└── Audit Logs
```

Roles:

```text
OWNER
ADMIN
TEACHER
VIEWER
```

Server-side RBAC is mandatory.

No `DEFAULT_ORG_ID`.

Organization context must be derived from:

```text
Web → authenticated membership
Android → authenticated device
```

---

# 5. Backend Architecture

```text
Next.js / API
      ↓
Authentication
      ↓
Authorization / RBAC
      ↓
Tenant Resolution
      ↓
Domain Services
      ↓
Drizzle ORM
      ↓
PostgreSQL
```

PostgreSQL is the authoritative application database.

Supabase can remain the infrastructure provider for PostgreSQL, but do not split business-data ownership between Drizzle and a second Supabase data-access path.

---

# 6. Firebase Responsibilities

Use Firebase for supporting platform services:

```text
Firebase Authentication
Firebase Cloud Messaging
Firebase Crashlytics
Firebase Analytics
```

Do not use Firestore as the primary OmniFace attendance database unless a future requirement explicitly justifies it.

Firebase identity supports the account layer; PostgreSQL remains the system of record.

---

# 7. Google Play Billing

Products:

```text
omniface_premium_monthly_199
omniface_pro_monthly_349
```

Flow:

```text
Android
 ↓
Google Play
 ↓
Purchase token
 ↓
Backend
 ↓
Server-side verification
 ↓
Subscription
 ↓
Entitlement
 ↓
Android + Web
```

Support:

* Purchase
* Restore
* Renewal
* Cancellation
* Expiration
* Grace period
* Upgrade
* Downgrade
* Account relinking

The client cannot activate a paid plan by itself.

---

# 8. Entitlement Engine

Centralize plan capability evaluation.

Example:

```text
maxPeople
maxDevices
dashboardAccess
cloudSync
adsEnabled
basicReports
advancedReports
xlsxExport
pdfExport
multiAdmin
departments
apiAccess
prioritySupport
```

Every feature gate should consult the centralized entitlement layer.

---

# 9. AdMob

Use real Google Mobile Ads.

### Free only

Use a bottom adaptive banner on non-scanner screens.

No ads on:

* Scanner
* Face enrollment
* Attendance confirmation
* Any critical attendance workflow

Premium/Pro/Institution:

```text
No ads
```

Ad visibility is controlled by entitlements, not a local `isPremium` flag.

---

# 10. Customer Web Application

The web application is the administrative control center.

Navigation:

```text
Dashboard
Directory
Attendance
Reports

Classes
Departments
Devices
Staff

Subscription
Audit Log
Settings
```

### Dashboard

Show:

* Attendance rate
* Present
* Absent
* Late
* Recent activity
* Device health
* Sync health
* Quick actions

### Directory

* Students
* Faculty
* Staff
* Visitors
* Search
* Filters
* Bulk actions
* Enrollment status

### Attendance

* Daily/weekly/monthly
* Class/department/device filters
* Status
* Recognition information
* Sync state
* Manual adjustments

### Reports

* Daily
* Weekly
* Monthly
* Student
* Class
* Department
* Absence
* Late
* Device activity

### Devices

* Online/offline
* Last sync
* Pending events
* App version
* Model version
* Battery/storage where available
* Pair/revoke/reassign

### Staff

* Invite
* Role management
* Suspend
* Revoke

### Audit

Read-only audit history.

---

# 11. Android Application

Android remains the capture and edge-processing device.

Core responsibilities:

```text
Camera
Face Detection
Face Enrollment
Face Recognition
Liveness
Local Attendance
Local Database
Offline Queue
Bidirectional Synchronization
Device Health
```

Do not turn Android into the institutional administration platform.

---

# 12. Deep Biometric Architecture

Final architecture:

```text
Camera
  ↓
BiometricFrame
  ↓
BiometricVerificationEngine
 ├── IdentityStore
 ├── ExecutionGovernor
 ├── Face Detection / Tracking
 ├── Multi-modal Liveness
 ├── Vector Matching
 └── Temporal Consensus
  ↓
VerificationDecision
```

The domain contracts are already correctly structured as a pure verification layer with `IdentityStore` and `VerificationDecision`. 

---

# 13. IdentityStore

The verification module must not depend directly on Room.

```text
Room FaceTemplateEntity
        ↓
RoomIdentityStoreAdapter
        ↓
IdentityTemplate
        ↓
IdentityStore
        ↓
BiometricVerificationEngine
```

`IdentityTemplate` is a domain type.

The verification module should not know:

* Room
* DAOs
* SQL
* Room entity classes
* storage encryption layout

This is the correct port-and-adapter boundary. 

---

# 14. Verification Decisions

Use:

```text
Idle
Verified
Duplicate
Unknown
SpoofRejected
PoorQuality
```

The decision is the core per-frame domain result.

Typed geometry remains domain-level rather than UI-specific. 

---

# 15. Hardware Abstraction

Do **not** implement:

```text
if Snapdragon
if MediaTek
if Exynos
if Tensor
```

The correct architecture from the hardware plan is:

```text
Hardware Discovery
        ↓
Capability Normalizer
        ↓
Runtime Probe
        ↓
Execution Policy
        ↓
Inference Backend
        ↓
UI
```

The uploaded hardware architecture explicitly recommends this approach instead of chipset-specific branches. 

---

# 16. Hardware Profile

Create a normalized:

```kotlin
HardwareProfile
```

containing:

```text
SoC
CPU
GPU
Accelerators
RAM
Android API
```

Use Android's hardware-identification APIs first and separate identity from capability. 

---

# 17. Inference Backends

Use replaceable implementations:

```text
NpuBackend
GpuBackend
CpuBackend
```

Behind:

```kotlin
interface InferenceBackend
```

The application must select based on actual model compatibility, not merely the name of the chip. 

---

# 18. ExecutionGovernor

Runtime selection should consider:

1. Accelerator availability
2. Operator support
3. Precision support
4. Tensor compatibility
5. Measured latency
6. Memory usage
7. Thermal state

Then select:

```text
NPU
→ GPU
→ CPU
```

as appropriate. 

The governor stays inside the biometric module.

The UI receives capability information, not hardware-selection rules.

---

# 19. Runtime Profiling

Do not benchmark on every application launch.

Preferred:

```text
Install / Model update
       ↓
Capability discovery
       ↓
Model compatibility probe
       ↓
Short benchmark
       ↓
Selected backend cached
```

Reevaluate on meaningful events such as:

* model change
* app update
* backend failure
* thermal degradation
* configuration change

---

# 20. UI Hardware Display

Show useful information:

```text
CPU
8 cores · ARM64

GPU
Adreno

AI Accelerator
Available

Inference Backend
NPU

Performance
Very High

Thermal
Normal
```

Never make the UI branch on:

```text
Snapdragon → screen A
MediaTek → screen B
```

The UI consumes a normalized capability model. 

---

# 21. Attendance Durability

This is non-negotiable.

The flow is:

```text
Verified
   ↓
AttendanceService
   ↓
Room Transaction
   ├── attendance_record
   └── aegis_outbox
```

Both are committed atomically.

That means blockchain failure cannot destroy attendance reliability. 

---

# 22. Attendance Idempotency

Every attendance event needs a deterministic unique identifier.

Database:

```text
UNIQUE(recordId)
```

The same event retried must never create a second attendance record.

This protects against:

* camera duplicate callbacks
* process restart
* sync retries
* worker retries
* network interruption

---

# 23. Aegis Processing

Aegis must never block attendance.

Correct:

```text
Face verified
 ↓
Attendance persisted
 ↓
Aegis pending
 ↓
Background worker
 ↓
Mint
 ↓
Retry if necessary
```

If Aegis is unavailable:

```text
Attendance = successful
Aegis = pending
```

The outbox/worker architecture already captures this invariant. 

---

# 24. Bidirectional Synchronization

This is a major part of the final system.

### Upstream

```text
Android
 ↓
Attendance Events
 ↓
Backend
 ↓
PostgreSQL
```

### Downstream

```text
Backend
 ↓
Changes
 ↓
Android
```

Downstream changes include:

```text
STUDENT_CREATED
STUDENT_UPDATED
STUDENT_DEACTIVATED
FACE_UPDATED
FACE_DELETED
CLASS_CREATED
CLASS_UPDATED
CLASS_ASSIGNMENT_CHANGED
DEVICE_CONFIG_CHANGED
```

Use version/change cursors.

Example:

```text
Give me everything since version 184.
```

---

# 25. Device Dataset

Each Android device gets a scoped local dataset.

Do not download an entire institution's biometric database to every kiosk.

Example:

```text
Device A
→ Main Gate
→ Classes 1–5

Device B
→ Block B
→ Classes 6–10
```

Device assignment controls what the device receives.

---

# 26. Device Provisioning

Fresh device:

```text
Install
 ↓
Model Ready
 ↓
Pair
 ↓
Authenticate
 ↓
Receive authorized dataset
 ↓
Build face index
 ↓
Verify sync
 ↓
Scanner Ready
```

Do not mark the device fully ready until the required prerequisites exist.

---

# 27. Device Lifecycle

Use:

```text
PROVISIONED
ACTIVE
OFFLINE
REVOKED
REPLACED
RETIRED
```

Support:

* pair
* rename
* assign
* reassign
* revoke
* replace
* health monitoring

---

# 28. Device Health

Display:

```text
Device
ONLINE

Model
READY

Database
HEALTHY

Sync
SYNCED

Pending
0

Last Sync
10:41 AM
```

Offline:

```text
OFFLINE

Pending Events
183
```

This is useful operational information, unlike raw NPU benchmark numbers.

---

# 29. Face Enrollment

The web dashboard creates the person record.

The Android device performs the biometric capture.

```text
Web
Create person
 ↓
Assign class/device
 ↓
Face enrollment pending
 ↓
Android
Capture face
 ↓
Generate embedding
 ↓
Validate
 ↓
Update enrollment state
```

This keeps the biometric capture workflow device-centered.

---

# 30. Face Model Versioning

Every face template must be associated with:

```text
modelVersion
embeddingVersion
```

Changing the model later must not silently invalidate old templates.

Support controlled re-enrollment/rebuild when necessary.

---

# 31. Imports

Institution import:

```text
Upload CSV/XLSX
 ↓
Validate
 ↓
Preview
 ↓
Show row errors
 ↓
Confirm
 ↓
Transactional import
```

Never partially corrupt a roster.

Support:

* duplicate handling
* class mapping
* department mapping
* validation
* rollback

---

# 32. Organization Ownership

Support:

```text
Owner A
 ↓
Transfer ownership
 ↓
Owner B
```

Organization remains intact.

---

# 33. Account Deletion

Separate:

```text
Delete user
Delete organization
Delete biometric data
Delete device
```

Deleting a teacher must never delete the school’s attendance database.

---

# 34. Subscription State Machine

Use:

```text
TRIAL
ACTIVE
GRACE
PAST_DUE
CANCELLED
EXPIRED
ARCHIVED
```

Define exact feature access for each state.

Do not reduce everything to:

```text
ACTIVE / EXPIRED
```

---

# 35. Reporting

All reports derive from PostgreSQL.

Support:

```text
Daily
Weekly
Monthly
Person
Class
Department
Absence
Late
Attendance %
Device Activity
```

Exports:

```text
CSV
XLSX
PDF
```

Server-side entitlement enforcement is mandatory.

---

# 36. Audit

Audit privileged operations:

```text
STUDENT_CREATED
STUDENT_UPDATED
STUDENT_DELETED
FACE_REGISTERED
FACE_DELETED
ATTENDANCE_ADJUSTED
DEVICE_PAIRED
DEVICE_REVOKED
CLASS_CREATED
ROLE_CHANGED
STAFF_INVITED
SETTINGS_CHANGED
SUBSCRIPTION_CHANGED
```

Every event should include:

```text
organization
actor
action
entity
timestamp
reason
requestId
```

---

# 37. Firebase Notifications

Use FCM for meaningful notifications:

```text
Device Offline
Sync Failure
Subscription Expiring
Report Ready
Device Revoked
Security Alert
```

Implement token registration, refresh, invalidation and removal.

---

# 38. Crash Monitoring

Use Crashlytics for Android.

Monitor:

* crashes
* ANRs
* version-specific failures
* useful breadcrumbs

Never log sensitive biometric/authentication/payment data.

---

# 39. Analytics

Track:

```text
Install
Registration
Onboarding Complete
First Enrollment
First Attendance
Sync Success
Sync Failure
Subscription View
Premium Purchase
Pro Purchase
Institution Lead
```

Keep business analytics separate from low-level hardware diagnostics.

---

# 40. Database Hardening

PostgreSQL schema must contain:

* organizations
* users
* memberships
* people
* departments
* classes
* devices
* face templates
* attendance sessions
* attendance events
* attendance adjustments
* synchronization records
* subscriptions
* payments
* invoices
* audit logs
* Aegis outbox

Add real query-path indexes rather than blindly indexing everything.

---

# 41. Security

Mandatory protections:

```text
Cryptographic Firebase token validation
Signed/validated sessions
RBAC
Tenant isolation
Device authentication
Replay protection
Rate limiting
Webhook verification
Server-side billing verification
Secure storage
Secret management
Audit logging
```

Production must fail closed.

---

# 42. Testing Strategy

### Android

* Unit
* Instrumentation
* Database
* Offline mode
* Synchronization
* Recognition
* Billing

### Backend

* Unit
* Integration
* API
* Authorization
* Tenant isolation
* Billing
* Reports
* Audit
* Webhooks

### Behavioral equivalence

Run legacy and new biometric pipelines against the same inputs during migration.

The frozen biometric plan explicitly calls for equivalence testing and regression validation. 

---

# 43. Migration Strategy

Do not rewrite the biometric system in one destructive step.

Use:

```text
Existing FaceSecurityPipeline
        ↓
Compatibility Adapter
        ↓
New BiometricVerificationEngine
```

Then:

```text
Introduce interfaces
 ↓
Move implementation
 ↓
Run equivalence tests
 ↓
Migrate callers
 ↓
Remove old wrappers
 ↓
Delete legacy implementation
```

The old implementation should become an adapter, not a second independent engine.

---

# 44. UI Direction

### Android

Primary navigation:

```text
Home
Scan
Directory
Attendance
Settings
```

Scanner is the hero.

Hide engineering details in:

```text
Settings
→ Advanced
→ Diagnostics
```

Show users:

```text
Ready
Verified
Unknown
Offline
Synced
Pending
```

not:

```text
NPU 45 TOPS
3DMM
Attrib Net
Neural Suite
```

### Web

Primary navigation:

```text
Dashboard
Directory
Attendance
Reports
Classes
Departments
Devices
Staff
Subscription
Settings
Audit Log
```

The web application is the command center.

---

# 45. Design Philosophy

Use:

* iOS-inspired hierarchy
* premium dark mode
* clean typography
* restrained glass
* subtle borders
* high information density
* meaningful color

Avoid:

* excessive neon
* meaningless AI jargon
* fake technical metrics
* dashboard clutter
* consumer-style upgrade spam

The interface should communicate **operational reliability**, not simply technological sophistication.

---

# 46. Production Infrastructure

Separate:

```text
LOCAL
DEV
STAGING
PRODUCTION
```

Production pipeline:

```text
Git
 ↓
Pull Request
 ↓
Lint
 ↓
Typecheck
 ↓
Tests
 ↓
Security Scan
 ↓
Build
 ↓
Staging
 ↓
Smoke Tests
 ↓
Production
```

No unmanaged production changes.

---

# 47. Backup & Recovery

Implement:

* automated PostgreSQL backups
* off-site backup
* retention
* restore runbook
* restore testing

Define:

```text
RPO
RTO
```

A backup is not considered valid until restoration has actually been tested.

---

# 48. Scale Strategy

Benchmark:

```text
25
250
500
1,000
2,500
5,000
10,000
```

People.

Measure:

* face-index build time
* recognition latency
* memory
* synchronization
* API performance
* report generation
* database load

For large institutions, use device-scoped datasets rather than one device holding every biometric template.

---

# 49. Final Implementation Order

```text
PHASE 1
Security lockdown
        ↓
PHASE 2
Authentication + sessions
        ↓
PHASE 3
Organizations + RBAC + tenant isolation
        ↓
PHASE 4
Hardware discovery + capability architecture
        ↓
PHASE 5
Biometric deep-module migration
        ↓
PHASE 6
Durable attendance + outbox
        ↓
PHASE 7
Bidirectional device sync
        ↓
PHASE 8
Device lifecycle
        ↓
PHASE 9
Web dashboard completion
        ↓
PHASE 10
Entitlements
        ↓
PHASE 11
Google Play Premium/Pro
        ↓
PHASE 12
AdMob
        ↓
PHASE 13
Firebase FCM/Crashlytics/Analytics
        ↓
PHASE 14
Reports/imports/exports
        ↓
PHASE 15
Audit/security hardening
        ↓
PHASE 16
Testing
        ↓
PHASE 17
Backup/monitoring/CI-CD
        ↓
PHASE 18
Load + scale validation
        ↓
PHASE 19
Institution pilot
        ↓
PHASE 20
Production launch
```

---

# 50. Final State

The resulting system should be:

```text
                    OMNIFACE
                       |
        ┌──────────────┼──────────────┐
        |              |              |
     ANDROID        BACKEND          WEB
      Device        Platform        Admin
        |              |              |
     Camera        Auth/RBAC      Dashboard
     Face AI       Tenanting      Directory
     Room          Billing        Attendance
     Offline       Sync           Reports
     Sync          Audit          Devices
        |              |           Staff
        └──────────────┼───────────────
                       |
                   PostgreSQL
                       |
             ┌─────────┼─────────┐
             |         |         |
          Firebase   Storage    Aegis
          Services             Outbox
```

And the critical runtime path is:

```text
FACE
 ↓
Hardware Discovery
 ↓
Capability Profile
 ↓
Model Compatibility
 ↓
Execution Governor
 ↓
Biometric Verification
 ↓
VerificationDecision
 ↓
AttendanceService
 ↓
Atomic Room Transaction
 ├── Attendance Record
 └── Aegis Outbox
 ↓
Cloud Synchronization
 ↓
Web Dashboard
```

The hardware architecture specifically avoids chipset-specific application branches and instead uses normalized capabilities plus runtime profiling and backend selection.  

**This is the architecture to freeze. The next step is implementation and validation, not another redesign.**
