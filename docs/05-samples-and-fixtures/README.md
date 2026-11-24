# 📦 نمونه‌های ترافیک شبکه دانشگاه (Network Samples & Fixtures)

این پوشه شامل فایل‌های ضبط‌شده ترافیک زنده و پاسخ‌های واقعی سرور سامانه تغذیه دانشگاه علوم پزشکی بیرجند ([https://jeton.bums.ac.ir](https://jeton.bums.ac.ir)) است. این فایل‌ها جهت تست‌های واحد و تحلیل پروتکل وب‌فرم بدون نیاز به ارتباط با اینترنت مورد استفاده قرار می‌گیرند.

---

## 📑 لیست فایل‌های نمونه و شرح محتوا

### ۱. `har_entry0_resp.txt` (پاسخ جابه‌جایی هفته - Week Navigation Response)
- **عملیات شبیه‌سازی‌شده:** کلیک کاربر روی دکمه «هفته بعد» (`imgbtnNextWeek`).
- **فرمت خروجی:** پیام دلتای ASP.NET AJAX حاوی `UpdatePanel1`، فیلد `__VIEWSTATE` به‌روزشده، مقدار اعتبار ریالی دانشجو (`590,000 ریال`) و جدول منوی هفتگی ۷ روزه.
- **تست مرتبط:** متد `testParseHarEntry0_NextWeekNavigation` در کلاس `ReservationLiveHarUnitTest.kt`.

### ۲. `har_entry1_resp.txt` (پاسخ ثبت موفق رزرو غذا - Meal Reservation Success)
- **عملیات شبیه‌سازی‌شده:** ارسال فرم ذخیره رزرو وعده ناهار روز چهارشنبه با انتخاب غذای چلو جوجه کباب و سلف مرکزی.
- **فرمت خروجی:** بازگشت فریم دلتا با توکن‌های جدید ViewState و پیام توست موفقیت‌آمیز سرور سلف.
- **تست مرتبط:** متد `testParseHarEntry1_MealReservationSuccess` در کلاس `ReservationLiveHarUnitTest.kt`.

### ۳. `har_entry2_resp.txt` (پاسخ لغو موفق رزرو غذا - Meal Cancellation Success)
- **عملیات شبیه‌سازی‌شده:** ارسال فرم لغو رزرو غذا در یک روز رزروشده.
- **فرمت خروجی:** فریم دلتا با بازگشت اعتبار ریالی به حساب دانشجو و تغییر وضعیت روز به لغو‌شده.
- **تست مرتبط:** متد `testParseHarEntry2_MealCancellationSuccess` در کلاس `ReservationLiveHarUnitTest.kt`.

### ۴. `MyProfile_page_sanitized.html` (HTML صفحه‌ی «پروفایل من» بدون اطلاعات شخصی)
- **عملیات شبیه‌سازی‌شده:** `GET /MyCullinan/MyProfile.aspx` پس از ورود موفق به سامانه.
- **محتوا و ساختار:** HTML واقعی پاسخ سرور با همان ساختار DOM، جدول‌های دو ستونی `fieldset` و شناسه‌های ثابت (`cphMain_lblName`، `cphMain_lblIdNumber`، `cphMain_lblNationalCode`، نوار کناری `#divUsername`، `#lblIdNumber`، `#lblCredit` با نمونه `123,000- ريال ` و مقدار خالی `...`)؛ تمامی مقادیر شخصی با داده‌های ساختگی استاندارد و توکن `__VIEWSTATE` با `VIEWSTATE_PLACEHOLDER` جایگزین شده‌اند.
- **کاربرد در تست:** تست واحد `ProfileParser` و `PageClassifier.TYPE_PROFILE` در `ProfileFeatureUnitTest.kt` (استخراج ۲۱ فیلد پروفایل و ۵ فیلد نوار کناری، نگاشت `...` ← `null`، علامت `-` پسوندی اعتبار و نرمال‌سازی کاراکتر عربی `ي`). برای جزئیات کامل سند [مهندسی معکوس پروفایل من](../02-reverse-engineering/my-profile-and-user-image.md) را ببینید.

### ۵. `MyProfile_flow_sanitized.txt` (جریان کامل درخواست‌ها و پاسخ‌های پروفایل من و خروج)
- **عملیات شبیه‌سازی‌شده:** ضبط TLS-decrypted مرورگر Chrome (PCAPdroid) از توالی ورود، دریافت `Default.aspx`، `UserImage.ashx`، `GET /MyCullinan/MyProfile.aspx` و `POST /MyCullinan/MyProfile.aspx` برای خروج (`ctl00$logout`).
- **محتوای پاک‌سازی‌شده:** هدرها، کدهای وضعیت، اندازه‌های پاسخ (`25802` بایت HTML و `5660` بایت `image/jpg`) و فیلدهای بدنه‌ی ۵تایی خروج، بدون هرگونه کوکی، کلید TLS یا ViewState واقعی.
- **کاربرد در تست:** پیاده‌سازی سناریوهای `MockWebServer` برای `ProfileRepository`، `UserImage.ashx` و خروج با بدنه‌ی کوچک.

### ۶. فیکسچرهای نظرسنجی اجباری (`Survey_Answer.aspx`)
- **`Survey_page_sanitized.html`:** پاسخ واقعی `GET /Survey/Survey_Answer.aspx` شامل جدول `#cphMain_grdContent` با یک نظرسنجی فعال، دکمه `imgbtnView`، منوی مشترک Master و اسکریپت `toastify('warning', ...)`؛ جایگزین‌شده با مقادیر ساختگی و `VIEWSTATE_PLACEHOLDER`.
- **`Survey_select_delta_sanitized.txt`:** پاسخ Delta (`UpdatePanel1`) پس از انتخاب نظرسنجی («مشاهده»)، حاوی HTML کامل جدول `#cphMain_grdQuestion` (۲۷ پرسش: ۲۴ گزینه‌ای نوع `1` و ۳ متنی نوع `3`)، دکمه ذخیره و توکن `__VIEWSTATE` ۲۲,۰۶۰ کاراکتری پاک‌سازی‌شده. فیکسچر اصلی پارسر فرم نظرسنجی.
- **`Survey_questions_structure.json`:** ساختار استخراج‌شده و معتبر هر ۲۷ پرسش (شماره، پیشوند `ctl02..ctl28`، نوع، `HiddenMaxAnswer`، متن تمیز پرسش و لیست گزینه‌ها با `pos`، `value` و `label`).
- **`Survey_save_request_sample.txt`:** قالب کامل بدنه‌ی ۹۱ فیلدی درخواست `POST` ذخیره‌ی نظرسنجی با مقادیر نمونه و یادداشت راهنما.
- **`Survey_flow_sanitized.txt`:** جدول زمانی کامل ضبط PCAPdroid از ورود، ریدایرکت `302` رزرو به نظرسنجی (همراه با پوسته ۲۳,۸۳۶ بایتی)، تلاش‌های ناموفق و موفق `toastify`، باز شدن رزرو و خروج.
- **سند مرتبط:** [مهندسی معکوس نظرسنجی اجباری و دروازه رزرو](../02-reverse-engineering/mandatory-survey-and-gate.md).

### ۷. فیکسچرهای خروج پویا از حساب کاربری (`Logout`)
- **`Logout_Default_page_sanitized.html`:** HTML کامل صفحه‌ی داشبورد (`/Master/Default.aspx`) شامل فرم `MainForm`، لینک مشترک خروج `a#logout` با `javascript:__doPostBack('ctl00$logout','')`، مقدار `VIEWSTATEGENERATOR=79A64AFF` و منوی آکاردئونی صفحات؛ داده‌های شخصی با مقادیر ساختگی و توکن ViewState با `VIEWSTATE_PLACEHOLDER` جایگزین شده‌اند.
- **`Logout_pages_comparison.json`:** جدول مقایسه‌ی ساختار فرم، کنترل‌های موفق بدنه، حجم بایتی و `__VIEWSTATEGENERATOR` اختصاصی در پنج صفحه‌ی `Default`، `Reservation`، `MyProfile`، `MyMessage` و `Survey_Answer`.
- **`Logout_flow_sanitized.txt`:** ترتیب زمانی چهار خروج واقعی متوالی از صفحات مختلف، نمونه درخواست و پاسخ `302`، هشدار دور ریختن بدنه‌ی HTML پاسخ `302` و جزئیات چرخه‌ی کوکی (`ASP.NET_SessionId`، `keyId` با عمر حدود ۲ دقیقه و `__AntiXsrfToken`).
- **سند مرتبط:** [خروج واقعی و پویا از سامانه کالینان](../02-reverse-engineering/server-logout-flow.md).

### ۸. فیکسچرهای «سبد غذایی و فروش/حذف از سبد» (`FoodBasketSale.aspx`)
- **`FoodBasketSale_page_sanitized.html`:** HTML کامل پاسخ سرور برای `GET /FoodBasket/FoodBasketSale.aspx` (فرم اولیه بدون `#cphMain_fldsetResult`، دارای `#cphMain_txtDate`، `#cphMain_drpReserveType`، `#cphMain_drpFoodType`، `#cphMain_btnFoodReload` و `__VIEWSTATEGENERATOR=92079A9C`).
- **`FoodBasket_reload_delta_sanitized.txt`:** پاسخ Delta عملیات «بارگذاری غذا» (`btnFoodReload`) با `hdnReservationId=1234567`، وضعیت «این غذا رزرو شده است» و دکمه‌ی `btnAccept` («فروش به سبد غذا»).
- **`FoodBasket_sell_delta_sanitized.txt`:** پاسخ Delta عملیات «فروش به سبد غذا» (`btnAccept`) با اسکریپت `toastify('success', 'این غذا به سبد غذایی جهت فروش اضافه شد', ...)`، دکمه‌ی `btnCancel` و نمونه‌ی واقعی **برچسب وضعیت کهنه** («این غذا رزرو شده است»).
- **`FoodBasket_reload_after_sell_delta_sanitized.txt`:** پاسخ Delta بارگذاری دوباره بعد از فروش با وضعیت به‌روزشده («این غذا در سبد غذایی برای فروش قرار دارد») و دکمه‌ی `btnCancel`.
- **`FoodBasket_cancel_delta_sanitized.txt`:** پاسخ Delta عملیات «حذف از سبد غذا» (`btnCancel`) با اسکریپت `toastify('success', 'این غذا از سبد غذایی حذف شد', ...)`، بازگشت دکمه به `btnAccept` و نمونه‌ی واقعی **برچسب وضعیت کهنه** در جهت معکوس («این غذا در سبد غذایی برای فروش قرار دارد»).
- **`FoodBasket_reload_after_cancel_delta_sanitized.txt`:** پاسخ Delta بارگذاری دوباره بعد از حذف با بازگشت کامل به وضعیت «این غذا رزرو شده است» و دکمه‌ی `btnAccept` با همان `hdnReservationId` ثابت.
- **`FoodBasket_flow_sanitized.txt`:** جدول زمانی، هدرها و بدنه‌ی همه‌ی درخواست‌های هر دو ضبط PCAPdroid (شامل ۱۱ و ۱۲ فیلدی بارگذاری، ۱۲ فیلدی فروش و حذف، بررسی `Reservation.aspx` و خروج ۹ فیلدی).
- **سند مرتبط:** [مهندسی معکوس «سبد غذایی»، «فروش به سبد غذایی» و «حذف از سبد»](../02-reverse-engineering/food-basket-and-sale.md).

---

## 🔬 نحوه استفاده در کدهای تست پروژه

کلاس `ReservationLiveHarUnitTest.kt` فایل‌های این پوشه را به عنوان اولویت اول بارگذاری می‌کند:

```kotlin
val candidates = listOf(
    File("docs/05-samples-and-fixtures/har_entry${index}_resp.txt"),
    File("../docs/05-samples-and-fixtures/har_entry${index}_resp.txt"),
    File("app/src/test/resources/har_entry${index}_resp.txt")
)
```

> 🔒 **حریم خصوصی فیکسچرها:** تمامی فایل‌های موجود در این پوشه پیش از ثبت در مخزن به‌طور کامل پاک‌سازی (Sanitize) شده‌اند و **فاقد هرگونه نام واقعی، کد ملی، شماره دانشجویی، تلفن، ایمیل، تصویر چهره یا کوکی نشست** هستند. هنگام افزودن هر فیکسچر جدید، از پاک‌سازی کامل مقادیر شخصی و `VIEWSTATE` اطمینان حاصل کنید.

این ساختار باعث می‌شود آزمون‌ها با نهایت سرعت روی JVM محلی و در سرورهای GitHub Actions بدون وابستگی به شبکه بیرونی اجرا شوند.
