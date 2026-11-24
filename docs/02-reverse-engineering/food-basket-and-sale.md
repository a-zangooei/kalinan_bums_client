# 🧺 مهندسی معکوس «سبد غذایی»، «فروش به سبد غذایی» و «حذف از سبد» (`FoodBasketSale.aspx`)

این مستند تحلیل کامل فنی، جریان درخواست‌ها و پاسخ‌ها، ساختار DOM، ماشین حالت، پروتکل AJAX UpdatePanel (فرمت Delta)، ملاحظات امنیتی و الگوریتم پیشنهادی پیاده‌سازی برای زیرسیستم **«سبد غذایی»** (`menuIdx=2`) و به‌طور خاص صفحه‌ی **«فروش به سبد غذایی»** (`/FoodBasket/FoodBasketSale.aspx`) در سامانه تغذیه کالینان دانشگاه علوم پزشکی بیرجند (`jeton.bums.ac.ir`) را تشریح می‌کند.

> **مبنای مستندات و برچسب‌های اطمینان:**  
> داده‌های این سند بر پایه **دو ضبط واقعی مرورگر Chrome 156 روی اندروید با رمزگشایی TLS (ابزار PCAPdroid)** در تاریخ **۱۴۰۵/۰۹/۰۳ (۲۰۲۶-۱۱-۲۴)** به وقت تهران تهیه شده‌اند:
> 1. **ضبط اول (ساعت ۱۱:۲۴، جریان فروش):** شامل بارگذاری، فروش به سبد، بررسی صفحه‌ی رزرو، بارگذاری مجدد پس از GET تازه، خروج از سیستم و **سه عکس DOM (Blink MHTML)** در ساعت‌های `۱۱:۲۴:۰۰` (فرم اولیه بدون نتیجه)، `۱۱:۲۴:۰۵` (پس از بارگذاری با دکمه‌ی فروش) و `۱۱:۲۴:۱۱` (پس از فروش با toast موفق و دکمه‌ی حذف).
> 2. **ضبط دوم (ساعت ۱۱:۳۴، چرخه‌ی کامل فروش و حذف دو سمتی):** برای وعده ناهار فردا (`1405/09/04`) شامل بارگذاری ← فروش ← بارگذاری مجدد (بدون GET تازه) ← **حذف از سبد (`btnCancel`)** ← GET تازه ← بارگذاری مجدد (تأیید بازگشت به `btnAccept`) و بارگذاری دوباره‌ی بلافاصله.
>
> **پاک‌سازی حریم خصوصی:** شناسه‌ی رزرو (`1234567`)، نام غذا (`نام غذای نمونه`)، نام سلف (`سلف نمونه`)، نام و شماره شناسایی، اعتبار، عکس چهره، کوکی‌ها و مقادیر `__VIEWSTATE` در این سند و تمامی پیوست‌ها ماسک شده‌اند (تنها طول واقعی ViewState حفظ شده است). فایل‌های خام ضبط (pcapng و MHTML) حاوی داده‌های حساس شخصی هستند و نباید وارد مخزن شوند.
> - **`[اندازه‌گیری]`**: به‌طور مستقیم در ضبط شبکه یا DOM/MHTML مشاهده شده است.
> - **`[استنباط]`**: از داده‌های مشاهده‌شده نتیجه‌گیری شده است.
> - **`[تأییدنشده]`**: در ضبط فعلی آزمایش نشده و نیازمند بررسی روی حساب/سناریوی واقعی است.

**فیکسچرهای پاک‌سازی‌شده‌ی مرتبط در [`docs/05-samples-and-fixtures/`](../05-samples-and-fixtures/README.md):**
- [`FoodBasketSale_page_sanitized.html`](../05-samples-and-fixtures/FoodBasketSale_page_sanitized.html): HTML اصلی پاسخ سرور برای `GET /FoodBasket/FoodBasketSale.aspx` (فرم اولیه بدون پنل نتیجه).
- [`FoodBasket_reload_delta_sanitized.txt`](../05-samples-and-fixtures/FoodBasket_reload_delta_sanitized.txt): پاسخ Delta عملیات «بارگذاری غذا» (وضعیت: «این غذا رزرو شده است»، دکمه: `btnAccept`).
- [`FoodBasket_sell_delta_sanitized.txt`](../05-samples-and-fixtures/FoodBasket_sell_delta_sanitized.txt): پاسخ Delta عملیات «فروش به سبد غذا» (toast موفق، دکمه: `btnCancel`، برچسب وضعیت کهنه).
- [`FoodBasket_reload_after_sell_delta_sanitized.txt`](../05-samples-and-fixtures/FoodBasket_reload_after_sell_delta_sanitized.txt): پاسخ Delta بارگذاری دوباره پس از فروش (وضعیت: «این غذا در سبد غذایی برای فروش قرار دارد»، دکمه: `btnCancel`).
- [`FoodBasket_cancel_delta_sanitized.txt`](../05-samples-and-fixtures/FoodBasket_cancel_delta_sanitized.txt): پاسخ Delta عملیات «حذف از سبد غذا» (toast موفق، بازگشت دکمه به `btnAccept`، برچسب وضعیت کهنه).
- [`FoodBasket_reload_after_cancel_delta_sanitized.txt`](../05-samples-and-fixtures/FoodBasket_reload_after_cancel_delta_sanitized.txt): پاسخ Delta بارگذاری دوباره پس از حذف (بازگشت کامل به وضعیت: «این غذا رزرو شده است» و `btnAccept`).
- [`FoodBasket_flow_sanitized.txt`](../05-samples-and-fixtures/FoodBasket_flow_sanitized.txt): ترتیب زمانی، هدرها و بدنه‌ی همه‌ی درخواست‌ها در هر دو ضبط (به همراه خروج).

---

## ۱. جایگاه «سبد غذایی» در سامانه و خلاصه‌ی یافته‌های کلیدی

### زیرمنوهای گروه «سبد غذایی» (`menuIdx=2`)
در نوار منوی دائمی `#accordion` تمامی صفحات Master سامانه، گروه **«سبد غذایی»** دارای دو صفحه‌ی مجزا است `[اندازه‌گیری]`:

| عنوان زیرمنو | مسیر نسبی | وضعیت مهندسی معکوس |
|---|---|---|
| **خرید از سبد غذایی** | `../FoodBasket/FoodBasketBuy.aspx` | در این ضبط‌ها پوشش داده نشده است `[تأییدنشده]` |
| **فروش به سبد غذایی** | `../FoodBasket/FoodBasketSale.aspx` | به‌طور کامل در دو ضبط زنده تحلیل شده است `[اندازه‌گیری]` |

### خلاصه‌ی یافته‌های صفحه‌ی فروش و حذف (`FoodBasketSale.aspx`)
1. **تک‌اسلات بودن صفحه (نه فهرست رزروها):** برخلاف `Reservation.aspx`، صفحه‌ی فروش فهرستی از رزروهای دانشجو نمایش نمی‌دهد؛ بلکه کاربر برای **یک تاریخ (`txtDate`)، یک نوع رزرو (`drpReserveType`) و یک وعده (`drpFoodType`)** دکمه‌ی «بارگذاری غذا» را می‌زند تا وضعیت همان وعده واکشی شود. `[اندازه‌گیری]`
2. **سه رویداد AJAX در قالب Delta:** عملیات‌های **بارگذاری غذا (`btnFoodReload`)**، **فروش به سبد غذا (`btnAccept`)** و **حذف از سبد غذا (`btnCancel`)** همگی به‌صورت `POST` ناهمگام به `UpdatePanel1` ارسال شده و پاسخ `text/plain` با فرمت ASP.NET Delta برمی‌گردانند. `[اندازه‌گیری]`
3. **شناسه‌ی رزرو سمت سروری (`hdnReservationId`):** شناسه‌ی رزرو داخل HTML پاسخ «بارگذاری غذا» در کنترل مخفی `#cphMain_hdnReservationId` از سرور می‌آید و باید دقیقاً در درخواست‌های فروش و حذف بازگردانده شود. `[اندازه‌گیری]`
4. **دکمه‌ی عمل وابسته به وضعیت:** اگر غذا رزرو شده باشد دکمه‌ی `btnAccept` («فروش به سبد غذا») و اگر در سبد باشد دکمه‌ی `btnCancel` («حذف از سبد غذا») در پنل رندر می‌شود. `[اندازه‌گیری]`
5. **⚠️ تله‌ی مهم «برچسب کهنه» (در هر دو جهت فروش و حذف):**
   - بلافاصله در پاسخ Delta **فروش (`btnAccept`)**، دکمه به `btnCancel` تغییر می‌کند و toast موفقیت ارسال می‌شود، اما **`#cphMain_lblStatus` همچنان متن کهنه‌ی «این غذا رزرو شده است»** را نشان می‌دهد! `[اندازه‌گیری]`
   - بلافاصله در پاسخ Delta **حذف (`btnCancel`)** نیز دکمه به `btnAccept` برمی‌گردد و toast موفقیت ارسال می‌شود، اما **`#cphMain_lblStatus` همچنان متن کهنه‌ی «این غذا در سبد غذایی برای فروش قرار دارد»** را نشان می‌دهد! `[اندازه‌گیری]`
   - تنها پس از فراخوانی دوباره‌ی «بارگذاری غذا» (`btnFoodReload`)، برچسب `#cphMain_lblStatus` به‌روز می‌شود. بنابراین کلاینت باید وضعیت را **منحصراً از نام دکمه (`btnAccept` / `btnCancel`)** تشخیص دهد و پس از هر عمل موفق یک بار مجدداً بارگذاری بزند.
6. **برگشت‌پذیری کامل و ثبات شناسه:** فروش و حذف کاملاً برگشت‌پذیرند (`رزرو شده ⇄ در سبد`)؛ پس از حذف، همان رزرو مجدداً قابل فروش است و `hdnReservationId` در سراسر چرخه‌ی بارگذاری ← فروش ← بارگذاری ← حذف ← بارگذاری ثابت می‌ماند (حذف از سبد به معنای لغو رزرو غذا نیست). `[اندازه‌گیری]`
7. **نتیجه‌ی عمل در `toastify(...)`:** پیام‌های موفقیت فروش و حذف از طریق بلاک `scriptStartupBlock` و فراخوانی `toastify('success', ...)` ارسال می‌شوند؛ کد HTTP همواره `200` است. پیام‌های خطا در این دو ضبط مشاهده نشدند. `[اندازه‌گیری]`
8. **بازه زمانی فروش:** علاوه بر ناهار همان روز (`1405/09/03` در ساعت `11:24`)، فروش و حذف ناهار **فردا** (`1405/09/04`) نیز پذیرفته شد. `[اندازه‌گیری]`
9. **نبود بازتاب در `Reservation.aspx`:** صفحه‌ی رزرو هفتگی پس از فروش غذا **هیچ نشانه‌ی بصری یا وضعیتی** مبنی بر قرارگرفتن غذا در سبد ندارد؛ منبع یگانه وضعیت سبد، همین صفحه‌ی `FoodBasketSale.aspx` است. `[اندازه‌گیری]`
10. **تأیید الگوی خروج عمومی:** خروج از `FoodBasketSale.aspx` با ۹ کنترل موفق فرم و پاسخ `302 → /Login.aspx` مطابق الگوریتم [خروج واقعی و پویا](./server-logout-flow.md) انجام شد. `[اندازه‌گیری]`

---

## ۲. جریان زمانی درخواست‌ها و پاسخ‌ها در دو ضبط واقعی

### ضبط اول: فروش ناهار امروز (`1405/09/03` — ساعت ۱۱:۲۴ تهران) `[اندازه‌گیری]`

*(ضبط در حالی آغاز شد که نشست از پیش باز بود.)*

| ساعت (تهران) | درخواست | پاسخ و اندازه | توضیح |
|---|---|---|---|
| `۱۱:۲۳:۴۷` و `۱۱:۲۳:۵۲` | `GET /FoodBasket/FoodBasketSale.aspx` | `200 OK` (HTML) | فرم اولیه؛ `__VIEWSTATE` ۸۹۶ کاراکتر؛ تاریخ پیش‌فرض امروز؛ درخواست دوم بازخوانی با `Referer` همین صفحه |
| `۱۱:۲۴:۰۰` | *(عکس DOM اول — Blink MHTML)* | — | فرم اولیه بدون `fieldset#cphMain_fldsetResult` |
| `۱۱:۲۴:۰۲` | `POST /FoodBasket/FoodBasketSale.aspx` [Ajax: `btnFoodReload`] | `200 OK` Delta (`۵۱۶۹` بایت) | ۱۱ فیلد (`۱۳۵۲` بایت) ← وضعیت «این غذا رزرو شده است» + `btnAccept` (`VS`: ۱۱۷۶) |
| `۱۱:۲۴:۰۵` | *(عکس DOM دوم — Blink MHTML)* | — | نمایش پنل نتیجه با دکمه‌ی «فروش به سبد غذا» |
| `۱۱:۲۴:۰۸` | `POST /FoodBasket/FoodBasketSale.aspx` [Ajax: `btnAccept`] | `200 OK` Delta (`۵۳۵۹` بایت) | ۱۲ فیلد (`۱۶۹۹` بایت) ← `toastify('success', ...)` + دکمه `btnCancel`، ولی `lblStatus` کهنه |
| `۱۱:۲۴:۱۱` | *(عکس DOM سوم — Blink MHTML)* | — | نمایش toast موفق و دکمه‌ی «حذف از سبد غذا» |
| `۱۱:۲۴:۱۸` | `GET /Reservation/Reservation.aspx` | `200 OK` | بررسی اثر فروش در جدول رزرو هفتگی؛ هیچ تغییری در ظاهر ردیف دیده نشد |
| `۱۱:۲۴:۳۲` | `GET /FoodBasket/FoodBasketSale.aspx` | `200 OK` (HTML) | فرم تازه؛ `__VIEWSTATE` ۸۹۶ کاراکتر |
| `۱۱:۲۴:۳۶` | `POST /FoodBasket/FoodBasketSale.aspx` [Ajax: `btnFoodReload`] | `200 OK` Delta (`۵۲۴۳` بایت) | ۱۱ فیلد (`۱۳۳۸` بایت) ← وضعیت به‌روزشده: «این غذا در سبد غذایی برای فروش قرار دارد» + `btnCancel` (`VS`: ۱۲۱۶) |
| `۱۱:۲۴:۴۲` | `POST /FoodBasket/FoodBasketSale.aspx` [خروج: `ctl00$logout`] | `302 Found` → `/Login.aspx` | POST معمولی ۹ فیلدی (`۱۵۳۹` بایت) |

### ضبط دوم: چرخه‌ی کامل فروش و حذف ناهار فردا (`1405/09/04` — ساعت ۱۱:۳۴ تهران) `[اندازه‌گیری]`

*(این ضبط شامل خروج نبود و شناسه‌ی رزرو در تمامی مراحل ثابت ماند.)*

| ساعت (تهران) | درخواست | پاسخ و اندازه | توضیح |
|---|---|---|---|
| `۱۱:۳۴:۴۳` | `GET /FoodBasket/FoodBasketSale.aspx` | `200 OK` | فرم اولیه؛ `__VIEWSTATE` ۸۹۶ کاراکتر |
| `۱۱:۳۴:۴۸` | `POST` Ajax «بارگذاری غذا» (۱۱ فیلد، `۱۳۴۰` بایت) | `200 OK` Delta (`۵۱۹۶` بایت) | وضعیت «رزرو شده» + `btnAccept` (`VS`: ۱۱۹۶ کاراکتر) |
| `۱۱:۳۴:۵۱` | `POST` Ajax «فروش به سبد غذا» (۱۲ فیلد، `۱۷۲۱` بایت) | `200 OK` Delta (`۵۳۸۶` بایت) + toast موفق | دکمه ← `btnCancel`، برچسب وضعیت کهنه |
| `۱۱:۳۴:۵۳` | `POST` Ajax «بارگذاری غذا» **بدون GET تازه** (۱۲ فیلد، `۱۷۱۳` بایت، با `hdnReservationId`) | `200 OK` Delta (`۵۲۷۴` بایت) | «این غذا در سبد غذایی برای فروش قرار دارد» + `btnCancel` (`VS`: ۱۲۴۰ کاراکتر) |
| `۱۱:۳۴:۵۷` | `POST` Ajax **«حذف از سبد غذا»** (`btnCancel`، ۱۲ فیلد، `۱۷۵۵` بایت) | `200 OK` Delta (`۵۴۴۷` بایت) + toast موفق | دکمه ← `btnAccept`، برچسب وضعیت کهنه (`VS`: ۱۲۴۰ کاراکتر) |
| `۱۱:۳۵:۰۰` | `GET /FoodBasket/FoodBasketSale.aspx` | `200 OK` | فرم تازه (`VS`: ۸۹۶) |
| `۱۱:۳۵:۰۸` | `POST` Ajax «بارگذاری غذا» (۱۱ فیلد، `۱۳۵۸` بایت، ViewState اولیه) | `200 OK` Delta (`۵۱۹۶` بایت) | «این غذا رزرو شده است» + `btnAccept` (تأیید حذف کامل از سبد؛ `VS`: ۱۱۹۶) |
| `۱۱:۳۵:۰۸` | بارگذاری دوباره‌ی بلافاصله (۱۲ فیلد، `۱۶۹۵` بایت، با `hdnReservationId` و ViewState `۱۱۹۶`) | `200 OK` Delta (`۵۱۹۶` بایت) | پاسخ دقیقاً همانند قبلی؛ ناشی از دوبار لمس متوالی |

---

## ۳. دسترسی به صفحه، ساختار HTML و جزئیات فرم (`FoodBasketSale.aspx`)

### مشخصات HTTP و پوسته Master `[اندازه‌گیری]`
- **درخواست:** `GET https://jeton.bums.ac.ir/FoodBasket/FoodBasketSale.aspx` همراه با کوکی نشست (`ASP.NET_SessionId`).
- **پاسخ:** `200 OK`، `Content-Type: text/html; charset=utf-8`، `Cache-Control: private,no-store`، **بدون فشرده‌سازی** (`Content-Encoding` ندارد).
- **دروازه‌های پیشین سامانه:** رفتار ریدایرکت دروازه‌های [پیام خوانده‌نشده](./unread-messages-and-menu-lock.md) یا [نظرسنجی اجباری](./mandatory-survey-and-gate.md) هنگام فراخوانی مستقیم `FoodBasketSale.aspx` در این دو ضبط آزمایش نشد؛ ممکن است مشابه `Reservation.aspx` ریدایرکت `302` اعمال شود. `[تأییدنشده]`
- **فرم و فیلدهای ASP.NET:**
  - تگ فرم: `<form method="post" action="./FoodBasketSale.aspx" id="MainForm">`
  - فیلدهای مخفی: `__EVENTTARGET`، `__EVENTARGUMENT`، `__VIEWSTATE` (طول اولیه `896` کاراکتر)، `__VIEWSTATEGENERATOR=92079A9C`، `__VIEWSTATEENCRYPTED=""`.
  - **بدون `__EVENTVALIDATION`:** در HTML صفحه هیچ فیلد `__EVENTVALIDATION` وجود ندارد (بااین‌حال در کد عمومی فرم‌ساز بهتر است در صورت وجود ارسال شود).
- **پیکربندی ASP.NET AJAX:**
  ```javascript
  Sys.WebForms.PageRequestManager._initialize(
      'ctl00$ScriptManager', 'MainForm',
      ['tctl00$UpdatePanel1', 'UpdatePanel1'], [], [], 400, 'ctl00'
  );
  ```
  زمان انتظار آسنکرون (`asyncPostBackTimeout`) برابر `400` ثانیه و نشانگر پیشرفت `#PageUpdateProgress` با `displayAfter: 500` میلی‌ثانیه تنظیم شده است.
- **اجزای مشترک پوسته Master:** هدر شامل `#logout` (`javascript:__doPostBack('ctl00$logout','')`) و `#lblTodayDate`؛ نوار کناری شامل `UserControls/UserImage.ashx`، `#divUsername`، `#lblIdNumber`، `#lblCredit`، `#lblCafeteriaCredit` و منوی آکاردئونی `#accordion`؛ فوتر شامل `Label1` (`Last Update: 25/11/1402`) و `#lblVersion` (`Version : 8.9.78.1`).

### کنترل‌های داخل `#UpdatePanel1` `[اندازه‌گیری]`

1. **جعبه هشدار:** `#cphMain_alertBox` (به‌طور پیش‌فرض `style="display:none;"`) و برچسب داخلی `#cphMain_alertBoxValue` به‌همراه دکمه‌ی بستن `.topClose`.
2. **ناحیه‌ی جستجو (`fieldset` با عنوان «تعیین بازه زمانی و وعده غذایی»):**

| شناسه HTML | نام فیلد فرم (`name`) | توضیح | مقادیر و ویژگی‌ها |
|---|---|---|---|
| `#cphMain_txtDate` | `ctl00$cphMain$txtDate` | تاریخ جلالی | اجباری (`required`)، الگوی اعتبارسنجی `((0[1-9]\|[12][0-9]\|3[01])[- /.](0[1-9]\|1[012])[- /.](1)\d\d\d\|(1)\d\d\d[- /.](0[1-9]\|1[012])[- /.](0[1-9]\|[12][0-9]\|3[01]))`، datepicker جلالی jQuery، مقدار پیش‌فرض تاریخ امروز (مثل `1405/09/03`) |
| `#cphMain_drpReserveType` | `ctl00$cphMain$drpReserveType` | نوع رزرو | `0` = «رزرو هفتگی» *(پیش‌فرض)*، `1` = «روزفروش» |
| `#cphMain_drpFoodType` | `ctl00$cphMain$drpFoodType` | وعده غذایی | `1` = «صبحانه» *(گزینه‌ی اول پیش‌فرض)*، `2` = «ناهار»، `3` = «شام» |
| `#cphMain_btnFoodReload` | `ctl00$cphMain$btnFoodReload` | دکمه‌ی «بارگذاری غذا» | `<input type="submit" value="بارگذاری غذا" />` |

3. **ناحیه‌ی نتیجه (`fieldset#cphMain_fldsetResult`):**  
   **نکته‌ی کلیدی:** این `fieldset` در `GET` اولیه **اصلاً وجود ندارد** و تنها پس از یک «بارگذاری غذا»ی موفق با رزرو موجود در DOM ظاهر می‌شود.

| شناسه HTML | برچسب کنار آن | مثال (ماسک‌شده) | توضیح |
|---|---|---|---|
| `#cphMain_hdnReservationId` | — *(مخفی)* | `1234567` | `<input type="hidden" name="ctl00$cphMain$hdnReservationId">`؛ شناسه‌ی رزرو سروری |
| `#cphMain_lblStatus` | `وضعیت:` | `این غذا رزرو شده است` / `این غذا در سبد غذایی برای فروش قرار دارد` | در پاسخ بلافاصله‌ی فروش و حذف **کهنه** است! |
| `#cphMain_lblFood` | `نام غذا:` | `نام غذای نمونه` | در صورت چندغذا بودن با `, ` جدا می‌شود `[اندازه‌گیری]` |
| `#cphMain_lblSelf` | `سلف:` | `سلف نمونه` | نام سلف سرویس |
| `#cphMain_lblFoodcount` | `تعداد:` | `1` | تعداد پرس رزروشده |
| `#cphMain_lblReservePrice` | `قیمت رزرو غذا:` | `180,000 ریال` | قیمت اصلی رزرو |
| `#cphMain_lblBasketSalePrice` | `قیمت فروش به سبد:` | `180,000 ریال` | قیمت فروش به سبد غذایی |
| `#cphMain_btnAccept` یا `#cphMain_btnCancel` | — *(دکمه‌ی submit)* | `فروش به سبد غذا` / `حذف از سبد غذا` | `ctl00$cphMain$btnAccept` یا `ctl00$cphMain$btnCancel` |

---

## ۴. ماشین حالت و رفتار «برچسب کهنه» `[اندازه‌گیری]`

چرخه‌ی وضعیت برای هر وعده‌ی رزروشده به‌صورت کامل و دو سمتی در ضبط دوم تأیید شده است:

```text
   ┌───────────────────────────┐
   │  GET صفحه (بدون نتیجه)    │
   │  fldsetResult وجود ندارد  │
   └─────────────┬─────────────┘
                 │  POST: btnFoodReload
                 ▼
   ┌──────────────────────────────────────────────────────────┐
   │  وضعیت رزروشده (Reserved)                                │
   │  - دکمه: btnAccept («فروش به سبد غذا»)                  │
   │  - lblStatus پس از بارگذاری: «این غذا رزرو شده است»     │
   │  - (تله بلافاصله بعد از حذف: lblStatus هنوز «در سبد...») │
   └─────────────┬──────────────────────────────▲─────────────┘
                 │                              │
   POST: btnAccept                        POST: btnCancel
   + toast فروش                           + toast حذف
                 │                              │
                 ▼                              │
   ┌────────────────────────────────────────────┴─────────────┐
   │  وضعیت در سبد فروش (InBasket)                            │
   │  - دکمه: btnCancel («حذف از سبد غذا»)                   │
   │  - lblStatus پس از بارگذاری: «این غذا در سبد غذایی...»  │
   │  - (تله بلافاصله بعد از فروش: lblStatus هنوز «رزرو شده»)│
   └──────────────────────────────────────────────────────────┘
```

### جدول کامل حالت‌های مشاهده‌شده و پیش‌بینی‌شده

| حالت | مقدار `cphMain_lblStatus` | دکمه‌ی عمل در DOM | وضعیت اطمینان |
|---|---|---|---|
| **بارگذاری رزرو موجود و فروخته‌نشده** | `این غذا رزرو شده است` | `ctl00$cphMain$btnAccept` (`value="فروش به سبد غذا"`) | `[اندازه‌گیری]` |
| **بلافاصله در پاسخ Delta فروش موفق** | **کهنه:** `این غذا رزرو شده است` | `ctl00$cphMain$btnCancel` (`value="حذف از سبد غذا"`) | `[اندازه‌گیری]` |
| **بارگذاری مجدد بعد از فروش** | `این غذا در سبد غذایی برای فروش قرار دارد` | `ctl00$cphMain$btnCancel` (`value="حذف از سبد غذا"`) | `[اندازه‌گیری]` |
| **بلافاصله در پاسخ Delta حذف موفق** | **کهنه:** `این غذا در سبد غذایی برای فروش قرار دارد` | `ctl00$cphMain$btnAccept` (`value="فروش به سبد غذا"`) | `[اندازه‌گیری]` |
| **بارگذاری مجدد بعد از حذف** | `این غذا رزرو شده است` | `ctl00$cphMain$btnAccept` (دقیقاً مشابه پیش از فروش؛ با همان `hdnReservationId`) | `[اندازه‌گیری]` |
| **بدون رزرو / وعده گذشته / غذای خورده‌شده** | ضبط نشده | ضبط نشده | `[تأییدنشده]` |

> **قانون الزامی پارسر:** برای تشخیص وضعیت آیتم در DOM یا Delta، **همیشه وجود دکمه‌ی `ctl00$cphMain$btnAccept` در برابر `ctl00$cphMain$btnCancel` را ملاک قرار دهید** و هرگز بر متن فارسی `lblStatus` به تنهایی تکیه نکنید. برای همگام‌سازی کامل، پس از هر فروش یا حذف موفق یک بار `btnFoodReload` را فراخوانی کنید.

---

## ۵. پروتکل کامل AJAX Delta برای سه عملیات `[اندازه‌گیری]`

### هدرهای مشترک هر سه درخواست
```http
POST https://jeton.bums.ac.ir/FoodBasket/FoodBasketSale.aspx
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
X-MicrosoftAjax: Delta=true
X-Requested-With: XMLHttpRequest
Cache-Control: no-cache
Accept: */*
Origin: https://jeton.bums.ac.ir
Referer: https://jeton.bums.ac.ir/FoodBasket/FoodBasketSale.aspx
Cookie: <کوکی نشست شامل ASP.NET_SessionId و ...>
```
*(توضیح: مرورگر هدرهای `sec-ch-ua*`، `Sec-Fetch-*` و `save-data` را نیز ارسال کرده که در فیکسچرها حذف شده‌اند؛ ضرورت سرور برای `Origin` و `Referer` در `[تأییدنشده]` قرار دارد، اما ارسال آن‌ها مطابق رفتار مرورگر کم‌هزینه و ایمن است.)*

### قالب پاسخ سرور (Microsoft Ajax Delta)
پاسخ با کد `200 OK`، `Content-Type: text/plain; charset=utf-8` و `Cache-Control: private,no-store` در قالب استاندارد `طول|نوع|شناسه|محتوا|` (با اندازه‌گیری **طول بر حسب کاراکتر**؛ جزئیات در [پروتکل دلتا](./viewstate-delta-ajax.md)) بازمی‌گردد و شامل بخش‌های زیر است:
- `updatePanel` با شناسه `UpdatePanel1` (HTML به‌روزشده‌ی کنترل‌های داخل پنل)
- `hiddenField` برای: `__EVENTTARGET`، `__EVENTARGUMENT`، `__VIEWSTATE`، `__VIEWSTATEGENERATOR` (`92079A9C`)، `__VIEWSTATEENCRYPTED`
- متادیتای اسکریپت‌منیجر: `asyncPostBackControlIDs`، `postBackControlIDs`، `updatePanelIDs` (`tctl00$UpdatePanel1,UpdatePanel1`)، `childUpdatePanelIDs`، `panelsToRefreshIDs` (`ctl00$UpdatePanel1,UpdatePanel1`)، `asyncPostBackTimeout` (`400`) و `formAction` (`./FoodBasketSale.aspx`)
- در عملیات فروش و حذف: `scriptStartupBlock` (`ScriptContentNoTags`) حاوی دستور `toastify(...)`.

---

### الف) بارگذاری غذا (`btnFoodReload`) `[اندازه‌گیری]`

این درخواست دارای **دو شکل بدنه** در مرورگر است که سرور **هر دو را دقیقاً به یک شکل می‌پذیرد** و پاسخ یکسان می‌دهد:

#### شکل ۱: بدنه‌ی ۱۱ فیلدی (هنگام نبود `fldsetResult` در DOM — پس از GET اولیه)
نمونه حجم در ضبط‌ها: `1352`، `1338`، `1340`، `1358` بایت.
```text
ctl00$ScriptManager=ctl00$UpdatePanel1|ctl00$cphMain$btnFoodReload
__EVENTTARGET=
__EVENTARGUMENT=
__VIEWSTATE=<از HTML صفحه (۸۹۶ کاراکتر)>
__VIEWSTATEGENERATOR=92079A9C
__VIEWSTATEENCRYPTED=
ctl00$cphMain$txtDate=1405/09/03
ctl00$cphMain$drpReserveType=0
ctl00$cphMain$drpFoodType=2
__ASYNCPOST=true
ctl00$cphMain$btnFoodReload=بارگذاری غذا
```

#### شکل ۲: بدنه‌ی ۱۲ فیلدی (وقتی `fldsetResult` از بارگذاری/عمل قبلی در DOM هست)
مرورگر کنترل‌های موفق فرم را به‌ترتیب قرارگیری در DOM ارسال می‌کند؛ بنابراین `ctl00$cphMain$hdnReservationId` نیز همراه بدنه ارسال می‌شود و ترتیب فیلدها تغییر می‌یابد (نمونه حجم: `1713` بایت با `VS` ۱۲۴۰ کاراکتری، یا `1695` بایت با `VS` ۱۱۹۶ کاراکتری):
```text
ctl00$ScriptManager=ctl00$UpdatePanel1|ctl00$cphMain$btnFoodReload
ctl00$cphMain$txtDate=1405/09/04
ctl00$cphMain$drpReserveType=0
ctl00$cphMain$drpFoodType=2
ctl00$cphMain$hdnReservationId=<شناسه‌ی رزرو فعلی در DOM>
__EVENTTARGET=
__EVENTARGUMENT=
__VIEWSTATE=<از آخرین پاسخ Delta>
__VIEWSTATEGENERATOR=92079A9C
__VIEWSTATEENCRYPTED=
__ASYNCPOST=true
ctl00$cphMain$btnFoodReload=بارگذاری غذا
```
- **نتیجه‌ی مقایسه:** فرستادن یا نفرستادن `hdnReservationId` در درخواست بارگذاری بر خروجی سرور تأثیری نداشت و پاسخ `5196` بایتی دقیقاً تکرار شد؛ بااین‌حال بهترین الگو، ساخت بدنه دقیقاً مطابق کنترل‌های موفق فرم جاری (همراه با آخرین `__VIEWSTATE`) است.
- **پاسخ:** HTML کامل `UpdatePanel1` حاوی `#cphMain_fldsetResult`، مقدار `#cphMain_hdnReservationId` (که باید برای فروش/حذف ذخیره شود) و `__VIEWSTATE` جدید (`1176` یا `1196` کاراکتر در وضعیت رزروشده؛ `1216` یا `1240` کاراکتر در وضعیت در سبد).

---

### ب) فروش به سبد (`btnAccept`) `[اندازه‌گیری]`

بدنه دارای **۱۲ فیلد** (حجم اندازه‌گیری‌شده: `1699` بایت برای `1405/09/03` و `1721` بایت برای `1405/09/04`):
```text
ctl00$ScriptManager=ctl00$UpdatePanel1|ctl00$cphMain$btnAccept
ctl00$cphMain$txtDate=1405/09/03
ctl00$cphMain$drpReserveType=0
ctl00$cphMain$drpFoodType=2
ctl00$cphMain$hdnReservationId=<شناسه‌ی رزرو از پاسخ بارگذاری>
__EVENTTARGET=
__EVENTARGUMENT=
__VIEWSTATE=<از آخرین پاسخ Delta (بارگذاری)>
__VIEWSTATEGENERATOR=92079A9C
__VIEWSTATEENCRYPTED=
__ASYNCPOST=true
ctl00$cphMain$btnAccept=فروش به سبد غذا
```
- **نکات حیاتی بدنه:**
  - `__VIEWSTATE` باید دقیقاً از **آخرین پاسخ Delta مرحله‌ی بارگذاری** گرفته شود (`1176` یا `1196` کاراکتر)، نه ViewState اولیه‌ی صفحه (`896`).
  - فقط دکمه‌ی `ctl00$cphMain$btnAccept` ارسال می‌شود؛ `btnFoodReload` نباید در بدنه باشد.
  - مقادیر `txtDate`، `drpReserveType` و `drpFoodType` باید مطابق همان وعده‌ی بارگذاری‌شده باشند.
- **پاسخ موفق (`5359` / `5386` بایت Delta):**
  - تغییر دکمه‌ی پنل به `btnCancel` («حذف از سبد غذا»)، اما **`lblStatus` کهنه** («این غذا رزرو شده است»).
  - اسکریپت toast در `scriptStartupBlock`:
    ```javascript
    toastify('success', 'این غذا به سبد غذایی جهت فروش اضافه شد', 'عملیات موفق!', 'toast-bottom-right', 'true');
    ```
  - **الزام بعدی:** برای دریافت متن واقعی «این غذا در سبد غذایی برای فروش قرار دارد»، یک بار دیگر `btnFoodReload` را ارسال کنید.

---

### ج) حذف از سبد (`btnCancel`) `[اندازه‌گیری]`

بدنه دقیقاً ساختار ۱۲ فیلدی فروش را دارد و فقط `ScriptManager` و نام/مقدار دکمه تغییر می‌کنند (حجم اندازه‌گیری‌شده: `1755` بایت در ضبط دوم):
```text
ctl00$ScriptManager=ctl00$UpdatePanel1|ctl00$cphMain$btnCancel
ctl00$cphMain$txtDate=1405/09/04
ctl00$cphMain$drpReserveType=0
ctl00$cphMain$drpFoodType=2
ctl00$cphMain$hdnReservationId=<شناسه‌ی رزرو از پاسخ بارگذاری>
__EVENTTARGET=
__EVENTARGUMENT=
__VIEWSTATE=<از آخرین پاسخ Delta؛ در نمونه ۱۲۴۰ کاراکتر>
__VIEWSTATEGENERATOR=92079A9C
__VIEWSTATEENCRYPTED=
__ASYNCPOST=true
ctl00$cphMain$btnCancel=حذف از سبد غذا
```
- **پاسخ موفق (`5447` بایت Delta):**
  - تغییر دکمه‌ی پنل به `btnAccept` («فروش به سبد غذا»)، اما **`lblStatus` کهنه** («این غذا در سبد غذایی برای فروش قرار دارد»).
  - اسکریپت toast در `scriptStartupBlock`:
    ```javascript
    toastify('success', 'این غذا از سبد غذایی حذف شد', 'عملیات موفق!', 'toast-bottom-right', 'true');
    ```
  - **نکات مشاهده‌شده:**
    1. ViewState حذف مستقیماً از پاسخ Delta بارگذاری پس از فروش (`1240` کاراکتر) برداشته شد و بدون هیچ `GET` تازه‌ای توسط سرور پذیرفته شد. `[اندازه‌گیری]`
    2. `hdnReservationId` پس از حذف همچنان ثابت ماند و حذف از سبد، رزرو غذا را پاک نکرد؛ پس از فراخوانی `btnFoodReload` وضعیت مجدداً «این غذا رزرو شده است» با `btnAccept` بود. `[اندازه‌گیری]`
    3. اثر حذف بر اعتبار یا وجود مهلت زمانی پس از خرید توسط دیگری در این ضبط‌ها دیده نشده است. `[تأییدنشده]`

---

### د) زنجیره‌ی ViewState `[اندازه‌گیری]`

```text
GET صفحه          ──▶  VS (896)
                       │
                       ▼  POST btnFoodReload
پاسخ بارگذاری     ──▶  VS (1176 یا 1196)
                       │
                       ▼  POST btnAccept
پاسخ فروش         ──▶  VS (1176 یا 1196)
                       │
                       ▼  POST btnFoodReload (بدون GET تازه)
پاسخ بارگذاری مجدد ──▶  VS (1216 یا 1240)
                       │
                       ▼  POST btnCancel
پاسخ حذف          ──▶  VS (1240)
                       │
                       ▼  GET تازه (اختیاری)
بازنشانی چرخه     ──▶  VS (896) ──▶ POST btnFoodReload ──▶ VS (1196)
```

- **هرگز طول `__VIEWSTATE` را ثابت فرض نکنید** (بین دو ضبط برای حالت مشابه `1176` در برابر `1196` و `1216` در برابر `1240` کاراکتر مشاهده شد).
- همیشه آخرین مقدار `hiddenField` برای `__VIEWSTATE` (و در صورت تغییر، سایر فیلدهای مخفی) را از آخرین پاسخ Delta ذخیره کرده و `__VIEWSTATEGENERATOR` را به‌صورت پویا از HTML/Delta صفحه استخراج کنید (`92079A9C`).

---

## ۶. ارتباط با صفحه‌ی رزرو (`Reservation.aspx`) و انتخاب وعده/تاریخ

- **نبود فهرست در `FoodBasketSale.aspx`:** صفحه فهرست رزروهای دانشجو را نمی‌آورد؛ بنابراین کلاینت باید ابتدا از جدول‌های صبحانه/ناهار/شام در [صفحه‌ی رزرو](./meal-reservation-api.md) تاریخ جلالی (`yyyy/MM/dd`) و نوع وعده‌ی ردیف‌های تیک‌خورده را استخراج کند و سپس برای هر اسلات `txtDate` و `drpFoodType` را ارسال نماید. `[اندازه‌گیری]`
- **نگاشت وعده (`drpFoodType`):** `1` = صبحانه، `2` = ناهار، `3` = شام. `[اندازه‌گیری]`
- **نگاشت نوع رزرو (`drpReserveType`):** `0` = رزرو هفتگی (`Reservation.aspx`) در ضبط آزمایش و تأیید شد؛ مقدار `1` = روزفروش (احتمالاً متناظر با `DailyReservation.aspx` «خرید روزفروش») آزمایش نشده است `[تأییدنشده]`.
- **وضعیت سبد در صفحه‌ی رزرو منعکس نمی‌شود `[اندازه‌گیری]`:**  
  پس از فروش ناهار روز `1405/09/03` در ضبط اول، بررسی `GET /Reservation/Reservation.aspx` (ساعت `11:24:18`) نشان داد که ردیف روز فروخته‌شده از نظر آیکن و متن وضعیت **دقیقاً یکسان** با ردیف روز بعدی («رزرو شده، خورده نشده، غیرفعال») است.  
  *پیامد طراحی:* برای نمایش وضعیت «در سبد» در UI، کلاینت یا باید هنگام بازشدن صفحه‌ی «فروش به سبد» به ازای هر رزرو اسلات موردنظر یک درخواست `btnFoodReload` بزند (هزینه‌ی ۱ درخواست برای هر وعده) یا بارگذاری را فقط هنگام انتخاب تاریخ/وعده توسط کاربر انجام دهد تا از بار اضافه روی سرور جلوگیری شود.
- **محدوده‌ی زمانی فروش:** در ضبط‌ها، ناهار همان روز در ساعت `11:24` و ناهار روز بعد (`1405/09/04`) هر دو قابل فروش بودند؛ ساعت دقیق cutoff یا قوانین تاریخ‌های گذشته نامشخص است `[تأییدنشده]`.

---

## ۷. خروج از صفحه‌ی `FoodBasketSale.aspx` `[اندازه‌گیری]`

در ضبط اول (`۱۱:۲۴:۴۲`)، خروج از همین صفحه با یک درخواست `POST` عادی (غیر AJAX، بدون `X-MicrosoftAjax` و بدون `__ASYNCPOST`) با **۹ فیلد کنترل موفق فرم** (`Content-Length: 1539`) انجام شد و سرور پاسخ `302 Found` با `Location: /Login.aspx` برگرداند:

```text
ctl00$cphMain$txtDate=1405/09/03
ctl00$cphMain$drpReserveType=0
ctl00$cphMain$drpFoodType=2
ctl00$cphMain$hdnReservationId=RESERVATION_ID
__EVENTTARGET=ctl00$logout
__EVENTARGUMENT=
__VIEWSTATE=<۱۲۱۶ کاراکتر؛ آخرین مقدار Delta>
__VIEWSTATEGENERATOR=92079A9C
__VIEWSTATEENCRYPTED=
```

دکمه‌های `submit` (`btnFoodReload`, `btnAccept`, `btnCancel`) در بدنه وجود نداشتند. این مشاهده **الگوریتم عمومی خروج** در [سند خروج سرور و الگوی پویا](./server-logout-flow.md) (ارسال همه‌ی کنترل‌های موفق غیر از دکمه‌ها با `__EVENTTARGET=ctl00$logout`) را به‌طور کامل تأیید می‌کند. `[اندازه‌گیری]`

---

## ۸. راهنمای پیاده‌سازی در کلاینت اندروید و مدیریت خطا

> **وضعیت فعلی در کد برنامه:** در نسخه‌ی فعلی کلاینت اندروید، ویژگی «سبد غذایی» (`FoodBasketSale.aspx` و `FoodBasketBuy.aspx`) هنوز پیاده‌سازی نشده است و این بخش مرجع رسمی پیاده‌سازی آتی محسوب می‌شود.

### مدل‌های داده‌ی پیشنهادی
```kotlin
enum class BasketReserveType(val formValue: String) {
    WEEKLY("0"),
    DAILY_SALE("1") // تأییدنشده در ضبط
}

enum class BasketActionButton {
    ACCEPT, // ctl00$cphMain$btnAccept -> آماده برای فروش به سبد
    CANCEL  // ctl00$cphMain$btnCancel -> در سبد و آماده برای حذف
}

enum class BasketStatus {
    RESERVED,   // btnAccept در DOM
    IN_BASKET,  // btnCancel در DOM
    UNKNOWN
}

data class BasketSlot(
    val jalaliDate: String, // yyyy/MM/dd
    val mealType: Int,      // 1=صبحانه, 2=ناهار, 3=شام
    val reserveType: BasketReserveType = BasketReserveType.WEEKLY
)

data class BasketItemState(
    val reservationId: String,
    val status: BasketStatus,
    val actionButton: BasketActionButton,
    val foodName: String,
    val selfName: String,
    val count: Int,
    val reservePrice: String,
    val salePrice: String
)
```

### گام‌های الگوریتم کلاینت
1. **باز کردن صفحه (`GET`):** دریافت `/FoodBasket/FoodBasketSale.aspx`؛ استخراج `__VIEWSTATE`، `__VIEWSTATEGENERATOR` (`92079A9C`)، (در صورت وجود `__EVENTVALIDATION`) و مقدار پیش‌فرض `#cphMain_txtDate`. ثبت صفحه‌ی جاری در `LastPageSnapshot` برای خروج پویا.
2. **بارگذاری غذا (`btnFoodReload`):** ارسال `POST` الف (مطابق کنترل‌های موفق فرم جاری و آخرین ViewState). پارس `UpdatePanel1` در پاسخ Delta:
   - اگر `fieldset#cphMain_fldsetResult` نبود ← نمایش «برای این تاریخ و وعده رزروی یافت نشد» (و در صورت پر بودن `#cphMain_alertBoxValue` نمایش متن آن؛ رفتار نبود رزرو در `[تأییدنشده]` است).
   - اگر وجود داشت ← استخراج `hdnReservationId`، نام غذا، سلف، تعداد، قیمت‌ها و تشخیص وضعیت از **نام دکمه‌ی عمل (`btnAccept` یا `btnCancel`)**، نه `lblStatus`. به‌روزرسانی ViewState از `hiddenField` دلتا.
3. **فروش به سبد (`btnAccept`):** فقط وقتی دکمه‌ی جاری `btnAccept` است و **صرفاً پس از تأیید صریح کاربر** (در دیالوگ تأیید حتماً نام غذا، سلف، تاریخ/وعده و قیمت فروش به سبد نمایش داده شود). ارسال `POST` ب با `hdnReservationId` و آخرین ViewState؛ بررسی پیام موفقیت در `toastify`.
4. **حذف از سبد (`btnCancel`):** فقط وقتی دکمه‌ی جاری `btnCancel` است و **پس از تأیید صریح کاربر**. ارسال `POST` ج؛ بررسی پیام موفقیت در `toastify`.
5. **بارگذاری مجدد پس از هر عمل موفق:** بلافاصله پس از موفقیت فروش یا حذف، یک بار `btnFoodReload` را ارسال کنید تا `lblStatus` و وضعیت نهایی از سرور همگام و به کاربر نمایش داده شوند.
6. **مدیریت خطاها و Timeout:**
   - استخراج toast با الگوی `toastify\('(\w+)',\s*'(.*?)',\s*'(.*?)'` (پرچم `DOTALL`) و خواندن `#cphMain_alertBoxValue` پس از هر Delta.
   - اگر `toastify('error', ...)` دریافت شد ← نمایش پیام سرور به کاربر.
   - اگر پاسخ بدون `toastify` بود یا وضعیت غیرمنتظره رخ داد ← نمایش خطای عمومی و واکشی مجدد `GET` برای تازه‌سازی ViewState.
   - **خطای شبکه یا Timeout در میانه‌ی فروش/حذف:** وضعیت سمت سرور نامعلوم است؛ **پیش از هر تصمیم یا تلاش مجدد، ابتدا `btnFoodReload` بزنید** تا ببینید عمل در سرور ثبت شده است یا خیر.

### جدول پیام‌های شناخته‌شده `toastify`
| نوع | عنوان | متن پیام | وضعیت |
|---|---|---|---|
| `success` | `عملیات موفق!` | `این غذا به سبد غذایی جهت فروش اضافه شد` | `[اندازه‌گیری]` |
| `success` | `عملیات موفق!` | `این غذا از سبد غذایی حذف شد` | `[اندازه‌گیری]` |
| `error` / `warning` | — | پیام‌های اعتبارسنجی (نبود رزرو، گذشت مهلت، خریداری‌شده توسط دانشجوی دیگر، محدودیت اعتبار و ...) | `[تأییدنشده]` |

### اصول ایمنی و هم‌زمانی (الزامی برای عملیات مالی/بازارچه)
- استفاده از **همان `Mutex` اشتراکی درخواست‌های شبکه** و جلوگیری از اجرای هم‌زمان خروج هنگام انجام تراکنش سبد.
- **ممنوعیت مطلق حلقه‌ی خودکار یا Retry خودکار** برای درخواست‌های `btnAccept` و `btnCancel`؛ هر عملیات فروش یا حذف باید منحصراً با لمس صریح کاربر انجام شود.
- فروش و حذف دارای **اثر مالی و تغییر وضعیت در بازارچه‌ی عمومی سایر دانشجویان** هستند (غذا پس از فروش بلافاصله برای خرید دیگران نمایش داده می‌شود)؛ بنابراین باید از **Debounce لمس مکرر** استفاده شود و دکمه‌ی عمل تا پایان پاسخ غیرفعال گردد.

---

## ۹. حریم خصوصی و امنیت (`LogSanitizer` و حافظه)

1. **پاک‌سازی در `LogSanitizer` و `AppLogger`:**
   - مقادیر `hdnReservationId`، نام غذا (`lblFood`)، نام سلف (`lblSelf`)، قیمت‌ها، `__VIEWSTATE`، کوکی‌ها و بدنه‌ی کامل Delta/HTML **هرگز** نباید در لاگ‌های برنامه ذخیره شوند.
   - در `LogSanitizer` الگوهای `hdnReservationId=` و `ctl00$cphMain$hdnReservationId=` را به‌صورت صریح به فهرست ماسک اضافه کنید.
   - تنها ثبت متادیتای غیرحساس (نوع عملیات: reload/sell/cancel، نوع وضعیت: Reserved/InBasket، نوع toast: success/error و کد وضعیت HTTP) مجاز است.
2. **صحت و یکپارچگی شناسه‌ی رزرو:**
   - مقدار `hdnReservationId` باید **تنها از پاسخ بارگذاری سرور برای همان اسلات و همان نشست کاربر** خوانده شود. از ساخت شناسه‌ی جعلی یا بازاستفاده از شناسه‌ی وعده‌های دیگر اکیداً خودداری کنید.
3. **حفظ داده‌ها فقط در حافظه‌ی موقت نشست:**
   - وضعیت سبد غذایی نباید در دیسک یا دیتابیس محلی کش شود؛ تمام وضعیت‌ها تنها در حافظه‌ی رم نشست جاری نگهداری و هم‌زمان با خروج کاربر فوراً پاک شوند.

---

## ۱۰. برنامه‌ی آزمون پیشنهادی

1. **تست‌های واحد پارسر (بر مبنای ۵ فیکسچر Delta + صفحه‌ی HTML):**
   - استخراج صحیح `hdnReservationId`، نام غذا، سلف، تعداد، قیمت رزرو، قیمت فروش و تشخیص درست `btnAccept` در برابر `btnCancel` از فیکسچرهای `FoodBasket_reload_delta_sanitized.txt`، `FoodBasket_sell_delta_sanitized.txt`، `FoodBasket_reload_after_sell_delta_sanitized.txt`، `FoodBasket_cancel_delta_sanitized.txt` و `FoodBasket_reload_after_cancel_delta_sanitized.txt`.
   - **آزمون کلیدی «برچسب کهنه» در هر دو جهت:**
     - در `FoodBasket_sell_delta_sanitized.txt`: متن `lblStatus` برابر «این غذا رزرو شده است» است، ولی پارسر به‌دلیل وجود `btnCancel` باید وضعیت را **`InBasket`** تشخیص دهد.
     - در `FoodBasket_cancel_delta_sanitized.txt`: متن `lblStatus` برابر «این غذا در سبد غذایی برای فروش قرار دارد» است، ولی پارسر به‌دلیل وجود `btnAccept` باید وضعیت را **`Reserved`** تشخیص دهد.
   - پارس متن `toastify` فروش («این غذا به سبد غذایی جهت فروش اضافه شد») و حذف («این غذا از سبد غذایی حذف شد»).
   - استخراج `__VIEWSTATE` و سایر `hiddenField`ها از فریم‌های Delta با شمارش کاراکتری دقیق.
2. **تست‌های ساخت بدنه:**
   - انطباق تعداد فیلدها (۱۱ و ۱۲ فیلدی)، ترتیب دقیق کنترل‌های فرم و وجود **تنها یک دکمه‌ی عمل** برای بارگذاری، فروش و حذف مطابق بخش ۵ (از جمله تطابق بدنه‌ی حذف با الگوی `1755` بایتی با شناسه و ViewState هم‌طول ساختگی).
3. **تست‌های یکپارچه با `MockWebServer`:**
   - چرخه‌ی کامل دو سمتی: `GET` ← بارگذاری ← فروش ← بارگذاری ← حذف ← بارگذاری.
   - سناریوی نبود `fieldset#cphMain_fldsetResult` در پاسخ بارگذاری.
   - سناریوی خطای `toastify('error', ...)`.
   - سناریوی Timeout در وسط درخواست فروش ← اطمینان از فراخوانی `btnFoodReload` پیش از هر تصمیم دیگر.
   - خروج ۹ فیلدی از صفحه‌ی `FoodBasketSale.aspx` و پاسخ `302 /Login.aspx`.
4. **تست UI / Compose:**
   - اطمینان از اینکه بدون تأیید صریح کاربر در دیالوگ، هیچ درخواست فروش یا حذف ارسال نمی‌شود و لمس دوگانه debounce می‌گردد.
5. **تست روی دستگاه واقعی (حساب کاربری واقعی):**
   - فروش و حذف یک وعده رزروشده، بررسی اسلات بدون رزرو، وعده‌ی گذشته، رزرو روزفروش (`drpReserveType=1`) و رزرو استفاده‌شده/خورده‌شده.

---

## ۱۱. فهرست یکجا از موارد تأییدنشده (`[تأییدنشده]`)

1. **صفحه‌ی خرید از سبد غذایی (`FoodBasketBuy.aspx`):** ساختار HTML، فهرست غذاهای عرضه‌شده در سبد، پروتکل جستجو/خرید و اثر آن بر اعتبار.
2. **خطاهای فروش و حذف:** متن دقیق `toastify` یا `#cphMain_alertBoxValue` در حالت‌های نبود رزرو برای تاریخ/وعده، گذشتن زمان مجاز، رزرو خورده‌شده، غذای خریداری‌شده توسط دانشجوی دیگر یا چند رزرو هم‌زمان در یک وعده (در ضبط‌ها تنها یک `hdnReservationId` دیده شد).
3. **رفتار نوع رزرو روزفروش (`drpReserveType=1`):** امکان فروش یا حذف غذاهای خریداری‌شده از مسیر `DailyReservation.aspx`.
4. **قاعده‌ی قیمت فروش به سبد:** در نمونه‌های ضبط‌شده `lblBasketSalePrice` برابر `lblReservePrice` (`180,000 ریال`) بود؛ مشخص نیست در همه موارد برابر است یا کارمزد/تخفیف اعمال می‌شود.
5. **اثر فروش و حذف روی اعتبار و تراکنش‌ها:** در ضبط‌ها صفحه‌ی اعتبار یا `MyTransaction.aspx` پس از فروش/حذف بررسی نشد (از جمله اینکه آیا فروش بلافاصله اعتبار را برمی‌گرداند یا پس از خرید توسط دیگری، و حذف اعتبار بازگشتی را کسر می‌کند یا نه).
6. **محدودیت زمانی فروش و حذف:** ساعت دقیق cutoff برای وعده‌های امروز (ناهار امروز در `11:24` قابل فروش بود) و وعده‌های آینده.
7. **امکان حذف پس از خریده‌شدن توسط شخص دیگر:** آیا پس از فروش رفتن غذا به خریدار دیگر، `btnCancel` خطا می‌دهد یا دیگر نمایش داده نمی‌شود؟
8. **حساسیت سرور به هدرهای `Origin` و `Referer`.**
9. **رفتار دروازه‌های پیام خوانده‌نشده و نظرسنجی اجباری هنگام درخواست مستقیم `GET /FoodBasket/FoodBasketSale.aspx`.**
