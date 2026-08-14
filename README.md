This app provides a Speech-to-text feature for non-touch flip phones. (Kyocera E4610, and E4810 have been tested.) Once installed properly: with the cursor in a text field, you can simply hold a button of your choice while talking, then when you let go, it transcribes it, then types it in.
Instructions for installing on Kyocera E4610:
1.	Install TurboVoice by sideloading the APK or install directly from Android studio.
2.	It should open right away with a page of setting’s to set.
3.	Click on the “accessibility” button, scroll to “TurboVoice”, select, hit down key, then hit up key, then hit OK. Scroll down and click on the OK button.
4.	Back out to the main menu again, click on “overlay settings” scroll down and click on “Special access”, select “draw over other apps”, select “turbovoice” click up, then click back down, to select “permit drawing”, ensure that it now says “ON”.
5.	Grant microphone and storage access permissions.
6.	Optional: you can grant SMS permission, and set the “provisioning phone number” if you want to be able to set the API key remotely. (See below for more details)
7.	Set the trigger key. Press the button that you want to push to start the transcribing, it will automatically remember the key, and return to the Main menu.

 	This app is using Groq’s API Whisper Speetch-to-text service. You will need a Groq API key to make it work. There are two methods for entering the API key into the app.
1.	Copy and paste the key into the text file found in: Internal storage/Turbo key/ (you may need to restart the phone for this to show up.)
2.	You can set the key remotely by sending it from a Phone number that matches what you enter in step 6. Above. The API key needs encoded using Base64 format (base64encode.org) then prefixed like follows: TURBOVOICE_SETUP:put your encoded api key here. Send this encoded and prefixed text to the phone that you are setting up, TuboVoice will read it and the Speech-to-text should start working.
