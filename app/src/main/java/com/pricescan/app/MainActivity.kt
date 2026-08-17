package com.pricescan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.pricescan.app.databinding.ActivityMainBinding
import com.pricescan.app.model.ProductInfo
import com.pricescan.app.model.StorePrice
import com.pricescan.app.network.AiPriceService
import com.pricescan.app.ui.PriceAdapter
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val aiPriceService by lazy { AiPriceService(client) }
    private val priceAdapter by lazy { PriceAdapter() }

    private var currentProduct: ProductInfo? = null

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            binding.etBarcode.setText(result.contents)
            processSearch(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
    }

    private fun setupRecyclerView() {
        binding.rvPrices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = priceAdapter
        }
    }

    private fun setupListeners() {
        binding.btnScan.setOnClickListener { startScanner() }
        binding.btnSearch.setOnClickListener {
            val query = binding.etBarcode.text.toString().trim()
            if (query.isNotEmpty()) {
                processSearch(query)
            } else {
                showToast("Ingresa un código de barras o nombre de producto")
            }
        }
        binding.btnShare.setOnClickListener { shareResults() }
        binding.btnCopy.setOnClickListener { copyResults() }
        binding.btnReset.setOnClickListener { resetApp() }
    }

    private fun startScanner() {
        val options = ScanOptions().apply {
            setPrompt("Apunta la cámara al código de barras")
            setBeepEnabled(true)
            setOrientationLocked(true)
        }
        barcodeLauncher.launch(options)
    }

    // ── Search Flow ────────────────────────────────────────────────────────────
    private fun processSearch(query: String) {
        showLoading(true)

        val isNumericBarcode = query.all { it.isDigit() } && query.length >= 6
        if (isNumericBarcode) {
            setLoadingStep("Buscando información del producto...")
            fetchProductFromOpenFoodFacts(query)
        } else {
            // Text search (Product Name) -> AI Price Service
            setLoadingStep("Consultando inteligencia de precios de IA...")
            fetchPricesFromAi(
                barcode = "N/A",
                productName = query,
                brand = "",
                imageUrl = ""
            )
        }
    }

    // Step 1: Open Food Facts Lookup
    private fun fetchProductFromOpenFoodFacts(barcode: String) {
        val request = Request.Builder()
            .url("https://world.openfoodfacts.org/api/v0/product/$barcode.json")
            .addHeader("User-Agent", "PriceScanUSA/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                tryUPCItemDB(barcode, "", "")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    var name = ""
                    var brand = ""
                    var imageUrl = ""

                    try {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        if (json.optInt("status") == 1) {
                            val p = json.optJSONObject("product")
                            name = p?.optString("product_name_en")
                                ?: p?.optString("product_name") ?: ""
                            brand = p?.optString("brands") ?: ""
                            imageUrl = p?.optString("image_front_url")
                                ?: p?.optString("image_url") ?: ""
                        }
                    } catch (_: Exception) {}

                    if (name.isEmpty()) {
                        tryUPCItemDB(barcode, brand, imageUrl)
                    } else {
                        fetchRealPrices(barcode, name, brand, imageUrl)
                    }
                }
            }
        })
    }

    // Step 1.5: Fallback to UPCItemDB
    private fun tryUPCItemDB(barcode: String, fallbackBrand: String, fallbackImage: String) {
        val request = Request.Builder()
            .url("https://api.upcitemdb.com/prod/trial/lookup?upc=$barcode")
            .addHeader("User-Agent", "PriceScanUSA/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fetchRealPrices(barcode, "Producto $barcode", fallbackBrand, fallbackImage)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    var name = ""
                    var brand = fallbackBrand
                    var imageUrl = fallbackImage

                    try {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val items = json.optJSONArray("items")
                        if (items != null && items.length() > 0) {
                            val item = items.getJSONObject(0)
                            name = item.optString("title", "")
                            brand = item.optString("brand", fallbackBrand)

                            val images = item.optJSONArray("images")
                            if (images != null && images.length() > 0) {
                                imageUrl = images.getString(0)
                            }
                        }
                    } catch (_: Exception) {}

                    val finalName = if (name.isEmpty()) "Producto $barcode" else name
                    fetchRealPrices(barcode, finalName, brand, imageUrl)
                }
            }
        })
    }

    // Step 2: Fetch real store prices from Open Food Facts Prices API
    private fun fetchRealPrices(
        barcode: String,
        productName: String,
        brand: String,
        imageUrl: String
    ) {
        runOnUiThread { setLoadingStep("Buscando precios en tiendas de EE.UU...") }

        val url = "https://prices.openfoodfacts.org/api/v1/prices?product_code=$barcode&page_size=50&order_by=-date"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "PriceScanUSA/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fetchPricesFromAi(barcode, productName, brand, imageUrl)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val storeMap = mutableMapOf<String, MutableList<Double>>()

                    try {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val items = json.optJSONArray("items")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val item = items.getJSONObject(i)
                                val price = item.optDouble("price", 0.0)
                                val location = item.optJSONObject("location")
                                val name = location?.optString("osm_name", "") ?: ""
                                val country = location?.optString("country", "") ?: ""

                                if (price > 0 && (country == "US" || country == "en:us" || country.isEmpty())) {
                                    val storeName = normalizeStoreName(name)
                                    if (storeName.isNotEmpty()) {
                                        storeMap.getOrPut(storeName) { mutableListOf() }.add(price)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    val priceList = mutableListOf<StorePrice>()
                    storeMap.forEach { (store, prices) ->
                        val avg = prices.average()
                        val (emoji, note) = getStoreInfo(store)
                        priceList.add(
                            StorePrice(
                                store = store,
                                price = avg,
                                unit = "cada uno",
                                emoji = emoji,
                                note = note,
                                isAiEstimate = false
                            )
                        )
                    }

                    if (priceList.isNotEmpty()) {
                        val sorted = priceList.sortedBy { it.price }
                        val productInfo = ProductInfo(
                            barcode = barcode,
                            name = productName,
                            brand = brand,
                            imageUrl = imageUrl,
                            prices = sorted,
                            isAiPowered = false,
                            aiInsight = "Precios reales verificados de usuarios en tiendas de EE.UU."
                        )
                        runOnUiThread { displayResults(productInfo) }
                    } else {
                        // Fallback to AI Price Service
                        fetchPricesFromAi(barcode, productName, brand, imageUrl)
                    }
                }
            }
        })
    }

    // Step 3: AI Price Estimation Service
    private fun fetchPricesFromAi(
        barcode: String,
        productName: String,
        brand: String,
        imageUrl: String
    ) {
        runOnUiThread { setLoadingStep("Consultando IA de mercado para estimación de precios...") }

        aiPriceService.fetchAiPrices(
            productName = productName,
            brand = brand,
            apiKey = null, // Uses smart AI market engine with optional Gemini API key
            callback = object : AiPriceService.AiPriceCallback {
                override fun onSuccess(prices: List<StorePrice>, insight: String) {
                    val productInfo = ProductInfo(
                        barcode = barcode,
                        name = productName,
                        brand = brand,
                        imageUrl = imageUrl,
                        prices = prices,
                        isAiPowered = true,
                        aiInsight = insight
                    )
                    runOnUiThread { displayResults(productInfo) }
                }

                override fun onError(errorMsg: String) {
                    runOnUiThread {
                        showToast("Error al obtener precios: $errorMsg")
                        showLoading(false)
                    }
                }
            }
        )
    }

    // ── UI Render Results ──────────────────────────────────────────────────────
    private fun displayResults(info: ProductInfo) {
        currentProduct = info
        showLoading(false)

        binding.tvProductName.text = info.name
        binding.tvBrand.text = info.brand.ifEmpty { "Marca Genérica" }
        binding.tvBarcode.text = "▌▌ ${info.barcode}"

        // Load Product Image using Glide
        if (info.imageUrl.isNotEmpty()) {
            Glide.with(this)
                .load(info.imageUrl)
                .transform(CenterCrop(), RoundedCorners(dp(12)))
                .placeholder(R.drawable.product_img_bg)
                .error(R.drawable.product_img_bg)
                .into(binding.ivProduct)
        } else {
            binding.ivProduct.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // Data Source Badge
        if (info.isAiPowered) {
            binding.tvDataSourceBadge.text = "🤖 IA de Mercado"
            binding.tvDataSourceBadge.setTextColor(getColor(R.color.accent))
        } else {
            binding.tvDataSourceBadge.text = "🟢 Datos Reales"
            binding.tvDataSourceBadge.setTextColor(getColor(R.color.accent2))
        }

        // Best Deal Header Card
        val cheapest = info.cheapestPrice
        val priciest = info.priciestPrice
        if (cheapest != null) {
            binding.tvBestStore.text = "★ Mejor oferta — ${cheapest.store}"
            binding.tvBestPrice.text = cheapest.formattedPrice
            val savings = info.maxSavings
            binding.tvSavings.text = if (savings > 0.01 && priciest != null) {
                "Ahorras $${"%.2f".format(savings)} comparado con ${priciest.store}"
            } else {
                "El precio más accesible disponible"
            }
        }

        // AI Insight Banner
        if (info.aiInsight.isNotEmpty()) {
            binding.aiInsightCard.visibility = View.VISIBLE
            binding.tvAiInsightText.text = info.aiInsight
        } else {
            binding.aiInsightCard.visibility = View.GONE
        }

        // Update RecyclerView
        priceAdapter.updatePrices(info.prices)
        binding.resultsSection.visibility = View.VISIBLE
    }

    // ── Helpers & Utils ───────────────────────────────────────────────────────
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

    private fun shareResults() {
        val info = currentProduct ?: return
        var text = "💰 PriceScan USA\n📦 ${info.name}\n\n"
        info.prices.forEach { text += "${it.emoji} ${it.store}: ${it.formattedPrice}\n" }
        text += "\nEscaneado con PriceScan USA 🇺🇸"
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Compartir comparación"
            )
        )
    }

    private fun copyResults() {
        val info = currentProduct ?: return
        var text = "${info.name}\n"
        info.prices.forEach { text += "${it.store}: ${it.formattedPrice}\n" }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PriceScan", text))
        showToast("✓ Copiado al portapapeles")
    }

    private fun resetApp() {
        binding.resultsSection.visibility = View.GONE
        binding.scannerCard.visibility = View.VISIBLE
        binding.etBarcode.text?.clear()
        currentProduct = null
    }

    private fun showLoading(show: Boolean) {
        binding.loadingSection.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.scannerCard.visibility = View.GONE
            binding.resultsSection.visibility = View.GONE
        }
    }

    private fun setLoadingStep(msg: String) {
        runOnUiThread { binding.tvLoadingStep.text = msg }
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
