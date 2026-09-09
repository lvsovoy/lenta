# Privacy Policy for Lenta

**Last updated:** September 9, 2026

Lenta ("we", "our", or "the application") is a free, open-source media viewer and file management application for Android. We are committed to protecting your privacy and ensuring you have complete control over your personal data and files.

This Privacy Policy explains how Lenta handles your information when you use the app, including when you connect third-party storage services such as **Google Drive**, **Microsoft OneDrive**, and **Nextcloud**.

---

### 1. Summary: Privacy by Design & Local Processing
- **No Developer Servers:** Lenta is a client-side Android application. We do not operate any backend servers, analytics services, or user databases.
- **No Data Collection or Selling:** We do not collect, track, store, sell, or share your personal information, browsing history, or media files.
- **Direct Communication:** When connecting to cloud storage providers (Google Drive, Microsoft OneDrive, Nextcloud), Lenta communicates directly and securely with the official API endpoints of those providers over encrypted HTTPS connections.

---

### 2. Information Accessed and Handled by Lenta

#### A. Local Storage & Device Files
- **Permissions:** Storage access permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`).
- **Usage:** Used solely to discover, display, and play local media files and generate fast thumbnail previews on your device.

#### B. Google Drive (Google OAuth 2.0)
When you choose to connect Google Drive, Lenta requests access via Google OAuth 2.0 with the following scopes:
- `.../auth/drive.readonly` or `.../auth/drive.file`: To list your Drive folders, download requested media files for local playback, and generate preview thumbnails.
- `.../auth/userinfo.email` and `.../auth/userinfo.profile`: To retrieve and display your account name/email as a label in the app's storage sources list.

**Google API Services User Data Policy & Limited Use Disclosure:**
> **Lenta's use and transfer to any other app of information received from Google APIs will adhere to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements.**
>
> Specifically:
> - Data received from Google APIs is used strictly to provide user-facing media browsing and playback features within Lenta.
> - Data received from Google APIs is never transferred, shared, or disclosed to third parties, advertising platforms, data brokers, or external servers.
> - Data received from Google APIs is never used for serving advertisements, retargeting, or training machine learning / artificial intelligence models.

#### C. Microsoft OneDrive (Microsoft Entra / Microsoft Graph)
When you connect Microsoft OneDrive, Lenta requests the following delegated permissions:
- `Files.Read` / `Files.Read.All`: To browse folders, stream audio/video, and render document/comic pages.
- `User.Read`, `email`, `profile`: To display your account name in Lenta.
- `offline_access`: To maintain secure session authentication without requiring repeated interactive logins.

All OneDrive data is processed entirely on-device and is never shared with third parties.

---

### 3. Data Storage, Security & Token Management
- **Local Credentials:** OAuth access tokens, refresh tokens, and server credentials are saved exclusively in your device's private, sandboxed application storage.
- **Local Caching:** Thumbnail images and downloaded media files are stored in your device's private app cache directory (`context.cacheDir`).
- **Encryption:** All network communications with Google, Microsoft, and Nextcloud servers use Transport Layer Security (TLS/HTTPS).

---

### 4. Data Retention, Cache Management & Deletion
- **Removing a Storage Source:** You can disconnect Google Drive or OneDrive at any time by deleting the storage source in Lenta. Deleting a source immediately and permanently deletes the associated OAuth tokens and account metadata from your device.
- **Clearing Cache:** You can purge locally cached thumbnails and downloaded online media files at any time via **Settings > Storage & Cache > Clear Cache**.
- **Uninstalling Lenta:** Uninstalling the app removes all locally stored tokens, cache files, and preferences completely.

---

### 5. How to Revoke Access
You can revoke Lenta's access to your cloud accounts at any time through the provider security dashboards:
- **Google:** [Google Account Permissions](https://myaccount.google.com/permissions)
- **Microsoft:** [Microsoft Account Consent Management](https://account.live.com/consent/Manage)

---

### 6. Third-Party Services
Lenta connects with external cloud storage services at your explicit request. Use of these third-party services is subject to their respective privacy policies:
- [Google Privacy Policy](https://policies.google.com/privacy)
- [Microsoft Privacy Statement](https://privacy.microsoft.com/privacystatement)

---

### 7. Changes to This Privacy Policy
We may update this Privacy Policy periodically to reflect new features or regulatory requirements. Any updates will be posted directly to the project repository.

---

### 8. Contact & Open Source Repository
Lenta is an open-source project hosted on GitHub:
- **Repository & Issues:** [https://github.com/lvsovoy/lenta](https://github.com/lvsovoy/lenta)
- **Developer:** lvsovoy
