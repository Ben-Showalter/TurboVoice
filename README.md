This app provides a Speech-to-text feature for non-touch flip phones. (Kyocera and Sonim phones have been tested, but not all models are guaranteed to work.) Once installed properly: with the cursor in a text field, you can simply hold a button of your choice while talking, then when you let go, it transcribes it, then types it in.
Instructions for installing on Kyocera E4610:
1.	Install TurboVoice by sideloading the APK or install directly from Android studio.
2.	It should open right away with a page of setting’s to set.
3.	Click on the “accessibility” button, scroll to “TurboVoice”, select, hit down key, then hit up key, then hit OK. Scroll down and click on the OK button.
4.	Back out to the main menu again, click on “overlay settings” scroll down and click on “Special access”, select “draw over other apps”, select “turbovoice” click up, then click back down, to select “permit drawing”, ensure that it now says “ON”.
5.	Grant microphone and storage access permissions.
6.	Optional: you can grant SMS permission, and set the “provisioning phone number” if you want to be able to set the API key remotely. (See below for more details)
7.	Set the trigger key. Press the button that you want to push to start the transcribing, it will automatically remember the key, and return to the Main menu.

 	This app is using Groq’s API Whisper Speech-to-text service. You will need a Groq API key to make it work. There are two methods for entering the API key into the app.
1.	Copy and paste the key into the text file found in: Internal storage/Turbo key/ (you may need to restart the phone for this to show up.)
2.	You can set the key remotely by sending it from a Phone number that matches what you enter in step 6. Above. The API key needs encoded using Base64 format (base64encode.org) then prefixed like follows: TURBOVOICE_SETUP:put your encoded api key here. Send this encoded and prefixed text to the phone that you are setting up, TuboVoice will read it and the Speech-to-text should start working.

## In-app updates

TurboVoice updates itself from this repo's GitHub Releases, the same way FlipLaunch does. Each time you open the TurboVoice app, it checks about once a week and asks whether to install a newer version. The "Check for Updates" button at the bottom of the main screen checks right away. Wi-Fi is preferred for the download. The first time you install an update, Android asks you to allow "Install unknown apps" for TurboVoice.

## Publishing a release

Installed copies read `https://api.github.com/repos/Ben-Showalter/TurboVoice/releases/latest` (see `UpdateChecker.kt`). Releases are published by hand: no CI is involved, and the signing key never leaves your machine.

### One-time: create the release signing key

```bash
keytool -genkeypair -v -keystore ~/turbovoice-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias turbovoice
```

Then create `keystore.properties` at the repo root. It's gitignored, so never commit it:

```properties
storeFile=/absolute/path/to/turbovoice-release.jks
storePassword=...
keyAlias=turbovoice
keyPassword=...
```

**Back up the `.jks` file and its passwords.** Android installs an update only if it's signed with the same key as the installed app. If you lose the key, every user has to uninstall and reinstall. For the same reason, a phone running a debug build (such as an install straight from Android Studio, or the original `TurboVoice.apk`) has to uninstall it once and install a release APK before in-app updates work on it. Uninstalling clears TurboVoice's settings (trigger key, trusted number), but the API key file in Internal storage stays.

### Each release

1. In `app/build.gradle`, raise `versionCode` by 1 and set `versionName`, e.g. `"1.1"`. Then commit.
2. Build the signed APK. In Android Studio, switch the build variant to `release` and use **Build -> Build APK(s)**. From a terminal, run:
   ```bash
   gradle :app:assembleRelease
   # -> app/build/outputs/apk/release/app-release.apk
   ```
3. On GitHub, go to **Releases -> Draft a new release**. Create the tag `v<versionName>` (e.g. `v1.1`), attach `app-release.apk`, and click **Publish release**. Don't mark it as a draft or pre-release, because `releases/latest` skips those. The app compares that tag with its own `versionName`.

The repo has to stay public: the app reads releases without logging in.
