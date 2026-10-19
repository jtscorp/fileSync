# FileSync

[O'zbekcha](#-ozbekcha) · [English](#-english)

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-yellow?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/jtscorp)

***

## 🇺🇿 O'zbekcha

FileSync — [Unison](https://github.com/bcpierce00/unison) uchun grafik interfeys. U ikkita papkani ikki tomonlama sinxronlaydi. Masalan, noutbukdagi hujjatlar va tashqi diskdagi backup.

Unison'dan tashqari FileSync mustaqil **risk tekshiruvi** ham qiladi. Masalan, disk buzilgan yoki ko'p fayl tasodifan o'chirilgan bo'lsa, yaxshi nusxa avtomatik ravishda ustidan yozilib ketmasligi uchun Apply bosqichi bloklanadi.

### Imkoniyatlar

- **4 bosqichli:** Setup → Check → Review → Apply.\
  Check hech narsani o'zgartirmaydi, faqat nima o'zgarishini ko'rsatadi.
- **Profiles:** har bir profil Main va Backup papkalaridan hamda ignore qoidalaridan iborat. Profillar `~/.unison/` ichida oddiy Unison `.prf` fayli sifatida saqlanadi. Profilning sinxronlashlar tarixi uning yonidagi `<profil>.history` faylida turadi.
- **Risk scan:** ikkala papka Unison'dan mustaqil tekshiriladi. Quyidagi holatlar topilsa, ular bo'yicha alohida qaror qilinmaguncha Apply ishlamaydi:
  - fayl hajmi 0 baytga tushgan yoki kamida 90% kamaygan;
  - fayl mazmuni uning turiga zid: tasodifiy baytlarga aylangan (shifrlangan bo'lishi mumkin), boshi kengaytmasiga mos emas yoki hajmi o'zgarmagan holda butunlay nol baytlardan iborat;
  - ko'p fayl bir vaqtda yangi kengaytmaga o'tgan va asl nusxalari o'chgan (ransomware belgisi). Bunday guruhni butunlay shu safar o'tkazib yuborish mumkin;
  - odatdagidan ko'p fayl o'zgargan. Profil 10 marta sinxronlangandan keyin uning o'z me'yori ishlatiladi, ungacha fayllarning 20% dan ko'pi yoki 50 tadan ortig'i;
  - fayl yoki papkani o'qib bo'lmagan.
- **G'ayrioddiy o'zgarishlar:** 10 ta sinxronlashdan keyin model har bir o'zgarishni baholaydi va g'ayrioddiylarini sababi bilan belgilaydi (masalan, kechasi o'zgargan yoki yillar davomida tegilmagan fayl). Bu faqat belgi, Apply'ni bloklamaydi.
- **Changes list:** o'zgarishlar papka daraxti yoki birga o'zgargan fayllar guruhi ko'rinishida ko'rsatiladi. Ularni yo'nalish bo'yicha filter qilish, path, name, size, date yoki anomaliya bo'yicha sort qilish va kerak bo'lsa ayrim fayllarni, butun papka yoki guruhni shu safar o'tkazib yuborish mumkin.
- **Conflicts:** har bir fayl uchun alohida qaror berish mumkin:
  * Tegilmasin;
  * Main qolsin;
  * Backup qolsin;
  * merge qilinsin.
  Merge faqat text fayllar uchun ishlaydi va profil eski nusxalarni saqlashni yoqqan bo'lishi kerak.
- **Diff panel:** tanlangan faylning Main va Backup nusxalari yonma-yon yoki unified ko'rinishda ko'rsatiladi. O'zgargan belgilar ajratiladi. Binary fayllarda size, modification time va ikkala nusxaning birinchi 32 bayti ko'rsatiladi.
- **Panellar:** sidebar va diff panel kengligini sudrab o'zgartirish mumkin, kenglik keyingi safar ham saqlanadi.
- **3 til:** o'zbekcha, ruscha va inglizcha.

### O'rnatish

1. [Releases](https://github.com/jtscorpjaxon/fileSync/releases) sahifasidan o'z tizimingiz uchun installer'ni yuklab oling:
   * Linux — `.deb`
   * Windows — `.msi`
   * macOS — `.dmg`
   Java alohida o'rnatilishi shart emas, u ilova ichida mavjud.
2. Quyidagi 3 ta tashqi dasturni o'rnating. Ular installer ichiga qo'shilmagan:
   - `unison` — Check va Apply uchun. Bu bo'lmasa FileSync ishlamaydi;
   - `diff` — fayllar orasidagi line-by-line farqlarni ko'rish uchun;
   - `diff3` — conflict'larni merge qilish uchun.

Debian va Ubuntu'da:

```
sudo apt install unison diffutils
```

Ilova ishga tushganda kerakli dasturlar mavjudligini tekshiradi. Agar birortasi bo'lmasa, tegishli tizim uchun o'rnatish bo'yicha ko'rsatma beradi.

Installer'lar hozircha signed emas. Shu sababli Windows va macOS birinchi ishga tushirishda warning ko'rsatishi mumkin.

Linux'da ilova Unison 2.53.3 bilan test qilingan. Windows va macOS'da hozircha test qilinmagan.

### Source'dan build qilish

JDK 23 kerak.

```
./gradlew run          # ilovani ishga tushirish
./gradlew test         # testlarni ishga tushirish
./gradlew installer    # shu tizim uchun installer -> build/installer/out/
```

### Native executable

FileSync [GluonFX Gradle plugin](https://github.com/gluonhq/gluonfx-gradle-plugin) orqali native executable sifatida ham build qilinishi mumkin.

Buning uchun:

* `GRAALVM_HOME` ichida Gluon's GraalVM build bo'lishi kerak;
* `JAVA_HOME` ichida JDK 23 yoki undan past versiya bo'lishi kerak;
* GTK, X11 va media development package'lari o'rnatilgan bo'lishi kerak.

```
./native.sh build      # -> build/gluonfx/x86_64-linux/fileSync
./native.sh run
```

FXML o'zgargandan keyin `src/main/resources/META-INF/native-image/` ichidagi reflection config'ni GraalVM tracing agent orqali qayta generate qilish kerak. Aks holda native build ishga tushganda xatolik berishi mumkin.

***

## 🇺🇸 English

FileSync is a graphical interface for [Unison](https://github.com/bcpierce00/unison). It synchronizes two folders in both directions. For example, documents on a laptop and their backup on an external drive.

In addition to Unison, FileSync performs an independent **risk scan**. If a drive is failing or many files were accidentally deleted, Apply is blocked so the good copy cannot be silently overwritten.

### Features

- **4 steps:** Setup → Check → Review → Apply.\
  Check does not change anything. It only shows what is going to happen.
- **Profiles:** each profile contains a Main and Backup folder plus ignore rules. Profiles are stored in `~/.unison/` as regular Unison `.prf` files. A profile's sync history is kept beside it in `<profile>.history`.
- **Risk scan:** both folders are checked independently from Unison. Apply stays disabled until a decision is made for each detected case:
  - a file dropped to 0 bytes or decreased by 90% or more;
  - a file's content contradicts its type: it turned into random bytes (it may be encrypted), its header does not match its extension, or it is all zero bytes at an unchanged size;
  - many files got a new extension at once while their originals were deleted (a sign of ransomware). Such a group can be skipped as a whole for this run;
  - more files changed than usual. After 10 syncs the profile's own normal is used; until then, more than 20% of the files or more than 50 files;
  - a file or folder could not be read.
- **Unusual changes:** after 10 syncs a model scores every change and marks the unusual ones with a reason (for example, a file changed at night or untouched for years). This is only a mark; it does not block Apply.
- **Changes list:** changes are shown as a folder tree or as groups of files that changed together. They can be filtered by direction, sorted by path, name, size, date or anomaly, and individual files, whole folders or groups can be skipped for the current run.
- **Conflicts:** each file can have its own decision:
  * Leave it unchanged;
  * Keep Main;
  * Keep Backup;
  * Merge.
  Merge works only for text files and requires keeping previous copies in the profile.
- **Diff panel:** the Main and Backup copies of the selected file can be shown side by side or in a unified view. Changed characters are highlighted. Binary files show their size, modification time and the first 32 bytes of both copies instead.
- **Panes:** the sidebar and the diff panel can be dragged wider or narrower; the widths are kept for next time.
- **3 languages:** Uzbek, Russian and English.

### Install

1. Download the installer for your system from the [Releases](https://github.com/jtscorpjaxon/fileSync/releases) page:
   * Linux — `.deb`
   * Windows — `.msi`
   * macOS — `.dmg`
   Java does not need to be installed separately because it is bundled with the application.
2. Install these 3 external programs. They are not included in the installer:
   - `unison` — used for Check and Apply. FileSync cannot work without it;
   - `diff` — used to view line-by-line differences;
   - `diff3` — used to merge conflicts.

On Debian and Ubuntu:

```
sudo apt install unison diffutils
```

When the application starts, it checks whether the required programs are installed. If something is missing, it shows installation instructions for the current system.

The installers are currently unsigned. Because of this, Windows and macOS may show a warning on the first launch.

The application has been tested on Linux with Unison 2.53.3. Windows and macOS have not been tested yet.

### Build from source

JDK 23 is required.

```
./gradlew run          # launch the application
./gradlew test         # run tests
./gradlew installer    # installer for this system -> build/installer/out/
```

### Native executable

FileSync can also be built as a native executable using the [GluonFX Gradle plugin](https://github.com/gluonhq/gluonfx-gradle-plugin).

Requirements:

* Gluon's GraalVM build in `GRAALVM_HOME`;
* JDK 23 or lower in `JAVA_HOME`;
* GTK, X11 and media development packages.

```
./native.sh build      # -> build/gluonfx/x86_64-linux/fileSync
./native.sh run
```

After changing an FXML file, the reflection config in `src/main/resources/META-INF/native-image/` must be regenerated using the GraalVM tracing agent. Otherwise, the native build may fail when the application starts.