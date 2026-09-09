# Lenta

[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-lightgrey.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2013%2B%20%28API%2033%2B%29-green.svg)](app/build.gradle.kts)
[![Website](https://img.shields.io/badge/Website-GitHub%20Pages-blue.svg)](https://lvsovoy.github.io/lenta/)
[![Privacy Policy](https://img.shields.io/badge/Privacy%20Policy-View-blue.svg)](https://lvsovoy.github.io/lenta/privacy.html)
[![Terms of Service](https://img.shields.io/badge/Terms%20of%20Service-View-blue.svg)](https://lvsovoy.github.io/lenta/terms.html)

Lenta is a fast, open-source media viewer and cloud storage browser for Android. It is designed with a privacy-first, client-only architecture with full Material 3 dynamic theming.

**Live Website & Hosted Documents:** [https://lvsovoy.github.io/lenta/](https://lvsovoy.github.io/lenta/)

---

## Legal & Compliance Documents

- **[Website & Landing Page](https://lvsovoy.github.io/lenta/)** (`https://lvsovoy.github.io/lenta/`)
- **[Privacy Policy (HTML)](https://lvsovoy.github.io/lenta/privacy.html)** / **[Markdown Source](PRIVACY_POLICY.md)**
- **[Terms of Service (HTML)](https://lvsovoy.github.io/lenta/terms.html)** / **[Markdown Source](TERMS_OF_SERVICE.md)**
- **[License (CC BY-NC-SA 4.0)](LICENSE)**

---

## OAuth Developer Console Setup & URL Mapping

When setting up custom developer applications for **Google Drive** and **Microsoft OneDrive** in developer portals, use the following standardized field mappings:

### Developer Console Field Mapping

| Field | Google Cloud Console | Microsoft Entra Admin Center | Value to Enter |
| :--- | :--- | :--- | :--- |
| **Application Name** | App name | Name | `Lenta` |
| **App Homepage URL** | Application home page | Home page URL | `https://lvsovoy.github.io/lenta/` |
| **Privacy Policy URL** | Application privacy policy link | Privacy statement URL | `https://lvsovoy.github.io/lenta/privacy.html` |
| **Terms of Service URL** | Application terms of service link | Terms of service URL | `https://lvsovoy.github.io/lenta/terms.html` |
| **Support Email** | User support email | Support contact | Developer contact email |
| **Authorized Domains** | Authorized domains | Domain publisher | `github.io` |

---

### GitHub Pages & Google Search Console Verification

To verify ownership of the GitHub Pages domain (`https://lvsovoy.github.io/lenta/`) for Google OAuth Consent Screen verification:

1. **Enable GitHub Pages:**
   - In the repository, go to **Settings > Pages**.
   - Under **Build and deployment > Source**, select **Deploy from a branch**.
   - Set Branch: `master`, Folder: `/ (root)`, and click **Save**.
2. **Verify in Google Search Console:**
   - Open [Google Search Console](https://search.google.com/search-console).
   - Click **Add Property** > Choose **URL prefix** > Enter `https://lvsovoy.github.io/lenta/` (or `https://lvsovoy.github.io/`).
   - Select the **HTML tag** method. The verification tag `<meta name="google-site-verification" content="ZtHjuldc7rMnnBgxdYXIUR3T93fmiuFQ7Y9afFkG1KY" />` is already embedded in `index.html`.
   - Click **Verify**.
3. **Configure Google Cloud OAuth Authorized Domains:**
   - In Google Cloud Console > **APIs & Services > OAuth consent screen**, add `github.io` to **Authorized domains**.
   - Enter `https://lvsovoy.github.io/lenta/` as the Home page, `https://lvsovoy.github.io/lenta/privacy.html` as the Privacy Policy, and `https://lvsovoy.github.io/lenta/terms.html` as the Terms of Service.

---

### 1. Google Cloud Console (Google Drive API) Setup

1. **Create Project**: Go to [Google Cloud Console](https://console.cloud.google.com/) and create a new project (e.g. `Lenta Cloud Storage`).
2. **Enable API**: Navigate to **APIs & Services > Library**, search for **Google Drive API**, and click **Enable**.
3. **OAuth Consent Screen**:
   - User Type: **External**
   - App Name: `Lenta`
   - Scopes:
     - `https://www.googleapis.com/auth/drive.readonly`
     - `https://www.googleapis.com/auth/drive.file`
     - `https://www.googleapis.com/auth/userinfo.email`
     - `https://www.googleapis.com/auth/userinfo.profile`
   - **Test Users**: Add your Google Account email address under the Test Users tab while the app is in testing mode.
4. **Create Credentials**:
   - Go to **APIs & Services > Credentials > Create Credentials > OAuth client ID**.
   - Application Type: **Web application** (or Android).
   - Authorized Redirect URIs:
     - `me.lesovoy.lenta://oauth2callback`
     - `lenta://oauth2callback`
5. **Configure Lenta**: Paste the Client ID in `OAuthConfig.kt` under `DEFAULT_GOOGLE`.

---

### 2. Microsoft Entra (OneDrive) Setup

1. **Register Application**: Go to [Microsoft Entra Admin Center](https://entra.microsoft.com/) > **Applications > App registrations > New registration**.
2. **Account Types**: Select **Accounts in any organizational directory (Any Microsoft Entra ID tenant - Multitenant) and personal Microsoft accounts (e.g. Skype, Xbox)**.
3. **Redirect URI**:
   - Platform: **Public client/native (mobile & desktop)**
   - URI: `me.lesovoy.lenta://oauth2callback`
4. **Authentication Configuration**:
   - Under **Platform configurations**, ensure `me.lesovoy.lenta://oauth2callback` and `https://login.microsoftonline.com/common/oauth2/nativeclient` are listed.
   - Under **Implicit grant and hybrid flows**, enable **Access tokens** and **ID tokens**.
   - Under **Advanced settings**, enable **Allow public client flows**: **Yes**.
5. **API Permissions (Microsoft Graph)**:
   - Delegated: `Files.Read`, `Files.Read.All`, `User.Read`, `offline_access`, `openid`, `profile`, `email`.
6. **Configure Lenta**: Copy the **Application (client) ID** into `OAuthConfig.kt` under `DEFAULT_ONEDRIVE`.

---

## Building & Verification

To verify and test the project locally:

```bash
# Run unit test suite
./gradlew test

# Build debug APK
./gradlew assembleDebug

# Check keystore signing certificate fingerprints (SHA-1)
./gradlew signingReport
```
