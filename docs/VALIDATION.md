# Validation — p3 test.epub

Source: `p3 test.epub`

Observed:
- ZIP/EPUB container readable.
- `mimetype`: `application/epub+zip`.
- OPF: `OEBPS/content.opf`.
- XHTML: 1.
- Total ZIP entries: 88.
- `<p>`: 151.
- `<figure>`: 10.
- `<img>` references: 16.
- `<table>`: 5.
- CSS assets: 3 declared in the main OPF manifest.

Rebuild smoke test:
- Rebuilt EPUB without content replacements.
- ZIP test: no errors.
- First entry: `mimetype`.
- `mimetype` compression method: STORED (method 0).
- `mimetype` payload preserved as `application/epub+zip`.

Not verified in this environment:
- Android Gradle build / APK installation, because Android SDK and Gradle distribution are not installed.
- Live AI translation, because no user API endpoint/key was supplied.
