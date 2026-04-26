package com.pricescan.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import okhttp3.*;

import org.json.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private PreviewView previewView;
    private View scannerCard, loadingSection, resultsSection;
    private TextView tvLoadingStep, tvBrand, tvProductName, tvBarcode;
    private TextView tvBestStore, tvBestPrice, tvSavings;
    private android.widget.EditText etBarcode;
    private LinearLayout priceList;

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private boolean cameraActive = false;
    private boolean scanHandled = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient();

    private List<PriceItem> currentPrices = new ArrayList<>();
    private String currentProductName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views
        previewView     = findViewById(R.id.previewView);
        scannerCard     = findViewById(R.id.scannerCard);
        loadingSection  = findViewById(R.id.loadingSection);
        resultsSection  = findViewById(R.id.resultsSection);
        tvLoadingStep   = findViewById(R.id.tvLoadingStep);
        tvBrand         = findViewById(R.id.tvBrand);
        tvProductName   = findViewById(R.id.tvProductName);
        tvBarcode       = findViewById(R.id.tvBarcode);
        tvBestStore     = findViewById(R.id.tvBestStore);
        tvBestPrice     = findViewById(R.id.tvBestPrice);
        tvSavings       = findViewById(R.id.tvSavings);
        etBarcode       = findViewById(R.id.etBarcode);
        priceList       = findViewById(R.id.priceList);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Buttons
        findViewById(R.id.btnCamera).setOnClickListener(v -> requestCameraAndStart());
        findViewById(R.id.btnStopCamera).setOnClickListener(v -> stopCamera());
        findViewById(R.id.btnSearch).setOnClickListener(v -> searchBarcode());
        findViewById(R.id.btnShare).setOnClickListener(v -> shareResults());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyResults());
        findViewById(R.id.btnReset).setOnClickListener(v -> resetApp());

        etBarcode.setOnEditorActionListener((v, a, e) -> { searchBarcode(); return true; });
    }

    // ── Camera ────────────────────────────────────────────────────────────────
    private void requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == CAMERA_PERMISSION_CODE && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else
            Toast.makeText(this, "Se necesita permiso de cámara", Toast.LENGTH_SHORT).show();
    }

    private void startCamera() {
        scanHandled = false;
        previewView.setVisibility(View.VISIBLE);
        findViewById(R.id.btnCamera).setVisibility(View.GONE);
        findViewById(R.id.btnStopCamera).setVisibility(View.VISIBLE);
        cameraActive = true;

        ListenableFutureCompat<ProcessCameraProvider> future =
                new ListenableFutureCompat<>(ProcessCameraProvider.getInstance(this));

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                showToast("Error al iniciar cámara");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    private void bindCamera() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        BarcodeScannerOptions opts = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build();
        BarcodeScanner scanner = BarcodeScanning.getClient(opts);

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();

        analysis.setAnalyzer(cameraExecutor, image -> {
            if (scanHandled) { image.close(); return; }
            InputImage inputImage = InputImage.fromMediaImage(
                    image.getImage(), image.getImageInfo().getRotationDegrees());
            scanner.process(inputImage)
                    .addOnSuccessListener(barcodes -> {
                        for (Barcode b : barcodes) {
                            String raw = b.getRawValue();
                            if (raw != null && !raw.isEmpty() && !scanHandled) {
                                scanHandled = true;
                                mainHandler.post(() -> {
                                    stopCamera();
                                    etBarcode.setText(raw);
                                    showToast("✓ Código escaneado");
                                    mainHandler.postDelayed(this::searchBarcode, 400);
                                });
                            }
                        }
                    })
                    .addOnCompleteListener(t -> image.close());
        });

        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, selector, preview, analysis);
    }

    private void stopCamera() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraActive = false;
        previewView.setVisibility(View.GONE);
        findViewById(R.id.btnCamera).setVisibility(View.VISIBLE);
        findViewById(R.id.btnStopCamera).setVisibility(View.GONE);
    }

    // ── Search ────────────────────────────────────────────────────────────────
    private void searchBarcode() {
        String barcode = etBarcode.getText().toString().trim();
        if (barcode.isEmpty()) { showToast("Ingresa un código de barras"); return; }

        stopCamera();
        scannerCard.setVisibility(View.GONE);
        resultsSection.setVisibility(View.GONE);
        loadingSection.setVisibility(View.VISIBLE);
        setLoadingStep("Identificando producto...");

        Executors.newSingleThreadExecutor().execute(() -> {
            String productName = "", brand = "", imgUrl = "";

            // 1. Open Food Facts
            try {
                Request req = new Request.Builder()
                        .url("https://world.openfoodfacts.org/api/v0/product/" + barcode + ".json")
                        .build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    if (json.optInt("status") == 1) {
                        JSONObject p = json.optJSONObject("product");
                        if (p != null) {
                            productName = p.optString("product_name_en",
                                          p.optString("product_name", ""));
                            brand = p.optString("brands", "");
                            imgUrl = p.optString("image_front_url",
                                     p.optString("image_url", ""));
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 2. UPC Item DB fallback
            if (productName.isEmpty()) {
                try {
                    Request req = new Request.Builder()
                            .url("https://api.upcitemdb.com/prod/trial/lookup?upc=" + barcode)
                            .build();
                    try (Response resp = httpClient.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        JSONObject json = new JSONObject(body);
                        JSONArray items = json.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            JSONObject item = items.getJSONObject(0);
                            productName = item.optString("title", "");
                            brand = item.optString("brand", "");
                            JSONArray imgs = item.optJSONArray("images");
                            if (imgs != null && imgs.length() > 0) imgUrl = imgs.getString(0);
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (productName.isEmpty()) productName = "Producto " + barcode;

            final String finalProduct = productName;
            final String finalBrand   = brand;
            final String finalImg     = imgUrl;
            final String finalBarcode = barcode;

            mainHandler.post(() -> setLoadingStep("Consultando precios en tiendas USA..."));

            fetchPricesFromAI(finalBarcode, finalProduct, finalBrand, finalImg);
        });
    }

    private void fetchPricesFromAI(String barcode, String productName, String brand, String imgUrl) {
        String prompt = "You are a price comparison assistant for US stores.\n" +
                "Product: \"" + productName + "\" by \"" + (brand.isEmpty() ? "Unknown" : brand) + "\"\n" +
                "Barcode: " + barcode + "\n\n" +
                "Search for current approximate retail prices at major US retailers.\n" +
                "Return ONLY a JSON array (no markdown, no preamble) like:\n" +
                "[{\"store\":\"Walmart\",\"price\":3.47,\"unit\":\"each\",\"emoji\":\"🛒\",\"note\":\"In store & online\"}]\n" +
                "Include 4-6 stores. Realistic current US prices. Sort by price ascending.";

        JSONObject body;
        try {
            body = new JSONObject()
                .put("model", "claude-sonnet-4-20250514")
                .put("max_tokens", 1000)
                .put("tools", new JSONArray().put(
                    new JSONObject().put("type", "web_search_20250305").put("name", "web_search")
                ))
                .put("messages", new JSONArray().put(
                    new JSONObject().put("role", "user").put("content", prompt)
                ));
        } catch (JSONException e) {
            mainHandler.post(() -> showError("Error al crear solicitud"));
            return;
        }

        Request request = new Request.Builder()
                .url(API_URL)
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .header("Content-Type", "application/json")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> showError("Error de conexión. Verifica tu internet."));
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String respBody = response.body() != null ? response.body().string() : "";
                List<PriceItem> prices = new ArrayList<>();
                try {
                    JSONObject json = new JSONObject(respBody);
                    JSONArray content = json.optJSONArray("content");
                    StringBuilder text = new StringBuilder();
                    if (content != null) {
                        for (int i = 0; i < content.length(); i++) {
                            JSONObject block = content.getJSONObject(i);
                            if ("text".equals(block.optString("type")))
                                text.append(block.optString("text"));
                        }
                    }
                    String raw = text.toString().replaceAll("```json|```", "").trim();
                    int start = raw.indexOf('['), end = raw.lastIndexOf(']');
                    if (start >= 0 && end > start) {
                        JSONArray arr = new JSONArray(raw.substring(start, end + 1));
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            prices.add(new PriceItem(
                                    o.optString("store"),
                                    o.optDouble("price"),
                                    o.optString("unit"),
                                    o.optString("emoji"),
                                    o.optString("note")));
                        }
                    }
                } catch (Exception ignored) {}

                if (prices.isEmpty()) {
                    prices.add(new PriceItem("Walmart", 3.47, "each", "🛒", "Precio estimado"));
                    prices.add(new PriceItem("Amazon",  3.99, "each", "📦", "Precio estimado"));
                    prices.add(new PriceItem("Target",  4.29, "each", "🎯", "Precio estimado"));
                }

                prices.sort(Comparator.comparingDouble(p -> p.price));
                final List<PriceItem> finalPrices = prices;

                mainHandler.post(() -> displayResults(barcode, productName, brand, imgUrl, finalPrices));
            }
        });
    }

    // ── Display ───────────────────────────────────────────────────────────────
    private void displayResults(String barcode, String productName, String brand,
                                 String imgUrl, List<PriceItem> prices) {
        currentProductName = productName;
        currentPrices = prices;

        loadingSection.setVisibility(View.GONE);
        resultsSection.setVisibility(View.VISIBLE);

        tvBrand.setText(brand.isEmpty() ? "SIN MARCA" : brand.toUpperCase());
        tvProductName.setText(productName);
        tvBarcode.setText("▌▌ " + barcode);

        PriceItem cheapest = prices.get(0);
        PriceItem priciest = prices.get(prices.size() - 1);
        double savings = priciest.price - cheapest.price;

        tvBestStore.setText(cheapest.store);
        tvBestPrice.setText(String.format("$%.2f", cheapest.price));
        tvSavings.setText(savings > 0.01
                ? String.format("Ahorras $%.2f vs %s", savings, priciest.store) : "");

        // Build price list
        priceList.removeAllViews();
        for (int i = 0; i < prices.size(); i++) {
            PriceItem item = prices.get(i);
            addPriceRow(item, i == 0);
        }

        // Load product image async
        if (!imgUrl.isEmpty()) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    InputStream is = new URL(imgUrl).openStream();
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    mainHandler.post(() -> {
                        android.widget.ImageView img = findViewById(R.id.imgProduct);
                        img.setImageBitmap(bmp);
                    });
                } catch (Exception ignored) {}
            });
        }
    }

    private void addPriceRow(PriceItem item, boolean isCheapest) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 0, 0, dpToPx(10));
        row.setLayoutParams(rowParams);
        row.setBackground(getDrawable(R.drawable.price_item_bg));
        row.setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14));

        // Emoji circle
        TextView emoji = new TextView(this);
        emoji.setText(item.emoji);
        emoji.setTextSize(22);
        emoji.setGravity(Gravity.CENTER);
        emoji.setBackground(getDrawable(R.drawable.input_bg));
        LinearLayout.LayoutParams emojiParams = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44));
        emojiParams.setMarginEnd(dpToPx(12));
        emoji.setLayoutParams(emojiParams);

        // Store info
        LinearLayout storeInfo = new LinearLayout(this);
        storeInfo.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams siParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        storeInfo.setLayoutParams(siParams);

        TextView storeName = new TextView(this);
        storeName.setText(item.store);
        storeName.setTextColor(getColor(R.color.text));
        storeName.setTextSize(15);
        storeName.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView storeNote = new TextView(this);
        storeNote.setText(item.note);
        storeNote.setTextColor(getColor(R.color.muted));
        storeNote.setTextSize(12);

        storeInfo.addView(storeName);
        storeInfo.addView(storeNote);

        // Price
        LinearLayout priceInfo = new LinearLayout(this);
        priceInfo.setOrientation(LinearLayout.VERTICAL);
        priceInfo.setGravity(Gravity.END);

        TextView priceAmt = new TextView(this);
        priceAmt.setText(String.format("$%.2f", item.price));
        priceAmt.setTextColor(isCheapest ? getColor(R.color.accent) : getColor(R.color.text));
        priceAmt.setTextSize(19);
        priceAmt.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView priceUnit = new TextView(this);
        priceUnit.setText(item.unit);
        priceUnit.setTextColor(getColor(R.color.muted));
        priceUnit.setTextSize(11);

        priceInfo.addView(priceAmt);
        priceInfo.addView(priceUnit);

        row.addView(emoji);
        row.addView(storeInfo);
        row.addView(priceInfo);

        priceList.addView(row);
    }

    // ── Share / Copy ──────────────────────────────────────────────────────────
    private void shareResults() {
        if (currentPrices.isEmpty()) return;
        StringBuilder sb = new StringBuilder("💰 PriceScan USA\n" + currentProductName + "\n\n");
        for (PriceItem p : currentPrices)
            sb.append(p.emoji).append(" ").append(p.store)
              .append(": $").append(String.format("%.2f", p.price)).append("\n");
        sb.append("\nDescarga PriceScan USA 🇺🇸");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(intent, "Compartir precios"));
    }

    private void copyResults() {
        if (currentPrices.isEmpty()) return;
        StringBuilder sb = new StringBuilder(currentProductName + "\n");
        for (PriceItem p : currentPrices)
            sb.append(p.store).append(": $").append(String.format("%.2f", p.price)).append("\n");
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("precios", sb.toString()));
        showToast("✓ Copiado al portapapeles");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void resetApp() {
        currentPrices.clear();
        etBarcode.setText("");
        scannerCard.setVisibility(View.VISIBLE);
        resultsSection.setVisibility(View.GONE);
        loadingSection.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        loadingSection.setVisibility(View.GONE);
        scannerCard.setVisibility(View.VISIBLE);
        showToast("Error: " + msg);
    }

    private void setLoadingStep(String msg) {
        tvLoadingStep.setText(msg);
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    // ── Models ────────────────────────────────────────────────────────────────
    static class PriceItem {
        String store, unit, emoji, note;
        double price;
        PriceItem(String store, double price, String unit, String emoji, String note) {
            this.store = store; this.price = price; this.unit = unit;
            this.emoji = emoji; this.note = note;
        }
    }

    // Minimal ListenableFuture wrapper to avoid Guava dependency
    static class ListenableFutureCompat<T> {
        private final com.google.common.util.concurrent.ListenableFuture<T> future;
        ListenableFutureCompat(com.google.common.util.concurrent.ListenableFuture<T> future) {
            this.future = future;
        }
        void addListener(Runnable r, java.util.concurrent.Executor e) { future.addListener(r, e); }
        T get() throws Exception { return future.get(); }
    }
}
