package com.pricescan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class PriceItem(
    val store: String,
    val price: Double,
    val unit: String,
    val emoji: String,
    val note: String
)

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private var currentProductName = ""
    private var currentPrices = listOf<PriceItem>()

    // Views
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
    private lateinit var ivProduct: ImageView
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

        scannerCard = findViewById(R.id.scannerCard)
        loadingSection = findViewById(R.id.loadingSection)
        resultsSection = findViewById(R.id.resultsSection)
        tvLoadingStep = findViewById(R.id.tvLoadingStep)
        tvProductName = findViewById(R.id.tvProductName)
        tvBrand = findViewById(R.id.tvBrand)
        tvBarcode = findViewById(R.id.tvBarcode)
        tvBestStore = findViewById(R.id.tvBestStore)
        tvBestPrice = findViewById(R.id.tvBestPrice)
        tvSavings = findViewById(R.id.tvSavings)
        ivProduct = findViewById(R.id.ivProduct)
        priceListContainer = findViewById(R.id.priceListContainer)
        etBarcode = findViewById(R.id.etBarcode)

        findViewById<Button>(R.id.btnScan).setOnClickListener { startScanner() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            val code = etBarcode.text.toString().trim()
            if (code.isNotEmpty()) searchProduct(code)
            else showToast("Ingresa un código de barras")
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
            setCameraId(0)
        }
        barcodeLauncher.launch(options)
    }

    private fun searchProduct(barcode: String) {
        showLoading(true)
        setLoadingStep("Identificando producto...")

        // Step 1: Open Food Facts
        val url = "https://world.openfoodfacts.org/api/v0/product/$barcode.json"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                tryUPCItemDB(barcode, "", "", "")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var name = ""
                var brand = ""
                var imgUrl = ""
                try {
                    val json = JSONObject(body)
                    if (json.optInt("status") == 1) {
                        val p = json.optJSONObject("product")
                        name = p?.optString("product_name_en") ?: p?.optString("product_name") ?: ""
                        brand = p?.optString("brands") ?: ""
                        imgUrl = p?.optString("image_front_url") ?: p?.optString("image_url") ?: ""
                    }
                } catch (e: Exception) {}

                if (name.isEmpty()) {
                    tryUPCItemDB(barcode, brand, imgUrl, "")
                } else {
                    fetchPricesWithAI(barcode, name, brand, imgUrl)
                }
            }
        })
    }

    private fun tryUPCItemDB(barcode: String, brand: String, imgUrl: String, fallback: String) {
        val url = "https://api.upcitemdb.com/prod/trial/lookup?upc=$barcode"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fetchPricesWithAI(barcode, "Producto $barcode", brand, imgUrl)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var name = ""
                var newBrand = brand
                var newImg = imgUrl
                try {
                    val json = JSONObject(body)
                    val items = json.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val item = items.getJSONObject(0)
                        name = item.optString("title", "")
                        newBrand = item.optString("brand", brand)
                        val images = item.optJSONArray("images")
                        if (images != null && images.length() > 0) newImg = images.getString(0)
                    }
                } catch (e: Exception) {}

                if (name.isEmpty()) name = "Producto $barcode"
                fetchPricesWithAI(barcode, name, newBrand, newImg)
            }
        })
    }

    private fun fetchPricesWithAI(barcode: String, productName: String, brand: String, imgUrl: String) {
        runOnUiThread { setLoadingStep("Consultando precios en tiendas USA...") }

       val prompt = """Search the web right now for the current retail price of this exact product in the United States:
Product: "$productName"
Brand: "$brand"
Barcode: $barcode

Search each of these stores and find the real current price:
Walmart, Amazon, Target, Kroger, Aldi, Dillons, Walgreens, CVS

Return ONLY a JSON array, no markdown, no explanation:
[{"store":"Walmart","price":3.47,"unit":"each","emoji":"🛒","note":"walmart.com"},{"store":"Aldi","price":2.99,"unit":"each","emoji":"🏬","note":"aldi.us"},{"store":"Dillons","price":3.29,"unit":"each","emoji":"🏪","note":"dillons.com"}]
Use REAL current prices from web search. Sort by price ascending."""

        val bodyJson = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 1000)
            put("tools", JSONArray().put(JSONObject().apply {
                put("type", "web_search_20250305")
                put("name", "web_search")
            }))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }

        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val fallbackPrices = listOf(
                    PriceItem("Walmart", 3.47, "each", "🛒", "Precio estimado"),
                    PriceItem("Amazon", 3.99, "each", "📦", "Precio estimado"),
                    PriceItem("Target", 4.29, "each", "🎯", "Precio estimado")
                )
                runOnUiThread { displayResults(barcode, productName, brand, imgUrl, fallbackPrices) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var prices = listOf<PriceItem>()
                try {
                    val json = JSONObject(body)
                    val content = json.optJSONArray("content") ?: JSONArray()
                    var text = ""
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "text") text += block.optString("text")
                    }
                    val clean = text.replace("```json", "").replace("```", "").trim()
                    val startIdx = clean.indexOf('[')
                    val endIdx = clean.lastIndexOf(']')
                    if (startIdx >= 0 && endIdx > startIdx) {
                        val jsonArr = clean.substring(startIdx, endIdx + 1)
                        val type = object : TypeToken<List<PriceItem>>() {}.type
                        prices = Gson().fromJson(jsonArr, type)
                    }
                } catch (e: Exception) {}

                if (prices.isEmpty()) {
                    prices = listOf(
                        PriceItem("Walmart", 3.47, "each", "🛒", "Precio estimado"),
                        PriceItem("Amazon", 3.99, "each", "📦", "Precio estimado"),
                        PriceItem("Target", 4.29, "each", "🎯", "Precio estimado")
                    )
                }

                val sortedPrices = prices.sortedBy { it.price }
                runOnUiThread { displayResults(barcode, productName, brand, imgUrl, sortedPrices) }
            }
        })
    }

    private fun displayResults(barcode: String, productName: String, brand: String, imgUrl: String, prices: List<PriceItem>) {
        currentProductName = productName
        currentPrices = prices

        showLoading(false)

        tvProductName.text = productName
        tvBrand.text = brand.ifEmpty { "Sin marca" }
        tvBarcode.text = "▌▌ $barcode"

        if (imgUrl.isNotEmpty()) {
            Glide.with(this).load(imgUrl).into(ivProduct)
        }

        val cheapest = prices.first()
        val priciest = prices.last()
        val savings = priciest.price - cheapest.price

        tvBestStore.text = cheapest.store
        tvBestPrice.text = "$${String.format("%.2f", cheapest.price)}"
        if (savings > 0.01) {
            tvSavings.text = "Ahorras $${String.format("%.2f", savings)} vs ${priciest.store}"
        }

        priceListContainer.removeAllViews()
        prices.forEachIndexed { index, item ->
            val itemView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, null)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = getDrawable(if (index == 0) R.drawable.price_item_best_bg else R.drawable.price_item_bg)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = dpToPx(10)
                layoutParams = params
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val storeIcon = TextView(this).apply {
                text = item.emoji
                textSize = 24f
                val bg = LinearLayout(context).apply {
                    background = getDrawable(R.drawable.store_icon_bg)
                }
                val iconParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
                layoutParams = iconParams
                gravity = android.view.Gravity.CENTER
            }
            row.addView(storeIcon)

            val storeInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                params.marginStart = dpToPx(12)
                layoutParams = params
            }

            val storeName = TextView(this).apply {
                text = item.store
                textSize = 15f
                setTextColor(getColor(R.color.text))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            storeInfo.addView(storeName)

            val storeNote = TextView(this).apply {
                text = item.note
                textSize = 12f
                setTextColor(getColor(R.color.muted))
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                p.topMargin = dpToPx(2)
                layoutParams = p
            }
            storeInfo.addView(storeNote)
            row.addView(storeInfo)

            val priceInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }

            val priceAmount = TextView(this).apply {
                text = "$${String.format("%.2f", item.price)}"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (index == 0) getColor(R.color.accent) else getColor(R.color.text))
                gravity = android.view.Gravity.END
            }
            priceInfo.addView(priceAmount)

            val priceUnit = TextView(this).apply {
                text = item.unit
                textSize = 11f
                setTextColor(getColor(R.color.muted))
                gravity = android.view.Gravity.END
            }
            priceInfo.addView(priceUnit)
            row.addView(priceInfo)

            priceListContainer.addView(row)
        }

        resultsSection.visibility = View.VISIBLE
        scannerCard.visibility = View.GONE
    }

    private fun shareResults() {
        var text = "💰 PriceScan USA\n$currentProductName\n\n"
        currentPrices.forEach { text += "${it.emoji} ${it.store}: $${String.format("%.2f", it.price)}\n" }
        text += "\nDescarga PriceScan USA 🇺🇸"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Compartir precios"))
    }

    private fun copyResults() {
        var text = "$currentProductName\n"
        currentPrices.forEach { text += "${it.store}: $${String.format("%.2f", it.price)}\n" }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PriceScan", text))
        showToast("✓ Copiado al portapapeles")
    }

    private fun resetApp() {
        resultsSection.visibility = View.GONE
        scannerCard.visibility = View.VISIBLE
        etBarcode.text?.clear()
        priceListContainer.removeAllViews()
    }

    private fun showLoading(show: Boolean) {
        loadingSection.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            scannerCard.visibility = View.GONE
            resultsSection.visibility = View.GONE
        }
    }

    private fun setLoadingStep(msg: String) {
        runOnUiThread { tvLoadingStep.text = msg }
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
