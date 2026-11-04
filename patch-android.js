const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const pkgDir = path.join(root, 'android/app/src/main/java/com/rst/astralauncher');
fs.mkdirSync(pkgDir, { recursive: true });
for (const f of fs.readdirSync(path.join(root, 'native'))) fs.copyFileSync(path.join(root, 'native', f), path.join(pkgDir, f));

const mf = path.join(root, 'android/app/src/main/AndroidManifest.xml');
let x = fs.readFileSync(mf, 'utf8');
if (!x.includes('MediaListener')) {
  x = x.replace('<application', `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.WRITE_SETTINGS" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <application`);
  x = x.replace('android:name=".MainActivity"', 'android:name=".MainActivity"\n            android:screenOrientation="portrait"');
  x = x.replace('<category android:name="android.intent.category.LAUNCHER" />',
    '<category android:name="android.intent.category.LAUNCHER" />\n                <category android:name="android.intent.category.HOME" />\n                <category android:name="android.intent.category.DEFAULT" />');
  x = x.replace('</application>', `    <receiver android:name=".BootReceiver" android:exported="true" android:directBootAware="false">
            <intent-filter android:priority="999">
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                <action android:name="com.htc.intent.action.QUICKBOOT_POWERON" />
                <action android:name="com.fyt.boot.ACCON" />
                <action android:name="autochips.intent.action.QB_POWERON" />
                <action android:name="com.microntek.bootcheck" />
                <action android:name="com.ts.main.uiaccon" />
                <action android:name="android.intent.action.ACC_ON" />
            </intent-filter>
        </receiver>
        <service android:name=".TaskbarService" android:exported="false" android:foregroundServiceType="specialUse">
            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Barre des taches flottante du launcher" />
        </service>
        <service
            android:name=".MediaListener"
            android:exported="true"
            android:label="Astra Launcher"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
    </application>`);
  fs.writeFileSync(mf, x);
}
console.log('patch OK');
