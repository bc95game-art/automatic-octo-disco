# APK Injector Pro v2.0

برنامه تزریق پروژه به فایل APK برای اندروید — کاملاً آفلاین، بدون نیاز به اینترنت.

---

## پاسخ به سوالات رایج

### آیا می‌تواند APK را استخراج و بازسازی کند؟
**بله** — موتور تزریق:
1. همه فایل‌های APK را از فرمت ZIP می‌خواند (APK همان ZIP است)
2. فایل‌های پروژه تزریق را روی آن‌ها اعمال می‌کند
3. APK جدید را با همان ساختار فشرده‌سازی اصلی بازنویسی می‌کند

> ⚠️ **نکته مهم**: خروجی یک APK **بدون امضا (unsigned)** است.
> برای نصب باید با `apksigner` یا ابزار مشابه امضا شود، یا از نسخه **debug** در GitHub استفاده کنید که به‌صورت خودکار امضای دیباگ می‌گیرد.

### آیا پوشه‌های اضافی در ZIP تزریق را مدیریت می‌کند؟
**بله** — الگوریتم `detectTopFolderRobust`:
- اگر همه فایل‌های ZIP یک پوشه مشترک داشتند (مثل `myproject/`) → آن را strip می‌کند
- اگر فایل root-level وجود داشت → هیچ چیزی strip نمی‌شود (امن)
- اگر چند پوشه سطح اول داشت → به‌عنوان چند پروژه در نظر گرفته می‌شود

---

## ساختار پروژه تزریق پشتیبانی‌شده

```
my-injection.zip
└── myproject/            ← (اختیاری) پوشه اصلی — خودکار strip می‌شود
    ├── injector/
    │   ├── MainActivity.java      → assets/src/MainActivity.java
    │   ├── AndroidManifest.xml    → AndroidManifest.xml
    │   ├── inject_to_apk.sh       → نادیده گرفته می‌شود
    │   └── settings.xml           → res/xml/settings.xml
    ├── res/
    │   ├── layout/                → res/layout/ (همان‌طور)
    │   └── values/                → res/values/ (همان‌طور)
    └── assets/                    → assets/ (همان‌طور)
```

## رمایندر هوشمند مسیر (Smart Path Remapping)

| نوع فایل | مسیر هدف در APK |
|---------|----------------|
| `AndroidManifest.xml` | ریشه APK |
| `*.smali` | `smali/` + مسیر اصلی |
| `*.java`, `*.kt` | `assets/src/` + مسیر |
| `*.dex` | `classes2.dex`, `classes3.dex`, ... |
| `res/layout/*.xml` | `res/layout/` |
| `res/values/*.xml` | `res/values/` |
| `res/drawable/*.png` | `res/drawable/` |
| `*.so` | `lib/<abi>/` (با تشخیص ABI) |
| `*.ttf`, `*.otf` | `res/font/` |
| `assets/...` | `assets/` |
| فایل‌های shell (`.sh`, `.bat`) | نادیده گرفته می‌شوند |

---

## ساخت APK از GitHub

### روش ۱: GitHub Actions (توصیه‌شده)

1. پروژه را در GitHub آپلود کنید
2. به تب **Actions** بروید
3. `Build APK Injector Pro` را اجرا کنید (یا push کنید تا خودکار اجرا شود)
4. از **Artifacts** یا **Releases** فایل APK را دانلود کنید

#### دو فایل تولید می‌شود:
- **`*-debug.apk`** → برای نصب مستقیم (امضای دیباگ دارد) ✅
- **`*-release-unsigned.apk`** → برای production (باید امضا شود)

### روش ۲: ساخت محلی

```bash
# نیاز: Java 17, Android SDK
chmod +x gradlew
./gradlew assembleDebug       # نسخه debug (قابل نصب)
./gradlew assembleRelease     # نسخه release (unsigned)
```

---

## مجوزهای مورد نیاز

| مجوز | دلیل |
|------|------|
| `READ_EXTERNAL_STORAGE` | خواندن فایل APK/ZIP (Android ≤12) |
| `WRITE_EXTERNAL_STORAGE` | ذخیره APK در Downloads (Android ≤9) |
| `MANAGE_EXTERNAL_STORAGE` | دسترسی کامل (Android 11+) |
| `REQUEST_INSTALL_PACKAGES` | نصب APK مستقیم |

---

## نکات فنی

- APK فرمت ZIP است — موتور از `ZipInputStream`/`ZipOutputStream` جاوا استفاده می‌کند
- فایل‌های `STORED` (مثل `classes.dex` و `resources.arsc`) بدون فشرده‌سازی با CRC32 درست ذخیره می‌شوند
- پردازش در thread جداگانه — UI هیچ‌وقت فریز نمی‌شود
- `JsBridge` به‌صورت static با `WeakReference` — بدون memory leak
- تمام دسترسی به UI از `runOnUiThread()` — بدون crash
