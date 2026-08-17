# PriceScan USA 🔍

Aplicación Android moderna para escanear productos (o buscar por nombre) y comparar precios en las principales tiendas de Estados Unidos (Walmart, Target, Aldi, Kroger, Amazon, Costco).

## ✨ Características Principales

- 📷 **Escáner de código de barras**: Escaneo con cámara rápido mediante ZXing.
- 🔍 **Búsqueda Flexible**: Busca por código de barras numérico o directamente por nombre del producto (ej: *"Milk"*, *"Doritos"*).
- 🖼️ **Carga de Imágenes Reales**: Descarga y muestra la foto real del producto usando Glide.
- 🤖 **Comparación Inteligente con IA (Gemini API)**: Cuando los datos reales de la comunidad no estén disponibles, un motor de Inteligencia Artificial (Gemini) estima los precios actuales en las tiendas de EE.UU.
- 🟢 **Distinción Transparente de Datos**: Muestra distintivos claros cuando la información es *🟢 Datos Reales Verificados* o *🤖 Estimado de IA de Mercado*.
- ⚡ **Rendimiento Optimizado**: Interfaz construida con `RecyclerView`, `ViewBinding` y arquitectura limpia.
- 📤 **Compartir y Copiar**: Exporta las comparativas fácilmente a WhatsApp, notas o correo.

## 🛠️ Compilando el proyecto

### Requisitos
- Android Studio Hedgehog (2023.1.1) o superior.
- JDK 17.
- Android SDK 34.

### Compilar localmente
```bash
# En Windows:
gradlew.bat assembleDebug

# En Linux/macOS:
./gradlew assembleDebug
```

## 🚀 Generar APK gratis en la nube (GitHub Actions)
1. Sube este código a tu repositorio en GitHub.
2. Ve a la pestaña **Actions**.
3. Selecciona el workflow **Build APK** y presiona **Run workflow**.
4. Descarga el APK generado directamente desde la sección **Artifacts**.

---
Desarrollado con Kotlin, OkHttp, Glide y Gemini AI 🇺🇸
