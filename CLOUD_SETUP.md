# Cloud sign-in setup

Users sign into their own Google Drive or OneDrive account. They do not create OAuth registrations or enter developer credentials. The project owner registers the application once, then includes the public client IDs in the release build.

Devices must first join the same mesh and exchange folder keys. They can then push and pull files through the same cloud account without being on the same network. New folders and membership changes still need mesh metadata/key exchange before cloud file transfer can work for those folders. This feature does not create a public relay server or share one user's cloud account with another automatically.

## Google

1. Create a Google Cloud project, enable the Google Drive API, and configure the OAuth consent screen. Use the same project for desktop and Android registrations so the Drive application identity is consistent.
2. Register a Desktop app OAuth client. Set `SYNCDROID_GOOGLE_CLIENT_ID` in `shared/sync-core/src/main/resources/cloud-oauth.properties`. The desktop flow uses PKCE and an ephemeral IPv4 loopback redirect (`http://127.0.0.1:<port>`).
3. Register an Android OAuth client for package `com.syncdroid.app` and the permanent Android release signing certificate SHA-1. Set its public ID as `SYNCDROID_GOOGLE_ANDROID_CLIENT_ID` in that resource. Debug builds need a matching debug-certificate registration. Android uses the Google Play services authorization API; a supported Google Play services installation is required for Google Drive sign-in.
4. Request only `https://www.googleapis.com/auth/drive.file`. Add test users while the consent screen is in testing. Complete the provider's required publication/verification steps before general distribution.

## Microsoft

1. Create a Microsoft Entra app registration allowing the intended accounts, including personal Microsoft accounts for consumer OneDrive support.
2. Configure a public/native client, delegated `Files.ReadWrite` and `offline_access`, and the supported mobile/desktop redirect URIs:
   - Desktop: `http://localhost` (ephemeral ports are used).
   - Android browser flow: `com.syncdroid.app:/oauth2redirect/microsoft`.
3. Set the public Application (client) ID as `SYNCDROID_MICROSOFT_CLIENT_ID` in the same resource file. No client secret is embedded. Android uses AppAuth with PKCE; desktop uses the loopback PKCE flow.

See [Google desktop authorization](https://developers.google.com/identity/protocols/oauth2/native-app), [Google Android authorization](https://developer.android.com/identity/authorization), and [Microsoft redirect URI configuration](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url).

## Behavior and validation

- Automatic cloud work is admitted only on registered Wi-Fi. Elsewhere, including mobile data, users choose **Sync cloud now** to pull remote changes and push local changes.
- Folder scope remains Off, selected folders, or all configured folders. Both connected providers receive enabled folders.
- Cloud file operations wait for admitted local file transfers. Downloads are verified before replacement, and conflicting versions require review.
- New cloud manifests use format 3 with publisher-owned file objects, allowing one device to clean its obsolete uploads without removing another publisher's files. All cloud participants should run this version or later. Format 2 manifests remain readable; legacy shared objects are not automatically removed.
- Superseded publisher-owned files get a 30-day grace period from when they are first observed as unused, then are moved to provider trash/recycle bin. Provider trash retention and quota behavior apply. Losing local retention state restarts the grace period.
- Existing folder keys are retained when devices converge on a common key, preserving decryption of older uploads.

Before enabling sign-in in a public release, test both providers with two real accounts/devices as appropriate: sign-in, cancellation, token renewal, disconnection, upload, download, conflict handling, SD-card permission loss, interrupted transfers, and background versus manual requests. Automated tests use isolated stores and local HTTP servers; they cannot establish that an unregistered provider app accepts sign-ins.
