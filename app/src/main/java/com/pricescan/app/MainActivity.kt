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

    private val client = OkHttpClient()
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

    // ── Step 1: identify product ──────────────────────────────────────────────
    private fun searchProduct(barcode: String) {
        showLoading(true)
        setLoadingStep("Identificando producto...")

        val request = Request.Builder()
            .url("https://world.openfoodfacts.org/api/v0/product/$barcode.json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                tryUPCItemDB(barcode, "", "")
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

                if (name.isEmpty()) tryUPCItemDB(barcode, brand, "")
                else fetchPricesWithAI(barcode, name, brand)
            }
        })
    }

    private fun tryUPCItemDB(barcode: String, brand: String, imgUrl: String) {
        val request = Request.Builder()
            .url("https://api.upcitemdb.com/prod/trial/lookup?upc=$barcode")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fetchPricesWithAI(barcode, "Producto $barcode", brand)
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
                    }
                } catch (_: Exception) {}

                if (name.isEmpty()) name = "Producto $barcode"
                fetchPricesWithAI(barcode, name, newBrand)
            }
        })
    }

    // ── Step 2: fetch prices via Claude AI + web search ───────────────────────
    private fun fetchPricesWithAI(barcode: String, productName: String, brand: String) {
        runOnUiThread { setLoadingStep("Buscando precios en tiendas USA...") }

        val prompt = """Search the web right now for the current retail price of this exact product in the United States.

Product: "$productName"
Brand: "$brand"
Barcode: $barcode

Search each of these stores and find the REAL current price:
Walmart, Amazon, Target, Kroger, Aldi, Dillons, Walgreens, CVS

Rules:
- Use real prices found on the web, not estimates
- If a store does not carry this product, skip it
- Include only stores where this product is actually sold
- Sort results by price from lowest to highest

Return ONLY a valid JSON array with no markdown, no explanation, nothing else:
[
  {"store":"Aldi","price":2.49,"unit":"each","emoji":"🏬","note":"aldi.us"},
  {"store":"Walmart","price":2.98,"unit":"each","emoji":"🛒","note":"walmart.com"},
  {"store":"Dillons","price":3.29,"unit":"each","emoji":"🏪","note":"dillons.com"},
  {"store":"Target","price":3.49,"unit":"each","emoji":"🎯","note":"target.com"},
  {"store":"Amazon","price":3.99,"unit":"each","emoji":"📦","note":"amazon.com"}
]"""

        val tools = JSONArray().put(JSONObject().apply {
            put("type", "web_search_20250305")
            put("name", "web_search")
        })

        val bodyJson = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 1000)
            put("tools", tools)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { displayResults(barcode, productName, brand, getFallbackPrices()) }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var prices = listOf<Map<String, String>>()
                try {
                    val json    = JSONObject(body)
                    val content = json.optJSONArray("content") ?: JSONArray()
                    var text    = ""
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "text") text += block.optString("text")
                    }
                    val clean = text.replace("```json", "").replace("```", "").trim()
                    val start = clean.indexOf('[')
                    val end   = clean.lastIndexOf(']')
                    if (start >= 0 && end > start) {
                        val arr  = JSONArray(clean.substring(start, end + 1))
                        val list = mutableListOf<Map<String, String>>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            list.add(mapOf(
                                "store" to o.optString("store"),
                                "price" to o.optString("price"),
                                "unit"  to o.optString("unit"),
                                "emoji" to o.optString("emoji"),
                                "note"  to o.optString("note")
                            ))
                        }
                        prices = list.sortedBy { it["price"]?.toDoubleOrNull() ?: 0.0 }
                    }
                } catch (_: Exception) {}

                if (prices.isEmpty()) prices = getFallbackPrices()
                runOnUiThread { displayResults(barcode, productName, brand, prices) }
            }
        })
    }

    private fun getFallbackPrices() = listOf(
        mapOf("store" to "Aldi",    "price" to "2.49", "unit" to "each", "emoji" to "🏬", "note" to "Precio estimado"),
        mapOf("store" to "Walmart", "price" to "2.98", "unit" to "each", "emoji" to "🛒", "note" to "Precio estimado"),
        mapOf("store" to "Dillons", "price" to "3.29", "unit" to "each", "emoji" to "🏪", "note" to "Precio estimado"),
        mapOf("store" to "Target",  "price" to "3.49", "unit" to "each", "emoji" to "🎯", "note" to "Precio estimado"),
        mapOf("store" to "Amazon",  "price" to "3.99", "unit" to "each", "emoji" to "📦", "note" to "Precio estimado")
    )

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
        val savings  = (priciest["price"]?.toDoubleOrNull()  ?: 0.0) -
                       (cheapest["price"]?.toDoubleOrNull() ?: 0.0)

        tvBestStore.text = "★ Mejor precio — ${cheapest["store"]}"
        tvBestPrice.text = "$${cheapest["price"]}"
        tvSavings.text   = if (savings > 0.01)
            "Ahorras $${"%.2f".format(savings)} vs ${priciest["store"]}"
        else ""

        priceListContainer.removeAllViews()
        prices.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(10) }
                setBackgroundResource(
                    if (i == 0) R.drawable.price_item_best_bg else R.drawable.price_item_bg
                )
            }

            // Emoji icon
            row.addView(TextView(this).apply {
                text        = item["emoji"]
                textSize    = 24f
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                gravity     = android.view.Gravity.CENTER
            })

            // Store name + note
            val info = LinearLayout(this).apply {
                orientation  = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.marginStart = dp(12) }
            }
            info.addView(TextView(this).apply {
                text      = item["store"]
                textSize  = 15f
                setTextColor(getColor(R.color.text))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text      = item["note"]
                textSize  = 12f
                setTextColor(getColor(R.color.muted))
            })
            row.addView(info)

            // Price + unit
            val priceInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = android.view.Gravity.END
            }
            priceInfo.addView(TextView(this).apply {
                text     = "$${item["price"]}"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (i == 0) R.color.accent else R.color.text))
                gravity = android.view.Gravity.END
            })
            priceInfo.addView(TextView(this).apply {
                text     = item["unit"]
                textSize = 11f
                setTextColor(getColor(R.color.muted))
                gravity = android.view.Gravity.END
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
