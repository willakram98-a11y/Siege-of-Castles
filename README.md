# حصار القلاع — تطبيق أندرويد

لعبة «حصار القلاع» مغلّفة كتطبيق أندرويد أصلي يعمل بملء الشاشة.

## التثبيت على الهاتف
1. نزّل الملف [`release/SiegeOfCastles.apk`](release/SiegeOfCastles.apk) على هاتفك.
2. افتحه، وإذا طُلب منك فاسمح بـ«التثبيت من مصادر غير معروفة».
3. اضغط «تثبيت».

يعمل على أندرويد 8.0 فما فوق.

## تعديل اللعبة
اللعبة كلها في ملف واحد: `app/src/main/assets/index.html`.
استبدله بالنسخة الجديدة ثم ابنِ التطبيق:

```
./gradlew assembleRelease
```

الملف الناتج: `app/build/outputs/apk/release/app-release.apk`.
انسخه إلى `release/SiegeOfCastles.apk` حتى تبقى النسخة الجاهزة مطابقة للعبة.
عند كل رفع إلى GitHub يُبنى التطبيق تلقائياً (تبويب Actions).

## التوقيع
بدون إعداد إضافي يُوقَّع التطبيق بمفتاح تجريبي (debug) يختلف من جهاز بناء لآخر،
لذلك قد يرفض الهاتف تثبيت نسخة جديدة فوق القديمة؛ احذف القديمة أولاً.

لتوقيع ثابت (وللنشر على Google Play) أنشئ مفتاحاً خاصاً:
- على GitHub: أضف الأسرار `KEYSTORE_BASE64` (المفتاح بترميز base64) و`KEYSTORE_PASSWORD`
  و`KEY_ALIAS` و`KEY_PASSWORD` في Settings ← Secrets and variables ← Actions.
- على جهازك: ضع بياناته في ملف `keystore.properties` بجانب `settings.gradle`
  (هذا الملف لا يُرفع إلى GitHub):

```
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

## ملاحظات
- وضع «لاعبان على هاتفين» يحتاج الإنترنت والكاميرا (لمسح رمز QR)، ويطلب التطبيق إذن الكاميرا عند أول استخدام.
