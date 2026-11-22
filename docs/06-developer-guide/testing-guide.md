# 🧪 راهنمای جامع آزمون‌های نرم‌افزار (Testing & Quality Assurance)

پروژه کالینان دارای مجموعه کاملی از آزمون‌های واحد و شبیه‌سازی‌شده برای تضمین پایداری کلاینت در ارتباط با سرور دانشگاه است. این سند نحوه اجرای آزمون‌ها و استانداردهای افزودن تست‌های جدید را شرح می‌دهد.

---

## ۱. نحوه اجرای آزمون‌ها با خط فرمان

برای اجرای کلیه آزمون‌های واحد و Robolectric/Compose روی JVM محلی:

```bash
# در لینوکس / مک‌اواس
./gradlew testDebugUnitTest

# در ویندوز (PowerShell / CMD)
.\gradlew.bat testDebugUnitTest
```

> **پشتیبانی آفلاین Robolectric:** در فایل `app/build.gradle.kts` تسک `prepareRobolectricSdks` به‌همراه `RobolectricSdkArgumentProvider` پیش‌شرط اجرای تست است و با آرگومان `-Drobolectric.offline=true` فایل SDK اندروید ۱۴ را به‌صورت خودکار در مسیر بیلد آماده می‌کند تا تست‌های رابط کاربری در CI و محیط لوکال بدون نیاز به دانلود زمان اجرا اجرا شوند.

گزارش بصری HTML آزمون‌ها پس از اجرا در مسیر زیر در دسترس است:  
`app/build/reports/tests/testDebugUnitTest/index.html`

---

## ۲. کلاس‌های آزمون موجود (۲۸ سوئیت تست) و حیطه وظایف هر کدام

### الف) رزرواسیون، نظرسنجی اجباری و ناوبری هفته
1. **`SurveyAndPaginationUnitTest.kt`:**
   - تست تشخیص ریدایرکت ۳۰۲ از `/Reservation/Reservation.aspx` به `/Survey/Survey_Answer.aspx`، تقدم `TYPE_SURVEY` بر `TYPE_MESSAGES` با وجود اشتراک `#cphMain_grdContent`، صدور `MandatorySurveyException` و نمایش `SurveyBlockInfo` در `MenuViewModel`.
   - تست کشف و دریافت خودکار صفحات بعدی جدول پیام‌ها (`PagerStyle`) تا ۲۷+ پیام و کلید یکتای ردیف‌ها در لیست.
2. **`ReservationLiveHarUnitTest.kt`:**
   - تست پارس داده‌های واقعی ضبط‌شده در ترافیک زنده سرور دانشگاه (فایل‌های `docs/05-samples-and-fixtures/har_entry*.txt`) برای قیمت‌ها، روزها، غذاها و نیم‌پرس‌ها.
3. **`WeekNavigationUnitTest.kt`:**
   - تست ساخت فرم‌بادی ناوبری هفته (`imgbtnNextWeek`، `imgbtnLastWeek`، `imgbtnCurrentWeek`)، استخراج `#cphMain_alertBoxValue`، فیلتر `ctl00_ScriptManager_HiddenField` و هدر `User-Agent`.
4. **`MenuRepositoryBehaviorTest.kt` و `MenuListModelsUnitTest.kt`:**
   - تست رفتارهای `MenuRepository` (رزرو، لغو، تعویض سلف، خطاهای سرور) و مدل‌های نمایش فهرست غذا (`MenuListModels`) بدون وابستگی به سرور.

### ب) پیام‌های خوانده‌نشده و پروفایل دانشجویی
5. **`UnreadMessagesUnitTest.kt` و `MessageRetrievalUnitTest.kt`:**
   - تست طبقه‌بندی صفحه پیام‌های خوانده‌نشده/خوانده‌شده، کشف کنترل‌های صفحه‌بندی `PagerStyle`، مختصات `.x`/`.y` دکمه تصویری، زنجیره‌سازی ViewState و استخراج متن از `cphMain_pMessage`.
6. **`ProfileFeatureUnitTest.kt` و `ProfileSheetComposeTest.kt`:**
   - تست پارس کامل `MyProfile_page_sanitized.html` توسط `ProfileParser`، طبقه‌بندی `TYPE_PROFILE`، دریافت تصویر `UserImage.ashx` (`image/jpg` + بایت‌های جادویی JPEG) در `ProfileRepository`، پالایش PII و تست رابط کاربری Compose در `ProfileModalSheet` (Robolectric).

### ج) کپچای روی دستگاه، مدیریت ماژول گوگل‌پلی و آزمایشگاه کپچا (Captcha Lab)
7. **`CaptchaPreprocessPipelineUnitTest.kt`، `MlKitCaptchaSolverUnitTest.kt`، `OcrModuleControllerUnitTest.kt` و `AuthViewModelAutoCaptchaUnitTest.kt`:**
   - تست الگوریتم‌های پیش‌پردازش، مسیر اصلی فریم رنگی رمزگشایی‌شده (`useDecodedColorFrame`) و ۲ مسیر جایگزین بدون حذف نویز، اعتبارسنجی ۴ رقمی با `OcrSession` جعلی، لغو Job هنگام «کد جدید»، عدم رونویسی متن کاربر و عدم ورود خودکار.
   - تست چرخه وضعیت `OcrModuleController` (نبود Google Play Services، ماژول نصب‌نشده بدون شروع دانلود خودکار در ورود، دانلود دستی از طریق `ModuleInstallClient` با درصد پیشرفت و نگاشت خطاها) و غیرفعال‌سازی ایمن در `SettingsScreenContent`.
8. **`CaptchaLabCoreUnitTest.kt` و `CaptchaLabComposeTest.kt`:**
   - تست بایگانی محلی آزمایشگاه کپچا، فیلتر `UNLABELED` و شمارنده وضعیت، موتور شبیه‌سازی غیرمخرب `CaptchaSolverSimulationEngine`، و تست‌های Compose صفحه `CaptchaLabScreen` شامل دکمه دانلود دستی و غیرفعال ماندن ابزارهای OCR هنگام آماده نبودن ماژول (Robolectric).

### د) امنیت شبکه، احراز هویت و دسترسی‌پذیری
9. **`MockWebServerSecurityUnitTest.kt` و `LoginSessionIsolationUnitTest.kt`:**
   - تست پاک‌سازی کوکی نشست هنگام خروج، شناسایی انقضای نشست (۳۰۲)، خطاهای ۵۰۰، استریم باینری کپچا، حالت موقت `fallbackClient` و عدم ارسال درخواست پیش از ورود توسط `MessagesViewModel`.
10. **`BiometricSecurityHardeningTest.kt`، `SecurityPreferencesUnitTest.kt` و `LoginAndCopyAccessibilityTest.kt`:**
    - تست قفل بیومتریک، تنظیمات امنیتی محلی در `UserPreferences` و آزمون‌های دسترسی‌پذیری صفحه ورود و کپی اطلاعات (Robolectric/Compose).

### هـ) لاگ‌گیری پالایش‌شده، به‌روزرسانی و پایه‌های پروژه
11. **`LoggingHardeningUnitTest.kt`، `AppLoggerSanitizerUnitTest.kt`، `ComprehensiveLoggingUnitTest.kt` و `LoggingIntegrationTest.kt`:**
    - تست پالایش گذرواژه، کوکی، داده‌های پروفایل و فیلدهای کپچا/OCR در `AppLogger` و `LogSanitizer`، ذخیره خام صفحات در `pageSnapshots`، ثبت کرش `FATAL` و ساخت آرشیو ZIP عیب‌یابی.
12. **`UpdateRepositoryUnitTest.kt`، `KalinanUnitTests.kt`، `ExampleRobolectricTest.kt` و `ExampleUnitTest.kt`:**
    - تست بررسی نسخه جدید از GitHub Releases، مدل‌های دامنه، متدهای کمکی رزرو و تست‌های پایه JVM/Robolectric.

---

## ۳. قواعد و استانداردهای الزامی برای افزودن آزمون‌های جدید

- ⚠️ **قانون اکید عدم اتصال به سرور واقعی:** هیچ تستی نباید به صورت خودکار به آدرس واقعی `https://jeton.bums.ac.ir` درخواست ارسال کند یا غذای واقعی رزرو/لغو کند.
- **استفاده از MockWebServer:** هرگونه تست ارتباط شبکه باید با شبیه‌ساز سرور محلی `okhttp3.mockwebserver.MockWebServer` پیاده‌سازی شود.
- **استفاده از Fixtures:** برای تست تغییر فرمت‌های ASP.NET، نمونه پاسخ‌های پاک‌سازی‌شده را در پوشه `docs/05-samples-and-fixtures/` قرار دهید؛ هم‌اکنون علاوه بر فایل‌های `har_entry*.txt` و `MyProfile_*_sanitized`، مجموعه‌ی کامل فیکسچرهای نظرسنجی اجباری (`Survey_page_sanitized.html`، `Survey_select_delta_sanitized.txt`، `Survey_questions_structure.json`، `Survey_save_request_sample.txt` و `Survey_flow_sanitized.txt`) در همین مسیر آماده شده است.
- **تست در JVM محلی / Robolectric:** از نوشتن تست‌های نیازمند به امولاتور فیزیکی (`androidTest` با Espresso) که در CI بدون امولاتور اجرا نمی‌شوند پرهیز کنید؛ تست‌های UI را با Compose Test + Robolectric در `app/src/test/` بنویسید.
