# Price Translator — Android MVP

Price Translator detects dollar prices in a selected photo, converts them to Russian rubles using an online exchange rate, replaces the detected price text, adds the used rate as a footer, and saves the resulting image.

## Implemented

- Kotlin + Jetpack Compose
- Android system photo picker
- ML Kit on-device text recognition
- USD price detection (`$19.99`, `USD 19.99`)
- Online USD→RUB reference rate
- Cached offline rate fallback
- Daily background rate refresh with WorkManager
- Converted prices drawn into the image
- Save to `Pictures/Price Translator`

GitHub Actions builds an installable debug APK and uploads it as the `PriceTranslator-APK` workflow artifact.
