# HealthFit V1.7

Projeto Android em Kotlin + Jetpack Compose.

## Build local
Requer JDK 17 e Gradle 8.7.

## Build no GitHub
O workflow `.github/workflows/build-apk.yml`:
1. configura Java 17;
2. baixa Gradle 8.7;
3. executa `gradle assembleDebug` na raiz do projeto;
4. publica o APK como artefato `HealthFit-debug-apk`.

Importante: o workflow não entra em uma pasta `HealthFit/`. Os arquivos `settings.gradle.kts` e `build.gradle.kts` ficam na raiz do repositório.
