# ⚙️ معماری خط لوله‌های GitHub Actions (CI/CD Architecture)

مخزن کالینان مجهز به یک سیستم خودکارسازی بیلد، تست و توزیع صنعتی با اصل حداقل دسترسی (Least Privilege) و اجرای بهینه ابری است.

---

## ۱. پایپ‌لاین خودکار واحد (`.github/workflows/ci.yml`)

برای هر کامیت، پوش (Push به غیر از تگ‌های `v*`) یا پول‌ریکوئست، **تنها یک وورک‌فلو واحد (`CI Workflow`)** اجرا می‌شود:
* **عنوان پویا در گیت‌هاب:** `Build #{run_number} - {commit_message | pr_title | branch}`

این وورک‌فلو دارای **۳ جاب تفکیک‌شده** با ساختار زیر است:

```text
               ┌────────────────────────────────────────────────────────┐
               │       Build #{run_number} - {commit_message ...}       │
               └───────────┬────────────────────────────────┬───────────┘
                           │                                │
                           ▼                                ▼
              ┌─────────────────────────┐      ┌─────────────────────────┐
              │   Job 1: 🧪 Unit Tests  │      │ 📚 Job 3: Sync to Wiki  │
              └────────────┬────────────┘      └─────────────────────────┘
                           │
                           ▼ (needs: [test])
              ┌─────────────────────────┐
              │   Job 2: 📦 Build APK   │
              └─────────────────────────┘
```

### شرح وظایف هر جاب:
1. **جاب ۱: آزمون‌های کیفیت و تست‌های واحد (`test` — پیش‌شرط بیلد):**
   - اجرا با **JDK 21 (Temurin)** و حافظه بهینه `GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx4g -Dorg.gradle.workers.max=4"`.
   - اجرای تسک `./gradlew testDebugUnitTest --no-daemon --stacktrace` (شامل ۲۷ سوئیت تست واحد، `MockWebServer` و Robolectric/Compose).
   - **تست‌های آفلاین Robolectric:** در `app/build.gradle.kts` تسک `prepareRobolectricSdks` به‌همراه `RobolectricSdkArgumentProvider` فایل‌های SDK اندروید ۱۴ Robolectric را پیش از اجرا آماده کرده و با `-Drobolectric.offline=true` از وابستگی به دانلود زمان اجرا در CI جلوگیری می‌کند.
   - تحلیل نتایج با اسکریپت `python3 scripts/parse_test_results.py` و درج جدول آماری در **Step Summary** + آپلود گزارش‌های HTML/XML با شرط `if: always()`.
2. **جاب ۲: کامپایل فایل نصبی دیباگ (`build`):**
   - به صورت متوالی و **صرفاً در صورت قبولی کامل تست‌ها** اجرا می‌شود (`needs: [test]`).
   - اعتبارسنجی کلید دائمی دیباگ (`DEBUG_KEYSTORE_BASE64` یا `debug.keystore.base64`) با `keytool` برای سازگاری آپدیت‌های دیباگ.
   - کامپایل `./gradlew assembleDebug -PversionCode=$BUILD_NUMBER -PversionName=$CLEAN_VERSION` بر اساس آخرین تگ گیت و شماره بیلد.
   - نام‌گذاری معنایی خروجی: `Kalinan-build-{run_number}-debug-{branch}-{short_sha}.apk`.
   - انتشار فایل مستقیم (بدون زیپ) در پیش‌انتشار غلتان **`debug-build`** و آپلود **Artifacts** با ماندگاری ۱۴ روز به همراه هش SHA-256 در **Step Summary**.
3. **جاب ۳: همگام‌سازی خودکار مستندات با ویکی گیت‌هاب (`wiki-sync`):**
   - به صورت موازی و مستقل درون همین وورک‌فلو (`ci.yml`) اجرا می‌شود.
   - کلیه فایل‌های Markdown مسیر `/docs` (و در صورت وجود `docs/images`) را به مخزن ویکی گیت‌هاب (`.wiki.git`) منتقل کرده و `docs/README.md` را به عنوان صفحه اصلی `Home.md` تنظیم می‌کند.
   - در صورت عدم فعال‌سازی اولیه ویکی توسط کاربر، بدون قرمز کردن وورک‌فلو راهنمای شفاف درج می‌کند.

---

## ۲. پایپ‌لاین توزیع و انتشار رسمی (`.github/workflows/release.yml`)

پایپ‌لاین انتشار ریلیز رسمی (`Release & Distribution`) **صرفاً به دو روش کنترل‌شده و ایمن** فعال می‌شود:
1. پوش کردن تگ نسخه با پیشوند استاندارد `v` (مانند `git push origin v1.0.1`).
2. اجرای دستی از طریق منوی `Actions` -> `Release & Distribution` با زدن دکمه `Run workflow` (و امکان وارد کردن `custom_release_tag`).

### ویژگی‌های کلیدی فرآیند انتشار رسمی:
1. **اجرای الزامی تست‌های واحد (`verify`):** جاب `build-and-release` دارای وابستگی `needs: [verify]` است؛ انتشار نسخه معیوب در صورت شکست حتی یک تست مسدود می‌شود.
2. **اعتبارسنجی سکرت‌ها و الیاس پویا:** کلیدهای `RELEASE_KEYSTORE_BASE64`، `RELEASE_STORE_PASSWORD`، `RELEASE_KEY_PASSWORD` و `RELEASE_KEY_ALIAS` از سکرت‌های مخزن خوانده شده و هیچ مقداری هاردکد نیست.
3. **کامپایل دوگانه فایل‌های خروجی (`assembleRelease bundleRelease`):**
   - فایل مستقل و بدون زیپ برای نصب مستقیم: `Kalinan-{tag}-release.apk`
   - بسته استاندارد فروشگاه‌های نرم‌افزاری: `Kalinan-{tag}-AppBundle.aab`
4. **تأیید گواهی با `apksigner` و تولید متادیتا:** استخراج اثرانگشت SHA-256 گواهی امضا، ثبت در `SHA256SUMS.txt` و `release-metadata.json`.
5. **امحای امن کلید موقت (`shred -u`):** حذف غیرقابل بازیابی فایل کلید موقت از رانر (`if: always()`) بلافاصله پس از اتمام امضا.
6. **پیوست خودکار به Release و آپدیت درون‌برنامه‌ای:**
   - متن توضیحات رسمی شامل بخش اختصاصی **`### 📝 تغییرات این نسخه`** در صفحه ریلیز درج می‌شود تا توسط `UpdateRepository` در کادر آپدیت درون‌برنامه‌ای اپلیکیشن برای دانشجویان نمایش داده شود.
