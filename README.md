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
- **Profiles:** har bir profil Main va Backup papkalaridan hamda ignore qoidalaridan iborat. Profillar `~/.unison/` ichida oddiy Unison `.prf` fayli sifatida saqlanadi.
- **Risk scan:** ikkala papka Unison'dan mustaqil tekshiriladi. Quyidagi holatlar topilsa, ular bo'yicha alohida qaror qilinmaguncha Apply ishlamaydi:
  - fayl hajmi 0 baytga tushgan yoki kamida 90% kamaygan;
  - bir vaqtning o'zida fayllarning 20% dan ko'pi yoki 50 tadan ortig'i o'zgargan;
  - fayl yoki papkani o'qib bo'lmagan.
- **Changes list:** o'zgarishlarni yo'nalish bo'yicha filter qilish, path, name, size yoki date bo'yicha sort qilish va kerak bo'lsa ayrim fayllarni shu safar o'tkazib yuborish mumkin.
- **Conflicts:** har bir fayl uchun alohida qaror berish mumkin:
  * Tegilmasin;
  * Main qolsin;
  * Backup qolsin;
  * merge qilinsin.
  Merge faqat text fayllar uchun ishlaydi va profil eski nusxalarni saqlashni yoqqan bo'lishi kerak.
- **Diff panel:** tanlangan faylning Main va Backup nusxalari yonma-yon yoki unified ko'rinishda ko'rsatiladi. O'zgargan belgilar ajratiladi. Binary fayllarda esa size va modification time ko'rsatiladi.
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
- **Profiles:** each profile contains a Main and Backup folder plus ignore rules. Profiles are stored in `~/.unison/` as regular Unison `.prf` files.
- **Risk scan:** both folders are checked independently from Unison. Apply stays disabled until a decision is made for each detected case:
  - a file dropped to 0 bytes or decreased by 90% or more;
  - more than 20% of the files or more than 50 files changed at once;
  - a file or folder could not be read.
- **Changes list:** changes can be filtered by direction, sorted by path, name, size or date, and individual files can be skipped for the current run.
- **Conflicts:** each file can have its own decision:
  * Leave it unchanged;
  * Keep Main;
  * Keep Backup;
  * Merge.
  Merge works only for text files and requires keeping previous copies in the profile.
- **Diff panel:** the Main and Backup copies of the selected file can be shown side by side or in a unified view. Changed characters are highlighted. Binary files show their size and modification time instead.
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