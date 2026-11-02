#!/bin/bash
# v1.3 手工打包脚本：aapt2 + kotlinc(embeddable) + d8 + zipalign + apksigner
set -u

SRC=/root/build_rcb/ReverseChargingBypass/app/src/main
WORK=/root/.rcb_build
OUT=/root/.rcb_out
rm -rf $WORK $OUT
mkdir -p $WORK/res $WORK/classes $WORK/dex $OUT

SDK=/root/sdk
BT=$SDK/build-tools/35.0.0
ANDROID_JAR=$SDK/platforms/android-35/android.jar
Q="qemu-x86_64-static -L /usr/x86_64-linux-gnu"

KC=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/79346ed53db48b18312a472602eb5c057070c54d/kotlin-compiler-embeddable-2.0.21.jar
KS=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/618b539767b4899b4660a83006e052b63f1db551/kotlin-stdlib-2.0.21.jar
KR=/tmp/gradle-8.9/lib/kotlin-reflect-1.9.23.jar
KSCR=/tmp/gradle-8.9/lib/kotlin-script-runtime-1.9.23.jar
KPD=/tmp/gradle-8.9/lib/kotlin-daemon-embeddable-1.9.23.jar
ANN=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar
XPAPI=/root/libs/api-82.jar
COR=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4/2c997cd1c0ef33f3e751d3831929aeff1390cb30/kotlinx-coroutines-core-jvm-1.6.4.jar
TROVE=/tmp/gradle-8.9/lib/trove4j-1.0.20200330.jar

echo "===== [1/6] aapt2 compile ====="
$Q $BT/aapt2 compile --dir $SRC/res -o $WORK/res/compiled.zip || exit 1

echo "===== [2/6] aapt2 link ====="
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.rcb.bypass">|' \
  $SRC/AndroidManifest.xml > $WORK/AndroidManifest.xml
grep -n '<manifest' $WORK/AndroidManifest.xml
$Q $BT/aapt2 link \
  -o $WORK/base.apk \
  -I $ANDROID_JAR \
  --manifest $WORK/AndroidManifest.xml \
  -A $SRC/assets \
  --java $WORK/gen \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 4 \
  --version-name "1.3" \
  $WORK/res/compiled.zip || exit 1

echo "===== [3/6] kotlinc ====="
java -cp "$KC:$KS:$KR:$KSCR:$KPD:$ANN:$COR:$TROVE" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn \
  -classpath "$ANDROID_JAR:$XPAPI:$KS" \
  -d $WORK/classes \
  $SRC/java/com/rcb/bypass/MainHook.kt \
  $SRC/java/com/rcb/bypass/SettingsActivity.kt || exit 1

echo "classes produced:"
find $WORK/classes -name '*.class' | head -20

echo "===== [4/6] d8 ====="
CLASSFILES=$(find $WORK/classes -name '*.class')
bash $BT/d8 --release --min-api 26 --lib $ANDROID_JAR --output $WORK/dex $CLASSFILES $KS || exit 1
echo "dex produced:"; ls -la $WORK/dex/

echo "===== [5/6] package ====="
cd $WORK && cp base.apk unsigned.apk && cd $WORK/dex && zip -q -X ../unsigned.apk classes.dex && cd $WORK
unzip -l unsigned.apk | tail -12

echo "===== [6/6] zipalign + sign ====="
$Q $BT/zipalign -f -p 4 $WORK/unsigned.apk $OUT/rcb-v1.3-unsigned-aligned.apk || exit 1
java -jar $BT/lib/apksigner.jar sign \
  --ks /root/libs/rcb.keystore \
  --ks-type PKCS12 \
  --ks-key-alias rcb \
  --ks-pass pass:rcbpass123 \
  --key-pass pass:rcbpass123 \
  --min-sdk-version 26 \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out $OUT/rcb-v1.3.apk \
  $OUT/rcb-v1.3-unsigned-aligned.apk || exit 1

echo "===== verify ====="
java -jar $BT/lib/apksigner.jar verify --verbose --print-certs $OUT/rcb-v1.3.apk 2>&1 | head -20
ls -la $OUT/rcb-v1.3.apk
md5sum $OUT/rcb-v1.3.apk
echo "DONE"
