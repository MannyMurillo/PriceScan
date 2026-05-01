package com.pricescan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var currentProductName = ""
    private var currentPrices = listOf<Map<String, String>>()

    private lateinit var scannerCard: LinearLayout
    private lateinit var loadingSection: LinearLayout
    private lateinit var resultsSection: LinearLayout
    private lateinit var tvLoadingStep: TextView
    private lateinit var tvProductName: TextView
    private lateinit var tvBrand: TextView
    private lateinit var tvBarcode: TextView
    private lateinit var tvBestStore: TextView
    private lateinit var tvBestPrice: TextView
    private lateinit var tvSavings: TextView
    private lateinit var priceListContainer: LinearLayout
    private lateinit var etBarcode: EditText

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            etBarcode.setText(result.contents)
            searchProduct(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scannerCard        = findViewById(R.id.scannerCard)
        loadingSection     = findViewById(R.id.loadingSection)
        resultsSection     = findViewById(R.id.resultsSection)
        tvLoadingStep      = findViewById(R.id.tvLoadingStep)
        tvProductName      = findViewById(R.id.tvProductName)
        tvBrand            = findViewById(R.id.tvBrand)
        tvBarcode          = findViewById(R.id.tvBarcode)
        tvBestStore        = findViewById(R.id.tvBestStore)
        tvBestPrice        = findViewById(R.id.tvBestPrice)
        tvSavings          = findViewById(R.id.tvSavings)
        priceListContainer = findViewById(R.id.priceListContainer)
        etBarcode          = findViewById(R.id.etBarcode)

        findViewById<Button>(R.id.btnScan).setOnClickListener { startScanner() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            val code = etBarcode.text.toString().trim()
            if (code.isNotEmpty()) searchProduct(code) else showToast("Ingresa un código")
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareResults() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyResults() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetApp() }
    }

    private fun startScanner() {
        val options = ScanOptions().apply {
            setPrompt("Apunta al código de barras")
            setBeepEnabled(true)
            setOrientationLocked(true)
        }
        barcodeLauncher.launch(options)
    }

    // ── Step 1: Identify product ──────────────────────────────────────────────
    private fun searchProduct(barcode: String) {
        showLoading(true)
        setLoadingStep("Identificando producto...")

        val request = Request.Builder()
            .url("https://world.openfoodfacts.org/api/v0/product/$barcode.json")
            .addHeader("User-Agent", "PriceScanUSA/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                tryUPCItemDB(barcode, "")
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var name = ""; var brand = ""
                try {
                    val json = JSONObject(body)
                    if (json.optInt("status") == 1) {
                        val p = json.optJSONObject("product")
                        name  = p?.optString("product_name_en")
                               ?: p?.optString("product_name") ?: ""
                        brand = p?.optString("brands") ?: ""
                    }
                } catch (_: Exception) {}

                if (name.isEmpty()) tryUPCItemDB(barcode, brand)
                else fetchRealPrices(barcode, name, brand)
            }
        })
    }

    private fun tryUPCItemDB(barcode: String, brand: String) {
        val request = Request.Builder()
            .url("https://api.upcitemdb.com/prod/trial/lookup?upc=$barcode")
            .addHeader("User-Agent", "PriceScanUSA/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fetchRealPrices(barcode, "Producto $barcode", brand)
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var name = ""; var newBrand = brand
                try {
                    val json  = JSONObject(body)
                    val items = json.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val item = items.getJSONObject(0)
                        name     = item.optString("title", "")
                        newBrand = item.optString("brand", brand)

                        // UPC Item DB also returns offers/prices sometimes
                        val offers = item.optJSONArray("offers")
                        if (offers != null && offers.length() > 0) {
                            val priceList = mutableListOf<Map<String, String>>()
                            for (i in 0 until offers.length()) {
                                val offer = offers.getJSONObject(i)
                                val merchant = offer.optString("merchant", "")
                                val price    = offer.optDouble("price", 0.0)
                                if (merchant.isNotEmpty() && price > 0) {
                                    val (emoji, note) = getStoreInfo(merchant)
                                    priceList.add(mapOf(
                                        "store" to merchant,
                                        "price" to "%.2f".format(price),
                                        "unit"  to "each",
                                        "emoji" to emoji,
                                        "note"  to note
                                    ))
                                }
                            }
                            if (priceList.isNotEmpty()) {
                                val sorted = priceList.sortedBy { it["price"]?.toDoubleOrNull() ?: 0.0 }
                                runOnUiThread {
                                    displayResults(barcode,
                                        name.ifEmpty { "Producto $barcode" },
                                        newBrand, sorted)
                                }
                                return
                            }
                        }
                    }
                } catch (_: Exception) {}

                if (name.isEmpty()) name = "Producto $barcode"
                fetchRealPrices(barcode, name, newBrand)
            }
        })
    }

    // ── Step 2: Fetch real prices from Open Food Facts Prices ─────────────────
    private fun fetchRealPrices(barcode: String, productName: String, brand: String) {
        runOnUiThread { setLoadingStep("Buscando precios reales en tiendas...") }

        // Open Food Facts Prices API - completely free, no key needed
        val url = "https://prices.openfoodfacts.org/api/v1/prices?product_code=$barcode&page_size=50&order_by=-date"

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "PriceScanUSA/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    displayResults(barcode, productName, brand,
                        getSmartEstimates(productName))
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                val storeMap = mutableMapOf<String, MutableList<Double>>()

                try {
                    val json  = JSONObject(body)
                    val items = json.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item     = items.getJSONObject(i)
                            val price    = item.optDouble("price", 0.0)
                            val location = item.optJSONObject("location")
                            val name     = location?.optString("osm_name", "") ?: ""
                            val country  = location?.optString("country", "") ?: ""

                            // Only US prices
                            if (price > 0 && (country == "US" || country == "en:us" || country.isEmpty())) {
                                val storeName = normalizeStoreName(name)
                                if (storeName.isNotEmpty()) {
                                    storeMap.getOrPut(storeName) { mutableListOf() }.add(price)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                // Build price list from real data
                val priceList = mutableListOf<Map<String, String>>()
                storeMap.forEach { (store, prices) ->
                    val avg = prices.average()
                    val (emoji, note) = getStoreInfo(store)
                    priceList.add(mapOf(
                        "store" to store,
                        "price" to "%.2f".format(avg),
                        "unit"  to "each",
                        "emoji" to emoji,
                        "note"  to note
                    ))
                }

                val finalPrices = if (priceList.isEmpty()) {
                    getSmartEstimates(productName)
                } else {
                    priceList.sortedBy { it["price"]?.toDoubleOrNull() ?: 0.0 }
                }

                runOnUiThread { displayResults(barcode, productName, brand, finalPrices) }
            }
        })
    }

    private fun normalizeStoreName(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("walmart")  -> "Walmart"
            lower.contains("target")   -> "Target"
            lower.contains("kroger")   -> "Kroger"
            lower.contains("dillon")   -> "Dillons"
            lower.contains("aldi")     -> "Aldi"
            lower.contains("amazon")   -> "Amazon"
            lower.contains("costco")   -> "Costco"
            lower.contains("walgreen") -> "Walgreens"
            lower.contains("cvs")      -> "CVS"
            lower.contains("whole")    -> "Whole Foods"
            lower.contains("trader")   -> "Trader Joe's"
            lower.contains("safeway")  -> "Safeway"
            lower.contains("heb")      -> "H-E-B"
            lower.contains("publix")   -> "Publix"
            else -> ""
        }
    }

    private fun getStoreInfo(store: String): Pair<String, String> {
        val lower = store.lowercase()
        return when {
            lower.contains("walmart")  -> Pair("🛒", "walmart.com")
            lower.contains("amazon")   -> Pair("📦", "amazon.com")
            lower.contains("target")   -> Pair("🎯", "target.com")
            lower.contains("kroger")   -> Pair("🏪", "kroger.com")
            lower.contains("dillon")   -> Pair("🛍️", "dillons.com")
            lower.contains("aldi")     -> Pair("🏬", "aldi.us")
            lower.contains("costco")   -> Pair("🏢", "costco.com")
            lower.contains("walgreen") -> Pair("💊", "walgreens.com")
            lower.contains("cvs")      -> Pair("💊", "cvs.com")
            lower.contains("whole")    -> Pair("🌿", "wholefoodsmarket.com")
            lower.contains("trader")   -> Pair("🌺", "traderjoes.com")
            lower.contains("safeway")  -> Pair("🏬", "safeway.com")
            lower.contains("heb")      -> Pair("🏪", "heb.com")
            lower.contains("publix")   -> Pair("🛒", "publix.com")
            else -> Pair("🏪", store.lowercase().replace(" ", "") + ".com")
        }
    }

    // Smart estimates when no real data available
    private fun getSmartEstimates(productName: String): List<Map<String, String>> {
        val base = when {
            productName.contains("milk", true)       -> 3.99
            productName.contains("bread", true)      -> 3.49
            productName.contains("juice", true)      -> 4.99
            productName.contains("water", true)      -> 1.99
            productName.contains("chips", true)      -> 3.99
            productName.contains("cereal", true)     -> 4.99
            productName.contains("cheese", true)     -> 5.99
            productName.contains("butter", true)     -> 4.99
            productName.contains("coffee", true)     -> 9.99
            productName.contains("soda", true)       -> 2.49
            productName.contains("chicken", true)    -> 7.99
            productName.contains("beef", true)       -> 9.99
            productName.contains("pasta", true)      -> 1.99
            productName.contains("rice", true)       -> 3.99
            productName.contains("soap", true)       -> 3.49
            productName.contains("shampoo", true)    -> 5.99
            productName.contains("toothpaste", true) -> 3.99
            else -> 3.99
        }
        return listOf(
            mapOf("store" to "Aldi",    "price" to "%.2f".format(base * 0.75), "unit" to "each", "emoji" to "🏬", "note" to "Precio estimado"),
            mapOf("store" to "Walmart", "price" to "%.2f".format(base * 0.88), "unit" to "each", "emoji" to "🛒", "note" to "Precio estimado"),
            mapOf("store" to "Dillons", "price" to "%.2f".format(base * 0.95), "unit" to "each", "emoji" to "🛍️", "note" to "Precio estimado"),
            mapOf("store" to "Kroger",  "price" to "%.2f".format(base * 0.97), "unit" to "each", "emoji" to "🏪", "note" to "Precio estimado"),
            mapOf("store" to "Target",  "price" to "%.2f".format(base * 1.05), "unit" to "each", "emoji" to "🎯", "note" to "Precio estimado"),
            mapOf("store" to "Amazon",  "price" to "%.2f".format(base * 1.10), "unit" to "each", "emoji" to "📦", "note" to "Precio estimado")
        ).sortedBy { it["price"]?.toDoubleOrNull() ?: 0.0 }
    }

    // ── Display results ───────────────────────────────────────────────────────
    private fun displayResults(
        barcode: String,
        productName: String,
        brand: String,
        prices: List<Map<String, String>>
    ) {
        currentProductName = productName
        currentPrices      = prices

        showLoading(false)
        tvProductName.text = productName
        tvBrand.text       = brand.ifEmpty { "Sin marca" }
        tvBarcode.text     = "▌▌ $barcode"

        val cheapest = prices.first()
        val priciest = prices.last()
        val savings  = (priciest["price"]?.toDoubleOrNull() ?: 0.0) -
                       (cheapest["price"]?.toDoubleOrNull() ?: 0.0)

        tvBestStore.text = "★ Mejor precio — ${cheapest["store"]}"
        tvBestPrice.text = "$${cheapest["price"]}"
        tvSavings.text   = if (savings > 0.01)
            "Ahorras $${"%.2f".format(savings)} vs ${priciest["store"]}"
        else ""

        priceListContainer.removeAllViews()
        prices.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply {
                orientation  = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(10) }
                setBackgroundResource(
                    if (i == 0) R.drawable.price_item_best_bg else R.drawable.price_item_bg
                )
            }

            row.addView(TextView(this).apply {
                text         = item["emoji"]
                textSize     = 24f
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                gravity      = android.view.Gravity.CENTER
            })

            val info = LinearLayout(this).apply {
                orientation  = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.marginStart = dp(12) }
            }
            info.addView(TextView(this).apply {
                text     = item["store"]
                textSize = 15f
                setTextColor(getColor(R.color.text))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text     = item["note"]
                textSize = 12f
                setTextColor(getColor(R.color.muted))
            })
            row.addView(info)

            val priceInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = android.view.Gravity.END
            }
            priceInfo.addView(TextView(this).apply {
                text     = "$${item["price"]}"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (i == 0) R.color.accent else R.color.text))
                gravity  = android.view.Gravity.END
            })
            priceInfo.addView(TextView(this).apply {
                text     = item["unit"]
                textSize = 11f
                setTextColor(getColor(R.color.muted))
                gravity  = android.view.Gravity.END
            })
            row.addView(priceInfo)
            priceListContainer.addView(row)
        }

        resultsSection.visibility = View.VISIBLE
    }

    // ── Share / Copy ──────────────────────────────────────────────────────────
    private fun shareResults() {
        var text = "💰 PriceScan USA\n$currentProductName\n\n"
        currentPrices.forEach { text += "${it["emoji"]} ${it["store"]}: $${it["price"]}\n" }
        text += "\nPriceScan USA 🇺🇸"
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Compartir"
        ))
    }

    private fun copyResults() {
        var text = "$currentProductName\n"
        currentPrices.forEach { text += "${it["store"]}: $${it["price"]}\n" }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PriceScan", text))
        showToast("✓ Copiado al portapapeles")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun resetApp() {
        resultsSection.visibility = View.GONE
        scannerCard.visibility    = View.VISIBLE
        etBarcode.text?.clear()
        priceListContainer.removeAllViews()
    }

    private fun showLoading(show: Boolean) {
        loadingSection.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            scannerCard.visibility    = View.GONE
            resultsSection.visibility = View.GONE
        }
    }

    private fun setLoadingStep(msg: String) { runOnUiThread { tvLoadingStep.text = msg } }
    private fun showToast(msg: String) { runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
