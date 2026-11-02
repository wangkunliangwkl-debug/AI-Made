import io

p = '/root/build_rcb/ReverseChargingBypass/app/src/main/AndroidManifest.xml'
s = io.open(p, encoding='utf-8').read()

act = (
    '        <activity\n'
    '            android:name=".RcbPanelActivity"\n'
    '            android:exported="true"\n'
    '            android:excludeFromRecents="true"\n'
    '            android:noHistory="true"\n'
    '            android:taskAffinity=""\n'
    '            android:launchMode="singleTop"\n'
    '            android:label="\u53cd\u5411\u5145\u7535\u9608\u503c"\n'
    '            android:theme="@android:style/Theme.DeviceDefault.Dialog.NoActionBar">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.service.quicksettings.action.QS_TILE_PREFERENCES" />\n'
    '                <category android:name="android.intent.category.DEFAULT" />\n'
    '            </intent-filter>\n'
    '        </activity>\n'
    '\n'
)

md = (
    '            <meta-data\n'
    '                android:name="android.service.quicksettings.SETTINGS_ACTIVITY"\n'
    '                android:value="com.rcb.bypass.RcbPanelActivity" />\n'
)

if 'RcbPanelActivity' not in s:
    s = s.replace('        <!-- v1.5', act + '        <!-- v1.5', 1)

if 'SETTINGS_ACTIVITY' not in s:
    s = s.replace('        </service>', md + '        </service>', 1)

io.open(p, 'w', encoding='utf-8').write(s)
print('patched')
