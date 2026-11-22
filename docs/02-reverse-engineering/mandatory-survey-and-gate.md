# 📋 مهندسی معکوس نظرسنجی اجباری و دروازه‌ی رزرو (`Survey_Answer.aspx`)

این مستند تحلیل کامل فنی، جریان درخواست‌ها و پاسخ‌ها، ساختار DOM، پروتکل AJAX UpdatePanel (فرمت Delta)، ساختار پرسش‌ها، قوانین اعتبارسنجی سرور و وضعیت پیاده‌سازی در کلاینت اندروید برای صفحه‌ی **نظرسنجی اجباری** (`/Survey/Survey_Answer.aspx`) در سامانه تغذیه دانشگاه علوم پزشکی بیرجند (`jeton.bums.ac.ir`) را تشریح می‌کند.

> **مبنای مستندات و برچسب‌های اطمینان:**  
> داده‌های این سند بر پایه ضبط واقعی مرورگر Chrome با رمزگشایی TLS (ابزار PCAPdroid) و دو عکس DOM (Blink MHTML) در تاریخ ۱۴۰۵/۰۸/۲۹ (۲۰۲۶-۱۱-۱۹) تهیه شده‌اند. هیچ نام، شماره شناسایی، اعتبار، تصویر چهره، کوکی یا `__VIEWSTATE` واقعی در این سند و پیوست‌های آن وجود ندارد.
> - **`[اندازه‌گیری]`**: به‌طور مستقیم در ضبط شبکه یا DOM سرور مشاهده شده است.
> - **`[استنباط]`**: از داده‌های مشاهده‌شده نتیجه‌گیری شده است.
> - **`[تأییدنشده]`**: در ضبط فعلی آزمایش نشده و نیازمند بررسی روی حساب/سناریوی واقعی است.

فیکسچرهای پاک‌سازی‌شده‌ی مرتبط در [`docs/05-samples-and-fixtures/`](../05-samples-and-fixtures/README.md):
- `Survey_page_sanitized.html`: HTML اصلی پاسخ سرور برای `GET /Survey/Survey_Answer.aspx` (فهرست نظرسنجی‌ها).
- `Survey_select_delta_sanitized.txt`: پاسخ Delta (`UpdatePanel1`) پس از انتخاب نظرسنجی («مشاهده»)، شامل ۲۷ پرسش با گزینه‌ها (فیکسچر اصلی پارسر).
- `Survey_questions_structure.json`: همان ۲۷ پرسش به صورت ساخت‌یافته (شماره، شناسه کنترل، نوع، سقف پاسخ، گزینه‌ها با شناسه).
- `Survey_save_request_sample.txt`: قالب کامل بدنه‌ی درخواست ذخیره (۹۱ فیلد) با مقادیر نمونه.
- `Survey_flow_sanitized.txt`: جریان زمانی درخواست‌ها، هدرها و کدهای وضعیت.

---

## ۱. خلاصه‌ی یافته‌ها

1. **دروازه‌ی `302` فقط روی مسیر رزرو `[اندازه‌گیری]`:**  
   هنگامی که کاربر **نظرسنجی فعال و پاسخ‌داده‌نشده** دارد، `GET /Reservation/Reservation.aspx` به‌جای صفحه‌ی رزرو، پاسخ **`302 Found` با `Location: /Survey/Survey_Answer.aspx`** برمی‌گرداند. متن پیام هشدار در صفحه: *«شرکت در نظرسنجی، برای استفاده از امکان رزرو در سامانه ضروری است.»*
2. **رفع قفل پس از ثبت موفق `[اندازه‌گیری]`:**  
   بدون ثبت نظرسنجی، رزرو بسته می‌ماند. بلافاصله پس از ثبت موفق نظرسنجی، همان `GET /Reservation/Reservation.aspx` دوباره `200 OK` (جدول رزرو) برمی‌گرداند.
3. **داشبورد و ورود بدون تغییر `[اندازه‌گیری]` / `[تأییدنشده]`:**  
   مسیرهای `/Login.aspx` و `/Master/Default.aspx` تحت تأثیر این ریدایرکت قرار نگرفتند؛ سایر صفحه‌های فرعی سامانه در حالت نظرسنجی باز آزمایش نشده‌اند `[تأییدنشده]`.
4. **فرم ASP.NET WebForms مبتنی بر `UpdatePanel` `[اندازه‌گیری]`:**  
   انتخاب نظرسنجی («مشاهده») و ذخیره پاسخ‌ها هر دو با درخواست `POST` ناهمگام AJAX و پاسخ `text/plain` در فرمت Delta انجام می‌شوند.
5. **شرایط اعتبارسنجی سرور `[اندازه‌گیری]`:**  
   در نظرسنجی ثبت‌شده **۲۷ پرسش** وجود داشت: ۲۴ پرسش گزینه‌ای (نوع `1`) و ۳ پرسش متنی (نوع `3`). سرور الزام می‌گذارد که تمام ۲۷ پرسش پاسخ داشته باشند و تعداد گزینه‌های انتخابی هر پرسش از `HiddenMaxAnswer` (در نمونه `1`) بیشتر نباشد.
6. **تشخیص نتیجه فقط از `toastify(...)` داخل `scriptStartupBlock` `[اندازه‌گیری]`:**  
   در درخواست ذخیره، کد وضعیت HTTP همواره `200 OK` است (چه در خطای اعتبارسنجی و چه در ثبت موفق). وضعیت نتیجه فقط از روی اسکریپت `toastify('success' | 'error', ...)` قابل تشخیص است.
7. **نکته‌ی تله‌ای در بدنه‌ی پاسخ `302` رزرو `[اندازه‌گیری]`:**  
   پاسخ `302` از `Reservation.aspx` علاوه بر متن کوتاه `Object moved...`، یک پوسته‌ی کامل HTML صفحه‌ی رزرو به طول **۲۳,۸۳۶ بایت** (بدون ردیف‌های منو و همراه با `VIEWSTATEGENERATOR=3ED98A75`) را نیز در بدنه چسبانده است! کلاینتی که `302` را دنبال نکند و به اشتباه بدنه را پارس نماید، به‌جای تشخیص قفل نظرسنجی، با یک منوی رزرو خالی مواجه می‌شود.
8. **اصل محصولی پاسخ‌دهی آگاهانه:**  
   پاسخ‌های نظرسنجی بیانگر **نظر شخصی کاربر** هستند؛ کلاینت نباید هیچ گزینه‌ای را به صورت پیش‌فرض یا خودکار به جای کاربر انتخاب یا ثبت کند.

---

## ۲. جریان زمانی درخواست‌ها و پاسخ‌ها

| مرحله | زمان نسبی | درخواست | پاسخ و اندازه | توضیح |
|:---:|:---:|---|---|---|
| ۱ | `t=14.2s` | `POST /Login.aspx` | `302` ← `/Master/Default.aspx` | ورود (بدون پیام خوانده‌نشده) |
| ۲ | `t=14.6s` | `GET /Master/Default.aspx` | `200 OK` (`text/html`) | داشبورد بدون ریدایرکت به نظرسنجی |
| ۳ | `t=18.2s` | `GET /Reservation/Reservation.aspx` | **`302` ← `/Survey/Survey_Answer.aspx`** | دروازه‌ی نظرسنجی؛ بدنه ۲۳,۸۳۶ بایت (حاوی پوسته خالی رزرو) |
| ۴ | `t=18.5s` | `GET /Survey/Survey_Answer.aspx` | `200 OK` (۱۹,۰۹۳ بایت) | فهرست نظرسنجی‌ها (یک ردیف فعال) + `toastify('warning', ...)` |
| ۵ | `t=30.9s` | `POST /Survey/Survey_Answer.aspx` (Ajax) | `200 OK` (`text/plain`, ۹۲,۳۴۱ بایت) | کلیک روی «مشاهده» (`ctl02$imgbtnView`) ← پنل ۲۷ پرسش + `__VIEWSTATE` ۲۲,۰۶۰ کاراکتری |
| ۶ | `t≈100–200s` | `POST /Survey/Survey_Answer.aspx` (Ajax) | `200 OK` (`text/plain`) | تلاش‌های ناموفق اعتبارسنجی (`toastify('error', ...)` برای سقف گزینه یا حداقل ۲۷ پاسخ) |
| ۷ | `t≈268s` | `POST /Survey/Survey_Answer.aspx` (Ajax) | `200 OK` (`text/plain`, ۹۱,۹۷۶ بایت) | ذخیره‌ی موفق (۹۱ فیلد فرم) ← `toastify('success', 'نظر شما با موفقیت ثبت شد.', ...)` |
| ۸ | `t=272.1s` | `GET /Reservation/Reservation.aspx` | **`200 OK`** (۱۱۵,۹۱۸ بایت) | باز شدن کامل جدول رزرو (`VIEWSTATEGENERATOR=3ED98A75`) |
| ۹ | `t=276.7s` | `POST /Reservation/Reservation.aspx` | `302` ← `/Login.aspx` | خروج (`ctl00$logout`) |

*(تمامی پاسخ‌های HTML و Delta با `Cache-Control: private,no-store` و بدون `Content-Encoding` فشرده‌سازی ارسال شدند.)* `[اندازه‌گیری]`

---

## ۳. تشخیص وضعیت «نظرسنجی لازم است» در کلاینت

### الف) تشخیص از مسیر و ریدایرکت رزرو
- در حالت `followRedirects = false`: اگر پاسخ `GET /Reservation/Reservation.aspx` دارای کد `302` و هدر `Location` منتهی به `/Survey/Survey_Answer.aspx` (`endsWith("/Survey/Survey_Answer.aspx", ignoreCase = true)`) باشد، وضعیت `SurveyRequired` است و **نباید بدنه `302` به عنوان صفحه رزرو پارس شود**.
- در حالت دنبال‌کردن ریدایرکت توسط OkHttp: آدرس نهایی `response.request.url` برابر `.../Survey/Survey_Answer.aspx` و HTML صفحه شامل نشانه‌های فهرست نظرسنجی است.
- **اشتراک شناسه جدول با پیام‌ها (نکته‌ی حیاتی پارسر):** صفحه‌ی `Survey_Answer.aspx` نیز دقیقاً همان شناسه جدول `#cphMain_grdContent` و نوار منوی Master (از جمله لینک `../MyCullinan/MyMessage.aspx`) را دارد؛ بنابراین طبقه‌بندی صفحه باید **نظرسنجی (`TYPE_SURVEY`) را پیش از پیام‌ها (`TYPE_MESSAGES`)** بررسی کند، در غیر این صورت صفحه‌ی نظرسنجی به اشتباه به عنوان صفحه‌ی پیام‌های بدون پیام خوانده‌نشده یا خطا دسته‌بندی می‌شود.

### ب) ساختار صفحه‌ی فهرست (`GET /Survey/Survey_Answer.aspx`) `[اندازه‌گیری]`
- **فرم:** `<form method="post" action="./Survey_Answer.aspx" id="MainForm">`
- **فیلدهای مخفی اولیه:** `__EVENTTARGET`، `__EVENTARGUMENT`، `__VIEWSTATE` (۱۵۱۶ کاراکتر در نمونه)، `__VIEWSTATEGENERATOR=4D6A1972`، `__VIEWSTATEENCRYPTED` (بدون `__EVENTVALIDATION`).
- **کنترل‌های اصلی داخل `#UpdatePanel1`:**
  - `ctl00$cphMain$hdnSelectedAccordion` (مقدار اولیه `0`؛ تابع `SetValue(1)` هنگام کلیک روی «مشاهده» آن را به `1` تغییر می‌دهد).
  - `ctl00$cphMain$hdnId` (خالی).
  - جدول فهرست `table#cphMain_grdContent` با ستون‌های: `ردیف` | `نظرسنجی` | `وضعیت` (`فعال`) | `تاریخ شروع` (`1405/08/26`) | `تاریخ پایان` (`1405/09/06`) | `مشاهده`.
  - دکمه‌ی تصویر ردیف اول: `<input type="image" name="ctl00$cphMain$grdContent$ctl02$imgbtnView" id="cphMain_grdContent_imgbtnView_0" onclick="SetValue(1);">`.
  - اسکریپت هنگام بارگذاری صفحه:  
    `toastify('warning', 'شرکت در نظرسنجی، برای استفاده از امکان رزرو در سامانه ضروری است.', '', 'toast-bottom-right', 'true');`
- پس از تکمیل تمام نظرسنجی‌ها در جدول `#cphMain_grdContent`، ردیف خالی با متن `«داده ای برای نمایش وجود ندارد»` ظاهر می‌شود. `[اندازه‌گیری]`

---

## ۴. پروتکل AJAX انتخاب نظرسنجی («مشاهده») `[اندازه‌گیری]`

### درخواست
```http
POST https://jeton.bums.ac.ir/Survey/Survey_Answer.aspx
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
X-MicrosoftAjax: Delta=true
X-Requested-With: XMLHttpRequest
Cache-Control: no-cache
Accept: */*
Origin: https://jeton.bums.ac.ir
Referer: https://jeton.bums.ac.ir/Survey/Survey_Answer.aspx
Cookie: <نشست>
```

| فیلد فرم | مقدار |
|---|---|
| `ctl00$ScriptManager` | `ctl00$UpdatePanel1|ctl00$cphMain$grdContent$ctl02$imgbtnView` |
| `__EVENTTARGET` / `__EVENTARGUMENT` | خالی |
| `__VIEWSTATE` | مقدار استخراج‌شده از HTML صفحه‌ی فهرست (۱۵۱۶ کاراکتر) |
| `__VIEWSTATEGENERATOR` | `4D6A1972` (از HTML خوانده شود) |
| `__VIEWSTATEENCRYPTED` | خالی |
| `ctl00$cphMain$hdnSelectedAccordion` | `1` |
| `ctl00$cphMain$hdnId` | خالی |
| `__ASYNCPOST` | `true` |
| `ctl00$cphMain$grdContent$ctl02$imgbtnView.x` / `.y` | مختصات کلیک (مثلاً `1` و `7`) |

### پاسخ Delta (`200 text/plain; charset=utf-8`, ۹۲,۳۴۱ بایت)
1. رکورد `updatePanel|UpdatePanel1`: شامل جدول فهرست و جدول پرسش‌ها (`table#cphMain_grdQuestion`) به همراه دکمه‌ی `ctl00$cphMain$btnSave` (`ذخیره`).
2. رکورد `hiddenField|__VIEWSTATE`: توکن جدید به طول **۲۲,۰۶۰ کاراکتر** که باید حتماً جایگزین ViewState قبلی شود.
3. نکته پارس: طول رکوردهای Delta بر حسب **تعداد کاراکتر یونیکد در رشته UTF-8** است، نه تعداد بایت، و محتوای HTML درون پنل خودش دارای کاراکتر `|` یا کاراکترهای چندبایتی فارسی است.

---

## ۵. ساختار پرسش‌ها و گزینه‌ها (`#cphMain_grdQuestion`) `[اندازه‌گیری]`

هر پرسش $i \in [0..N-1]$ متناظر با پیشوند کنترلی `ctl{i+2:02}` (از `ctl02` تا `ctl28` برای ۲۷ پرسش) است:

- **متن پرسش:** `<span id="cphMain_grdQuestion_Label1_{i}">`؛ در انتهای متن همیشه عبارت `حداکثر پاسخ مجاز N` چسبیده است که برای نمایش تمیز در UI بومی باید حذف شود.
- **فیلدهای مخفی اجباری هر پرسش (باید در درخواست ذخیره عیناً برگردانده شوند):**
  - `ctl00$cphMain$grdQuestion$ctlNN$HiddenMaxAnswer` (سقف انتخاب مجاز؛ در نمونه `1`)
  - `ctl00$cphMain$grdQuestion$ctlNN$hdnQuestionType` (نوع پرسش)

| مقدار `hdnQuestionType` | نوع کنترل در DOM | نام فیلد ارسالی در فرم ذخیره | مقدار ارسالی |
|:---:|---|---|---|
| **`1`** (چندگزینه‌ای، ۲۴ پرسش در نمونه) | `<input type="checkbox">` داخل `chkbxLstAnswer_{i}` | `ctl00$cphMain$grdQuestion$ctlNN$chkbxLstAnswer${pos}` | شناسه‌ی عددی گزینه (`value`)؛ فقط چک‌باکس‌های انتخاب‌شده ارسال می‌شوند |
| **`3`** (متن آزاد، ۳ پرسش در نمونه) | `<textarea>` (`txtAnswer`) | `ctl00$cphMain$grdQuestion$ctlNN$txtAnswer` | متن پاسخ کاربر |

> ⚠️ **نکات کلیدی ساختار گزینه‌ها `[اندازه‌گیری]`:**
> 1. **`pos` در برابر `value`:** `pos` اندیس صفر-مبنای جایگاه گزینه درون همان پرسش است، اما `value` یک شناسه‌ی عددی یکتا در کل نظرسنجی است.
> 2. **غیرترتیبی بودن شناسه‌های `value`:** برای نمونه در پرسش اول، شناسه‌ها به ترتیب `687` (خوب)، `688` (عالی)، `689` (متوسط)، `698` (ضعیف) هستند. برچسب گزینه را **فقط** از `<label for="...">` متناظر با `id` چک‌باکس استخراج کنید.
> 3. **انواع ناشناخته:** هر `hdnQuestionType` غیر از `1` و `3` در ضبط دیده نشد `[تأییدنشده]`؛ در صورت مشاهده در آینده در پیاده‌سازی فرم بومی، باید به مسیر WebView هدایت شود.

---

## ۶. پروتکل ذخیره‌ی نظرسنجی و پیام‌های `toastify` `[اندازه‌گیری]`

درخواست `POST /Survey/Survey_Answer.aspx` با همان هدرهای AJAX مرحله‌ی انتخاب و فرمت `application/x-www-form-urlencoded; charset=UTF-8` ارسال می‌شود (۹۱ فیلد فرم در ذخیره‌ی کامل، مقدار دکمه: `ctl00$cphMain$btnSave=ذخیره` با درصدکد UTF-8 و `__VIEWSTATE` به‌روزشده از آخرین پاسخ Delta). قالب کامل در [`Survey_save_request_sample.txt`](../05-samples-and-fixtures/Survey_save_request_sample.txt) آمده است.

پاسخ همواره `200 text/plain` (فرمت Delta) است و وضعیت ذخیره در `scriptStartupBlock` به شکل زیر اعلام می‌شود:

```javascript
SetValue(0);
toastify('<type>', '<message>', '<title>', 'toast-bottom-right', 'true');
```

| نوع (`type`) | پیام مشاهده‌شده | عنوان | وضعیت |
|---|---|---|---|
| `error` | `تعداد پاسخ انتخاب شده بیش از تعداد مجاز می باشد تعدا مجاز: 1 مورد ` | `عملیات ناموفق!` | انتخاب بیش از `HiddenMaxAnswer` برای یک پرسش |
| `error` | `حداقل تعداد سوال پاسخ داده شده، برای نظر سنجی "<عنوان>" باید 27 عدد باشد. ` | `عملیات ناموفق!` | پاسخ‌دهی کمتر از مجموع کل پرسش‌ها (از جمله ۳ پرسش متنی نوع `3`) `[استنباط]` |
| `success` | `نظر شما با موفقیت ثبت شد.` | `عملیات موفق!` | ثبت کامل؛ جدول نظرسنجی‌های پنل بازگشتی خالی می‌شود |

پس از دریافت `success`، ارسال مجدد `GET /Reservation/Reservation.aspx` با نتیجه `200 OK` باز شدن کامل دروازه رزرو را تأیید می‌کند. `[اندازه‌گیری]`

---

## ۷. وضعیت فعلی پیاده‌سازی در کلاینت اندروید و نقشه‌ی راه

### بخش‌های پیاده‌سازی‌شده در نسخه فعلی (مسیر اول: هشدار + WebView درون‌برنامه‌ای)
1. **تشخیص اختصاصی در `PageClassifier` (`TYPE_SURVEY`):**  
   پیش از بررسی جدول پیام‌ها (`TYPE_MESSAGES`) اجرا می‌شود تا تداخل `#cphMain_grdContent` رخ ندهد؛ مسیر URL (`Survey_Answer.aspx` یا `/Survey/`)، `form#MainForm[action*='Survey']`، آکاردئون نظرسنجی (`#cphMain_hdnSelectedAccordion` + `#accordion1 h3`)، دکمه `imgbtnView` و پیام واقعی `toastify` را استخراج می‌کند.
2. **مسدودسازی کنترل‌شده در لایه مخازن رزرو:**  
   هر دو `MenuRepository` و `MenuDeltaExecutor` در صورت طبقه‌بندی `TYPE_SURVEY` استثنای اختصاصی `MandatorySurveyException(surveyMessage, surveyUrl)` صادر می‌کنند تا ریدایرکت نظرسنجی هرگز به عنوان «منوی خالی» یا «انقضای نشست» گزارش نشود.
3. **هشدار و راهنما در `MenuViewModel` / `MenuScreen`:**  
   وضعیت `SurveyBlockInfo` یک بنر/صفحه‌ی مسدودکننده‌ی واضح با متن نظرسنجی و دکمه باز کردن صفحه نظرسنجی درون برنامه نمایش می‌دهد.
4. **مرورگر درون‌برنامه‌ای امن (`SurveyWebViewScreen`):**  
   - همگام‌سازی کوکی‌های نشست از `CullinanHttpClient.cookieJar` به `android.webkit.CookieManager` پیش از بارگذاری `Survey_Answer.aspx`.
   - جلوگیری از عبور بی‌صدا در خطای گواهی TLS (`SslErrorHandler.cancel()` با کارت راهنما و امکان باز کردن در مرورگر خارجی، بدون دور زدن اعتبارسنجی SSL در WebView).
   - پایش خودکار تکمیل نظرسنجی یا بازگشت کاربر با دکمه‌ی *«تکمیل کردم؛ بررسی منوی رزرو»* برای واکشی مجدد `Reservation.aspx`.
5. **تست‌های خودکار (`SurveyAndPaginationUnitTest.kt`):**  
   تست ریدایرکت ۳۰۲ از `/Reservation/Reservation.aspx` به `/Survey/Survey_Answer.aspx`، عدم طبقه‌بندی اشتباه به عنوان `TYPE_MESSAGES`، صدور `MandatorySurveyException` و مقداردهی `SurveyBlockInfo` در `MenuViewModel`.

### فاز اختیاری آتی (فرم بومی درون برنامه — هنوز پیاده‌سازی نشده)
- ساخت `SurveyRepository` + پارسر `SurveyParser` (مبتنی بر `Survey_select_delta_sanitized.txt` و `Survey_questions_structure.json`) و صفحه Compose با انتخاب‌گر رادیویی/چک‌باکس و فیلد متنی بدون هیچ انتخاب پیش‌فرض خودکار.
- استفاده از فراخوانی بدون دنبال‌کردن خودکار ریدایرکت (`executeCallNoRedirect`) روی `GET /Reservation/Reservation.aspx` برای تشخیص فوری `302` بدون نیاز به دانلود یا پارس پوسته ۲۳ کیلوبایتی رزرو.
- افزودن قواعد سانسور اختصاصی `LogSanitizer` برای `txtAnswer`، `chkbxLstAnswer`، و حصول اطمینان از پاک‌سازی کامل متن پاسخ‌های آزاد کاربر از حافظه پس از بسته شدن فرم و عدم لاگ پاسخ‌های Delta یا ViewState ۲۲ هزار کاراکتری.

---

## ۸. موارد تأییدنشده (`[تأییدنشده]`)

1. آیا صفحه‌های دیگری غیر از `/Reservation/Reservation.aspx` (مثل فیش فراموشی یا خرید روزفروش) هم در زمان باز بودن نظرسنجی به `Survey_Answer.aspx` ریدایرکت می‌شوند؟
2. رفتار دقیق `POST /Reservation/Reservation.aspx` (رزرو/ناوبری) اگر نظرسنجی حین باز بودن نشست فعال شود.
3. رفتار فرم در حضور بیش از یک نظرسنجی هم‌زمان و نام‌گذاری کنترل ردیف‌های بعدی (`ctl03`، ...).
4. انواع پرسش احتمالی غیر از `1` و `3` و پرسش‌هایی با `HiddenMaxAnswer > 1`.
5. آیا گذاشتن متن خالی برای سه پرسش نوع `3` هنگام پر بودن ۲۴ پرسش گزینه‌ای واقعاً با خطای «۲۷ عدد» رد می‌شود یا خیر.
6. آیا پس از خطای `toastify('error', ...)` استفاده مستقیم از `__VIEWSTATE` جدید پاسخ خطا برای ذخیره مجدد کافی است یا نیاز به زدن دوباره «مشاهده» وجود دارد.
7. ضرورت ارسال هدرهای `Referer` / `Origin` برای درخواست‌های Ajax روی OkHttp.
8. متن دقیق صفحه فهرست هنگام مراجعه در حالتی که از ابتدا هیچ نظرسنجی فعالی ثبت نشده است.
