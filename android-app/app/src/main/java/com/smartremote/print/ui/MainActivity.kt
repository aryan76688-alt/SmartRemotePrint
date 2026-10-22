package com.smartremote.print.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import com.smartremote.print.util.PdfFilePrintAdapter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.smartremote.print.R
import com.smartremote.print.network.PrintApiClient
import com.smartremote.print.security.SecurityVault
import com.smartremote.print.service.MargCsvSyncManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        private const val DEFAULT_HOME_URL = "https://account.margcloud.com/login"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val PERMISSION_REQUEST_STORAGE = 2001
    }

    private lateinit var vault: SecurityVault
    private lateinit var apiClient: PrintApiClient
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var floatingPill: LinearLayout
    private lateinit var controlBar: LinearLayout
    private lateinit var barUrlInput: EditText
    private lateinit var barGoBtn: Button
    private lateinit var barDirectPrintBtn: Button
    private lateinit var barPairBtn: Button
    private lateinit var barCloseBtn: Button
    private lateinit var barBackBtn: Button
    private lateinit var barForwardBtn: Button
    private lateinit var barRefreshBtn: Button
    private lateinit var barFit1080Btn: Button
    private lateinit var barZoomOutBtn: Button
    private lateinit var barZoomInBtn: Button
    private lateinit var barZoomResetBtn: Button
    private lateinit var barEscToggle: CheckBox
    private lateinit var statusBeacon: TextView
    private var userZoomLevel = 1.0f

    private lateinit var pairingModal: FrameLayout
    private lateinit var pairHostInput: EditText
    private lateinit var pairPinInput: EditText
    private lateinit var pairSubmitBtn: Button
    private lateinit var pairCancelBtn: Button

    private lateinit var barAccountsBtn: Button
    private lateinit var accountsModal: FrameLayout
    private lateinit var acc1UserInput: EditText
    private lateinit var acc1PassInput: EditText
    private lateinit var acc1CopyUserBtn: Button
    private lateinit var acc1CopyPassBtn: Button
    private lateinit var acc1AutoFillBtn: Button
    private lateinit var acc2UserInput: EditText
    private lateinit var acc2PassInput: EditText
    private lateinit var acc2CopyUserBtn: Button
    private lateinit var acc2CopyPassBtn: Button
    private lateinit var acc2AutoFillBtn: Button
    private lateinit var accSaveBtn: Button
    private lateinit var accCloseBtn: Button

    private lateinit var barPharmaBtn: Button
    private lateinit var barVirtualKbdBtn: Button
    private lateinit var barCsvSyncBtn: Button

    private lateinit var csvAnalyticsModal: FrameLayout
    private lateinit var tvCsvStockCount: TextView
    private lateinit var tvCsvValuation: TextView
    private lateinit var tvCsvPartyCount: TextView
    private lateinit var tvCsvTotalOutstanding: TextView
    private lateinit var tvCsvTotalOverdue: TextView
    private lateinit var tvCsvSyncStatus: TextView
    private lateinit var tvCsvLastBackup: TextView
    private lateinit var btnScanSyncCsv: Button
    private lateinit var btnCloseCsvModal: Button
    private lateinit var floatingKeyboardContainer: LinearLayout
    private lateinit var kbdHeaderDrag: LinearLayout
    private lateinit var kbdModeToggleBtn: Button
    private lateinit var kbdOpacityBtn: Button
    private lateinit var kbdScaleDownBtn: Button
    private lateinit var kbdScaleUpBtn: Button
    private lateinit var kbdCloseBtn: Button
    private lateinit var panelPharmaRibbon: LinearLayout
    private lateinit var panelDpad: LinearLayout
    private lateinit var panelFullKeyboard: LinearLayout

    private var currentKbdMode = 0 // 0: Pharma Ribbon, 1: D-Pad, 2: Full Keyboard
    private var isFullKbdMode = false
    private var isKbdTransparent = false
    private var kbdScale = 1.0f
    private var lastShortcutToastTime = 0L

    private var isCtrlActive = false
    private var isAltActive = false
    private var isShiftActive = false
    private var isCapsActive = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm?.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIndex != -1 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                            val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val uriString = if (localUriIndex != -1) cursor.getString(localUriIndex) else null
                            if (uriString != null) {
                                val path = Uri.parse(uriString).path
                                if (path != null) {
                                    val file = File(path)
                                    if (file.exists()) {
                                        if (file.name.endsWith(".pdf", ignoreCase = true)) {
                                            showPdfPrintPreview(file, file.name)
                                        } else if (file.name.endsWith(".csv", ignoreCase = true)) {
                                            processIncomingCsv(file)
                                        }
                                    }
                                }
                            }
                        }
                        cursor.close()
                    }
                }
            }
        }
    }

    private var connectivityManager: ConnectivityManager? = null
    private var isConnectedToAgent = false

    private val hidePillRunnable = Runnable {
        floatingPill.animate().alpha(0.25f).setDuration(500).start()
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            checkAndAutoConnect()
            mainHandler.postDelayed(this, 10000) // Poll every 10 seconds for instant auto-connect
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vault = SecurityVault(applicationContext)
        apiClient = PrintApiClient(vault)

        initViews()
        ensureMargCloudStorageFolder()
        checkStoragePermissions()
        setupWebView()
        setupControlBar()
        setupPairingModal()
        setupAccountsModal()
        setupCsvAnalyticsModal()
        setupVirtualKeyboard()
        setupNetworkMonitoring()
        setupBackInvokedCallback()

        applyFullScreen()

        // Handle incoming shared PDF
        if (intent?.action == Intent.ACTION_SEND && intent?.type == "application/pdf") {
            handleIncomingSharedPdf(intent)
        } else {
            loadUrl(DEFAULT_HOME_URL)
        }

        // Start auto-connect loop
        mainHandler.post(heartbeatRunnable)

        // Register download complete listener for auto print preview
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    override fun onResume() {
        super.onResume()
        applyFullScreen()
        schedulePillFade()
        checkAndAutoConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(heartbeatRunnable)
        try {
            unregisterReceiver(downloadReceiver)
        } catch (ignored: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullScreen()
        }
    }

    /**
     * Automatically creates "Marg Cloud" folder inside device's public Download directory.
     */
    private fun ensureMargCloudStorageFolder(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val margFolder = File(downloads, "Marg Cloud")
        if (!margFolder.exists()) {
            val created = margFolder.mkdirs()
            if (created) {
                Toast.makeText(this, "Created local folder: Download/Marg Cloud", Toast.LENGTH_SHORT).show()
            }
        }
        return margFolder
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_STORAGE)
            }
        }
    }

    private fun initViews() {
        rootContainer = findViewById(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        floatingPill = findViewById(R.id.floatingPill)
        controlBar = findViewById(R.id.controlBar)

        barUrlInput = findViewById(R.id.barUrlInput)
        barGoBtn = findViewById(R.id.barGoBtn)
        barDirectPrintBtn = findViewById(R.id.barDirectPrintBtn)
        barPairBtn = findViewById(R.id.barPairBtn)
        barCloseBtn = findViewById(R.id.barCloseBtn)
        barBackBtn = findViewById(R.id.barBackBtn)
        barForwardBtn = findViewById(R.id.barForwardBtn)
        barRefreshBtn = findViewById(R.id.barRefreshBtn)
        barFit1080Btn = findViewById(R.id.barFit1080Btn)
        barZoomOutBtn = findViewById(R.id.barZoomOutBtn)
        barZoomInBtn = findViewById(R.id.barZoomInBtn)
        barZoomResetBtn = findViewById(R.id.barZoomResetBtn)
        barEscToggle = findViewById(R.id.barEscToggle)
        statusBeacon = findViewById(R.id.statusBeacon)

        pairingModal = findViewById(R.id.pairingModal)
        pairHostInput = findViewById(R.id.pairHostInput)
        pairPinInput = findViewById(R.id.pairPinInput)
        pairSubmitBtn = findViewById(R.id.pairSubmitBtn)
        pairCancelBtn = findViewById(R.id.pairCancelBtn)

        barAccountsBtn = findViewById(R.id.barAccountsBtn)
        accountsModal = findViewById(R.id.accountsModal)
        acc1UserInput = findViewById(R.id.acc1UserInput)
        acc1PassInput = findViewById(R.id.acc1PassInput)
        acc1CopyUserBtn = findViewById(R.id.acc1CopyUserBtn)
        acc1CopyPassBtn = findViewById(R.id.acc1CopyPassBtn)
        acc1AutoFillBtn = findViewById(R.id.acc1AutoFillBtn)
        acc2UserInput = findViewById(R.id.acc2UserInput)
        acc2PassInput = findViewById(R.id.acc2PassInput)
        acc2CopyUserBtn = findViewById(R.id.acc2CopyUserBtn)
        acc2CopyPassBtn = findViewById(R.id.acc2CopyPassBtn)
        acc2AutoFillBtn = findViewById(R.id.acc2AutoFillBtn)
        accSaveBtn = findViewById(R.id.accSaveBtn)
        accCloseBtn = findViewById(R.id.accCloseBtn)

        barPharmaBtn = findViewById(R.id.barPharmaBtn)
        barVirtualKbdBtn = findViewById(R.id.barVirtualKbdBtn)
        barCsvSyncBtn = findViewById(R.id.barCsvSyncBtn)

        csvAnalyticsModal = findViewById(R.id.csvAnalyticsModal)
        tvCsvStockCount = findViewById(R.id.tvCsvStockCount)
        tvCsvValuation = findViewById(R.id.tvCsvValuation)
        tvCsvPartyCount = findViewById(R.id.tvCsvPartyCount)
        tvCsvTotalOutstanding = findViewById(R.id.tvCsvTotalOutstanding)
        tvCsvTotalOverdue = findViewById(R.id.tvCsvTotalOverdue)
        tvCsvSyncStatus = findViewById(R.id.tvCsvSyncStatus)
        tvCsvLastBackup = findViewById(R.id.tvCsvLastBackup)
        btnScanSyncCsv = findViewById(R.id.btnScanSyncCsv)
        btnCloseCsvModal = findViewById(R.id.btnCloseCsvModal)

        floatingKeyboardContainer = findViewById(R.id.floatingKeyboardContainer)
        kbdHeaderDrag = findViewById(R.id.kbdHeaderDrag)
        kbdModeToggleBtn = findViewById(R.id.kbdModeToggleBtn)
        kbdOpacityBtn = findViewById(R.id.kbdOpacityBtn)
        kbdScaleDownBtn = findViewById(R.id.kbdScaleDownBtn)
        kbdScaleUpBtn = findViewById(R.id.kbdScaleUpBtn)
        kbdCloseBtn = findViewById(R.id.kbdCloseBtn)
        panelPharmaRibbon = findViewById(R.id.panelPharmaRibbon)
        panelDpad = findViewById(R.id.panelDpad)
        panelFullKeyboard = findViewById(R.id.panelFullKeyboard)

        barEscToggle.isChecked = vault.isEscMappingEnabled

        floatingPill.setOnClickListener {
            showControlBar()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Enable hardware acceleration for smooth 60fps/120fps scrolling and ERP graphics
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings

        // 1. ALWAYS DESKTOP MODE ON (Edge-to-edge native full screen)
        settings.userAgentString = DESKTOP_USER_AGENT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.textZoom = 100

        // Multi-touch & Pinch zoom controls
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)

        // 2. INDEXED DATABASE & CACHING SYSTEM
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 3. PASSWORD & AUTOFILL SUPPORT
        settings.saveFormData = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }

        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Persistent Cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }
        cookieManager.flush()

        webView.addJavascriptInterface(AndroidPrintBridge(), "AndroidPrintBridge")

        // 4. AUTOMATIC DOWNLOAD TO "Marg Cloud" FOLDER
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleFileDownload(url, contentDisposition, mimetype)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.endsWith(".pdf", ignoreCase = true) || url.contains(".pdf?", ignoreCase = true)) {
                    handleFileDownload(url, null, "application/pdf")
                    return true
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 15
                url?.let { barUrlInput.setText(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                url?.let { barUrlInput.setText(it) }
                CookieManager.getInstance().flush()
                applyFullScreen()

                // Inject print hook and blob download interceptor
                val jsHooks = """
                    (function() {
                        try {
                            window.print = function() {
                                if (window.AndroidPrintBridge) {
                                    window.AndroidPrintBridge.triggerDirectPrint();
                                }
                            };
                            if (!window._smartPrintBlobHooked) {
                                window._smartPrintBlobHooked = true;
                                document.addEventListener('click', function(e) {
                                    var a = e.target.closest('a');
                                    if (a && a.href && a.href.startsWith('blob:')) {
                                        fetch(a.href)
                                            .then(function(r) { return r.blob(); })
                                            .then(function(blob) {
                                                var reader = new FileReader();
                                                reader.onloadend = function() {
                                                    if (window.AndroidPrintBridge) {
                                                        window.AndroidPrintBridge.onBlobPdfReady(reader.result, a.download || 'Marg_Report.pdf');
                                                    }
                                                };
                                                reader.readAsDataURL(blob);
                                            }).catch(function(err){});
                                    }
                                }, true);
                            }
                        } catch(e) {}
                    })();
                """.trimIndent()
                webView.evaluateJavascript(jsHooks, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun handleFileDownload(url: String, contentDisposition: String?, mimetype: String?) {
        try {
            ensureMargCloudStorageFolder()
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)

            if (url.startsWith("blob:")) {
                fetchBlobAndPrint(url, fileName)
                return
            }

            if (url.startsWith("data:")) {
                saveDataUriAndPrint(url, fileName)
                return
            }

            if (url.startsWith("http://") || url.startsWith("https://")) {
                downloadDirectAndPrint(url, fileName, mimetype)
            } else {
                enqueueDownloadManager(url, contentDisposition, mimetype, fileName)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadDirectAndPrint(url: String, fileName: String, mimetype: String?) {
        Toast.makeText(this, "💾 Downloading $fileName to Marg Cloud...", Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val margFolder = ensureMargCloudStorageFolder()
                val targetFile = File(margFolder, fileName)

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) {
                    conn.setRequestProperty("Cookie", cookie)
                }
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()

                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    try {
                        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                        dm?.addCompletedDownload(
                            targetFile.name,
                            "Marg Cloud Document",
                            true,
                            mimetype ?: "application/pdf",
                            targetFile.absolutePath,
                            targetFile.length(),
                            true
                        )
                    } catch (ignored: Exception) {}

                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "📄 Saved: $fileName in Download/Marg Cloud", Toast.LENGTH_SHORT).show()
                        if (fileName.endsWith(".pdf", ignoreCase = true) || mimetype?.contains("pdf", ignoreCase = true) == true) {
                            showPdfPrintPreview(targetFile, fileName)
                        } else if (fileName.endsWith(".csv", ignoreCase = true) || mimetype?.contains("csv", ignoreCase = true) == true) {
                            processIncomingCsv(targetFile)
                        }
                    }
                } else {
                    mainHandler.post {
                        enqueueDownloadManager(url, null, mimetype, fileName)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    enqueueDownloadManager(url, null, mimetype, fileName)
                }
            }
        }
    }

    private fun enqueueDownloadManager(url: String, contentDisposition: String?, mimetype: String?, fileName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype ?: "application/pdf")
                addRequestHeader("User-Agent", DESKTOP_USER_AGENT)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookie)
                }
                setDescription("Downloading to Marg Cloud folder...")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Marg Cloud/$fileName")
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "💾 Downloading $fileName to Marg Cloud folder", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchBlobAndPrint(blobUrl: String, fileName: String) {
        val js = """
            (function() {
                try {
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', '$blobUrl', true);
                    xhr.responseType = 'blob';
                    xhr.onload = function() {
                        if (this.status === 200) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (window.AndroidPrintBridge) {
                                    window.AndroidPrintBridge.onBlobPdfReady(reader.result, '$fileName');
                                }
                            };
                            reader.readAsDataURL(this.response);
                        }
                    };
                    xhr.send();
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun saveDataUriAndPrint(dataUri: String, fileName: String) {
        executor.execute {
            try {
                val cleanBase64 = if (dataUri.contains(",")) dataUri.substringAfter(",") else dataUri
                val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                val targetFile = File(ensureMargCloudStorageFolder(), fileName)
                targetFile.writeBytes(bytes)
                mainHandler.post {
                    if (fileName.endsWith(".csv", ignoreCase = true)) {
                        processIncomingCsv(targetFile)
                    } else {
                        Toast.makeText(this@MainActivity, "📄 PDF Saved: $fileName", Toast.LENGTH_SHORT).show()
                        showPdfPrintPreview(targetFile, fileName)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "Failed saving file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPdfPrintPreview(file: File, documentName: String) {
        try {
            val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager != null && file.exists()) {
                val cleanName = documentName.substringBeforeLast(".")
                val printAttributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build()

                printManager.print(
                    cleanName,
                    PdfFilePrintAdapter(file, cleanName),
                    printAttributes
                )
                Toast.makeText(this, "🖨️ Opening Print Preview: $cleanName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Print preview error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    inner class AndroidPrintBridge {
        @JavascriptInterface
        fun onBlobPdfReady(base64Data: String, fileName: String?) {
            executor.execute {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                    val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                    val isCsv = fileName?.endsWith(".csv", ignoreCase = true) == true
                    val safeName = if (!fileName.isNullOrBlank()) fileName else (if (isCsv) "Marg_Export_${System.currentTimeMillis()}.csv" else "Marg_Document_${System.currentTimeMillis()}.pdf")
                    val finalName = if (safeName.endsWith(".pdf", ignoreCase = true) || safeName.endsWith(".csv", ignoreCase = true)) safeName else "$safeName.pdf"
                    val targetFile = File(ensureMargCloudStorageFolder(), finalName)
                    targetFile.writeBytes(bytes)

                    mainHandler.post {
                        if (finalName.endsWith(".csv", ignoreCase = true)) {
                            processIncomingCsv(targetFile)
                        } else {
                            Toast.makeText(this@MainActivity, "📄 PDF Saved: $finalName", Toast.LENGTH_SHORT).show()
                            showPdfPrintPreview(targetFile, finalName)
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "Failed saving PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun triggerDirectPrint() {
            mainHandler.post {
                this@MainActivity.triggerDirectPrint()
            }
        }
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder().build()
        connectivityManager?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // WiFi or network turned on/off: trigger instant reconnect
                mainHandler.post {
                    checkAndAutoConnect()
                }
            }
        })
    }

    /**
     * AUTO-START & AUTO-CONNECT LOGIC:
     * Automatically attempts connection & pairing using PIN 260890 and server 100.111.100.122:8766.
     * Recovers seamlessly if PC reboots or WiFi toggles.
     */
    private fun checkAndAutoConnect() {
        executor.execute {
            val health = apiClient.checkHealth()
            if (health != null) {
                // Connected! Check if paired; if not, auto-pair with default PIN 260890
                if (vault.getAuthToken() == null) {
                    val (paired, _) = apiClient.pairWithPin(
                        pin = vault.pairingPin,
                        deviceId = UUID.randomUUID().toString(),
                        deviceName = "Lenovo Tab P12"
                    )
                    isConnectedToAgent = paired
                } else {
                    isConnectedToAgent = true
                }

                if (isConnectedToAgent && MargCsvSyncManager.hasPendingSync(applicationContext)) {
                    val flushed = MargCsvSyncManager.flushPendingSync(applicationContext, apiClient)
                    if (flushed) {
                        mainHandler.post {
                            Toast.makeText(this@MainActivity, "🔄 Offline Marg data auto-synced to PC!", Toast.LENGTH_SHORT).show()
                            if (csvAnalyticsModal.visibility == View.VISIBLE) {
                                refreshCsvAnalyticsUi()
                            }
                        }
                    }
                }

                mainHandler.post {
                    statusBeacon.text = "Office PC: ONLINE (Brother DCP-T530DW)"
                    statusBeacon.setTextColor(0xFF10B981.toInt())
                }
            } else {
                // PC might be rebooting or WiFi offline
                isConnectedToAgent = false
                mainHandler.post {
                    statusBeacon.text = "Office PC: OFFLINE (${vault.serverHost}:8766) - Auto-connecting..."
                    statusBeacon.setTextColor(0xFFF59E0B.toInt())
                }
            }
        }
    }

    private fun setupControlBar() {
        barUrlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                handleBarGo()
                true
            } else false
        }

        barGoBtn.setOnClickListener { handleBarGo() }

        barDirectPrintBtn.setOnClickListener {
            triggerDirectPrint()
        }

        barAccountsBtn.setOnClickListener {
            showAccountsModal()
        }

        barPharmaBtn.setOnClickListener {
            togglePharmaRibbon()
        }

        barVirtualKbdBtn.setOnClickListener {
            toggleVirtualKeyboard()
        }

        barCsvSyncBtn.setOnClickListener {
            showCsvAnalyticsModal()
        }

        barPairBtn.setOnClickListener {
            showPairingModal()
        }

        barCloseBtn.setOnClickListener { hideControlBar() }

        barBackBtn.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else Toast.makeText(this, "No previous page", Toast.LENGTH_SHORT).show()
        }

        barForwardBtn.setOnClickListener {
            if (webView.canGoForward()) webView.goForward() else Toast.makeText(this, "No next page", Toast.LENGTH_SHORT).show()
        }

        barRefreshBtn.setOnClickListener {
            webView.reload()
            hideControlBar()
        }

        barFit1080Btn.setOnClickListener {
            apply1920DesktopResolution(forceReset = true)
            hideControlBar()
            Toast.makeText(this, "Fitted to 1920x1080 Desktop Viewport", Toast.LENGTH_SHORT).show()
        }

        barZoomOutBtn.setOnClickListener {
            changeZoom(-0.1f)
        }

        barZoomInBtn.setOnClickListener {
            changeZoom(0.1f)
        }

        barZoomResetBtn.setOnClickListener {
            userZoomLevel = 1.0f
            setBodyZoom(1.0f)
        }

        barEscToggle.setOnCheckedChangeListener { _, isChecked ->
            vault.isEscMappingEnabled = isChecked
            val msg = if (isChecked) "Tab Back -> Windows Esc (Active)" else "Tab Back -> Standard Android Back"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFitScalePercent(): Int {
        val dm = resources.displayMetrics
        val screenWidth = maxOf(dm.widthPixels, dm.heightPixels)
        val widthDp = screenWidth / dm.density
        val percent = ((widthDp / 1920f) * 100).toInt()
        return percent.coerceIn(35, 100)
    }

    private fun changeZoom(delta: Float) {
        userZoomLevel = (userZoomLevel + delta).coerceIn(0.4f, 2.5f)
        setBodyZoom(userZoomLevel)
    }

    private fun setBodyZoom(factor: Float) {
        val js = """
            (function() {
                try {
                    document.body.style.zoom = '$factor';
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "Zoom: ${(factor * 100).toInt()}%", Toast.LENGTH_SHORT).show()
    }

    private fun apply1920DesktopResolution(forceReset: Boolean = false) {
        if (forceReset) {
            userZoomLevel = 1.0f
            setBodyZoom(1.0f)
        }

        val js = """
            (function() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        (document.head || document.documentElement).appendChild(meta);
                    }
                    meta.content = 'width=1920, user-scalable=yes';

                    try {
                        Object.defineProperty(window.screen, 'width', { get: function() { return 1920; }, configurable: true });
                        Object.defineProperty(window.screen, 'height', { get: function() { return 1080; }, configurable: true });
                        Object.defineProperty(window.screen, 'availWidth', { get: function() { return 1920; }, configurable: true });
                        Object.defineProperty(window.screen, 'availHeight', { get: function() { return 1080; }, configurable: true });
                    } catch(e) {}
                } catch(err) {}
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun setupPairingModal() {
        pairHostInput.setText(vault.serverHost)
        pairPinInput.setText(vault.pairingPin)

        pairCancelBtn.setOnClickListener {
            pairingModal.visibility = View.GONE
            applyFullScreen()
        }

        pairSubmitBtn.setOnClickListener {
            val pin = pairPinInput.text.toString().trim()
            val host = pairHostInput.text.toString().trim()

            if (pin.isNotEmpty()) vault.pairingPin = pin
            if (host.isNotEmpty()) vault.serverHost = host

            Toast.makeText(this, "Connecting to Windows Print Agent...", Toast.LENGTH_SHORT).show()

            executor.execute {
                val (success, msg) = apiClient.pairWithPin(
                    pin = pin,
                    deviceId = UUID.randomUUID().toString(),
                    deviceName = "Lenovo Tab P12"
                )

                mainHandler.post {
                    if (success) {
                        Toast.makeText(this, "✓ Connected to Office PC!", Toast.LENGTH_LONG).show()
                        pairingModal.visibility = View.GONE
                        checkAndAutoConnect()
                    } else {
                        Toast.makeText(this, "Pairing failed: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setupAccountsModal() {
        acc1UserInput.setText(vault.acc1User)
        acc1PassInput.setText(vault.acc1Pass)
        acc2UserInput.setText(vault.acc2User)
        acc2PassInput.setText(vault.acc2Pass)

        acc1CopyUserBtn.setOnClickListener {
            copyToClipboard("Username", acc1UserInput.text.toString().trim())
        }

        acc1CopyPassBtn.setOnClickListener {
            copyToClipboard("Password", acc1PassInput.text.toString().trim())
        }

        acc1AutoFillBtn.setOnClickListener {
            val user = acc1UserInput.text.toString().trim()
            val pass = acc1PassInput.text.toString().trim()
            autoFillCredentials(user, pass, "Account 1")
        }

        acc2CopyUserBtn.setOnClickListener {
            copyToClipboard("Username", acc2UserInput.text.toString().trim())
        }

        acc2CopyPassBtn.setOnClickListener {
            copyToClipboard("Password", acc2PassInput.text.toString().trim())
        }

        acc2AutoFillBtn.setOnClickListener {
            val user = acc2UserInput.text.toString().trim()
            val pass = acc2PassInput.text.toString().trim()
            autoFillCredentials(user, pass, "Account 2")
        }

        accSaveBtn.setOnClickListener {
            vault.acc1User = acc1UserInput.text.toString().trim()
            vault.acc1Pass = acc1PassInput.text.toString().trim()
            vault.acc2User = acc2UserInput.text.toString().trim()
            vault.acc2Pass = acc2PassInput.text.toString().trim()
            Toast.makeText(this, "💾 Credentials saved successfully!", Toast.LENGTH_SHORT).show()
        }

        accCloseBtn.setOnClickListener {
            hideAccountsModal()
        }
    }

    private fun showAccountsModal() {
        hideControlBar()
        acc1UserInput.setText(vault.acc1User)
        acc1PassInput.setText(vault.acc1Pass)
        acc2UserInput.setText(vault.acc2User)
        acc2PassInput.setText(vault.acc2Pass)
        accountsModal.visibility = View.VISIBLE
    }

    private fun hideAccountsModal() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(acc1UserInput.windowToken, 0)
        accountsModal.visibility = View.GONE
        applyFullScreen()
    }

    private fun setupCsvAnalyticsModal() {
        btnScanSyncCsv.setOnClickListener {
            Toast.makeText(this, "🔄 Scanning Download/Marg Cloud for CSVs...", Toast.LENGTH_SHORT).show()
            executor.execute {
                val res = MargCsvSyncManager.scanAndSyncLocalFolder(applicationContext, apiClient, isConnectedToAgent)
                mainHandler.post {
                    Toast.makeText(this@MainActivity, res, Toast.LENGTH_LONG).show()
                    refreshCsvAnalyticsUi()
                }
            }
        }

        btnCloseCsvModal.setOnClickListener {
            hideCsvAnalyticsModal()
        }
    }

    private fun showCsvAnalyticsModal() {
        hideControlBar()
        refreshCsvAnalyticsUi()
        csvAnalyticsModal.visibility = View.VISIBLE
    }

    private fun hideCsvAnalyticsModal() {
        csvAnalyticsModal.visibility = View.GONE
        applyFullScreen()
    }

    private fun refreshCsvAnalyticsUi() {
        executor.execute {
            val summary = MargCsvSyncManager.getOfflineSummary(applicationContext)
            val backupsDir = MargCsvSyncManager.getBackupsDir()
            val backups = backupsDir.listFiles { _, name -> name.endsWith(".csv", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
            val latestBackupName = backups?.firstOrNull()?.name ?: "None yet"

            mainHandler.post {
                val stockCount = summary.optInt("stockCount", 0)
                val partyCount = summary.optInt("partyCount", 0)
                val outstanding = summary.optDouble("totalOutstanding", 0.0)
                val overdue = summary.optDouble("totalOverdue", 0.0)
                val lastUpdated = summary.optString("lastUpdated", "Never")

                tvCsvStockCount.text = "$stockCount items"
                tvCsvValuation.text = if (stockCount > 0) "Calculated from CSV" else "₹0.00"
                tvCsvPartyCount.text = "$partyCount chemists"
                tvCsvTotalOutstanding.text = "₹${String.format(Locale.US, "%,.2f", outstanding)}"
                tvCsvTotalOverdue.text = "₹${String.format(Locale.US, "%,.2f", overdue)}"
                tvCsvLastBackup.text = "Latest: $latestBackupName\nFolder: Download/Marg Cloud/Backups/\nUpdated: $lastUpdated"

                val hasPending = MargCsvSyncManager.hasPendingSync(applicationContext)
                if (isConnectedToAgent) {
                    if (hasPending) {
                        tvCsvSyncStatus.text = "⚠️ Offline Changes Queued (Pushing to PC...)"
                        tvCsvSyncStatus.setTextColor(0xFFF59E0B.toInt())
                    } else {
                        tvCsvSyncStatus.text = "🟢 Synced with Windows Agent (Port 8766)"
                        tvCsvSyncStatus.setTextColor(0xFF10B981.toInt())
                    }
                } else {
                    tvCsvSyncStatus.text = "🟡 Offline Mode (Changes cached locally in tablet)"
                    tvCsvSyncStatus.setTextColor(0xFFEF4444.toInt())
                }
            }
        }
    }

    private fun processIncomingCsv(file: File) {
        Toast.makeText(this, "📊 Analyzing & Backing up: ${file.name}...", Toast.LENGTH_SHORT).show()
        executor.execute {
            val result = MargCsvSyncManager.processDownloadedCsv(
                context = applicationContext,
                csvFile = file,
                apiClient = apiClient,
                isOnline = isConnectedToAgent
            )
            mainHandler.post {
                Toast.makeText(this@MainActivity, result.summaryMessage, Toast.LENGTH_LONG).show()
                if (csvAnalyticsModal.visibility == View.VISIBLE) {
                    refreshCsvAnalyticsUi()
                }
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this, "✓ $label copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun autoFillCredentials(user: String, pass: String, accountLabel: String) {
        hideAccountsModal()
        val escapedUser = JSONObject.quote(user)
        val escapedPass = JSONObject.quote(pass)

        val js = """
            (function() {
                try {
                    var u = $escapedUser;
                    var p = $escapedPass;
                    
                    // 1. Locate password input first
                    var passInput = document.querySelector('input[type="password"], input[formcontrolname="password"], input[name*="pass" i], input[id*="pass" i]');

                    // 2. Locate email/username input - strictly excluding password inputs
                    var emailInput = document.querySelector('input[type="email"], input[formcontrolname="emailId"], input[placeholder*="email" i], input[name*="email" i], input[id*="email" i]');
                    
                    if (!emailInput) {
                        var allInputs = Array.from(document.querySelectorAll('input'));
                        for (var i = 0; i < allInputs.length; i++) {
                            var inp = allInputs[i];
                            if (inp !== passInput && inp.type !== 'password' && inp.type !== 'hidden' && inp.type !== 'checkbox' && inp.type !== 'radio') {
                                emailInput = inp;
                                break;
                            }
                        }
                    }

                    var filledCount = 0;
                    if (emailInput) {
                        emailInput.focus();
                        emailInput.value = u;
                        emailInput.dispatchEvent(new Event('input', { bubbles: true }));
                        emailInput.dispatchEvent(new Event('change', { bubbles: true }));
                        filledCount++;
                    }

                    if (passInput) {
                        passInput.focus();
                        passInput.value = p;
                        passInput.dispatchEvent(new Event('input', { bubbles: true }));
                        passInput.dispatchEvent(new Event('change', { bubbles: true }));
                        filledCount++;
                    }
                    
                    return filledCount > 0;
                } catch(e) {
                    return false;
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { res ->
            if (res == "true") {
                Toast.makeText(this, "⚡ Injected $accountLabel credentials into Marg Cloud", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ Could not locate login fields. Use 📋 Copy buttons to paste manually.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun triggerDirectPrint() {
        hideControlBar()
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager != null) {
            val jobName = "Marg_Invoice_" + System.currentTimeMillis()
            val printAdapter = webView.createPrintDocumentAdapter(jobName)
            val printAttributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()

            printManager.print(jobName, printAdapter, printAttributes)
            Toast.makeText(this, "Select 'Brother DCP-T530DW (Office PC)' in the print dialog.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Print service unavailable on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIncomingSharedPdf(intent: Intent) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri != null) {
            Toast.makeText(this, "Transmitting shared PDF to Brother DCP-T530DW...", Toast.LENGTH_LONG).show()
            executor.execute {
                try {
                    val input = contentResolver.openInputStream(uri)
                    val bytes = input?.readBytes() ?: return@execute
                    input.close()

                    val result = apiClient.submitPrintJob(bytes, "Marg_Shared_Document.pdf")
                    mainHandler.post {
                        if (result.state == "Completed" || result.state == "Queued" || result.state == "Spooling") {
                            Toast.makeText(this, "✓ Sent to Brother Printer spooler successfully!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this, "Print failed: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        Toast.makeText(this, "Error reading PDF: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showPairingModal() {
        hideControlBar()
        pairHostInput.setText(vault.serverHost)
        pairPinInput.setText(vault.pairingPin)
        pairingModal.visibility = View.VISIBLE
    }

    private fun handleBarGo() {
        val input = barUrlInput.text.toString().trim()
        if (input.isNotEmpty()) {
            val url = if (!input.startsWith("http://") && !input.startsWith("https://")) "https://$input" else input
            loadUrl(url)
        }
        hideControlBar()
    }

    private fun loadUrl(url: String) {
        barUrlInput.setText(url)
        webView.loadUrl(url)
        applyFullScreen()
    }

    private fun showControlBar() {
        mainHandler.removeCallbacks(hidePillRunnable)
        floatingPill.animate().alpha(1.0f).setDuration(150).start()
        controlBar.visibility = View.VISIBLE
        barUrlInput.requestFocus()
    }

    private fun hideControlBar() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(barUrlInput.windowToken, 0)
        controlBar.visibility = View.GONE
        applyFullScreen()
        schedulePillFade()
    }

    private fun schedulePillFade() {
        mainHandler.removeCallbacks(hidePillRunnable)
        floatingPill.alpha = 1.0f
        mainHandler.postDelayed(hidePillRunnable, 4000)
    }

    private fun applyFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    private var lastBackNoticeTime = 0L
    private var lastBackActionTime = 0L
    private val BACK_DEBOUNCE_MS = 250L

    /**
     * ANDROID 13+ PREDICTIVE / SYSTEM BACK GESTURE HANDLER:
     * Intercepts edge-swipe and navigation bar back gestures so Android NEVER closes the app.
     */
    private fun setupBackInvokedCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                handleBackAction()
            }
        }
    }

    /**
     * LEGACY ANDROID BACK PRESS HANDLER:
     * Completely overrides onBackPressed. Never calls super.onBackPressed(), ensuring APK cannot close on Back.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackAction()
    }

    /**
     * HARDWARE & SYSTEM KEY DOWN HANDLER:
     * Consumes Back and Esc keydown events (action is executed exclusively in dispatchKeyEvent).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return true // Consumed: prevents duplicate action
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * HARDWARE & SYSTEM KEY UP HANDLER:
     * Consumes Back and Esc keyup events.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * DISPATCH KEY EVENT INTERCEPTOR:
     * 1. Intercepts Alt+Number / Ctrl+Number combinations for physical keyboards without F1-F12
     *    (specifically tailored for Lenovo Tab P12 accessory keyboard).
     * 2. Intercepts physical F1-F12 and synthesizes standard Marg Cloud events.
     * 3. Intercepts Back & Esc at root window, executes once on initial press, and consumes event.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isAlt = event.isAltPressed
        val isCtrl = event.isCtrlPressed
        val action = event.action

        // 1. Lenovo Tab P12 Custom Physical Keyboard Mappings (No F1-F12 keys on physical accessory)
        if (isAlt || isCtrl) {
            val mapped = when (keyCode) {
                KeyEvent.KEYCODE_1 -> Triple("F1", "F1", 112)
                KeyEvent.KEYCODE_2 -> Triple("F2", "F2", 113) // Date
                KeyEvent.KEYCODE_3 -> Triple("F3", "F3", 114) // Item Master / Search
                KeyEvent.KEYCODE_4 -> Triple("F4", "F4", 115)
                KeyEvent.KEYCODE_5 -> Triple("F5", "F5", 116)
                KeyEvent.KEYCODE_6 -> Triple("F6", "F6", 117)
                KeyEvent.KEYCODE_7 -> Triple("F7", "F7", 118) // Cash / Credit
                KeyEvent.KEYCODE_8 -> Triple("F8", "F8", 119) // Bill Modify / Edit
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_P -> Triple("F9", "F9", 120) // Print Bill
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_S -> Triple("F10", "F10", 121) // Save Bill
                KeyEvent.KEYCODE_MINUS -> Triple("F11", "F11", 122)
                KeyEvent.KEYCODE_EQUALS -> Triple("F12", "F12", 123)
                KeyEvent.KEYCODE_E -> Triple("Escape", "Escape", 27)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Triple("Enter", "Enter", 13)
                else -> null
            }
            if (mapped != null) {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    sendKeyToWeb(mapped.first, mapped.second, mapped.third, null)
                    showPharmaShortcutToast(mapped.first)
                }
                return true // Consume: strictly prevents browser from typing digits into inputs
            }
        }

        // 2. Hardware F1-F12 direct pass-through
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            val fNum = keyCode - KeyEvent.KEYCODE_F1 + 1
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                sendKeyToWeb("F$fNum", "F$fNum", 111 + fNum, null)
                showPharmaShortcutToast("F$fNum")
            }
            return true
        }

        // 3. Hardware Escape & Back Lock
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                handleBackAction()
            }
            return true // ALWAYS consume: locks APK and blocks double-firing
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showPharmaShortcutToast(keyName: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastShortcutToastTime > 1200) {
            lastShortcutToastTime = now
            val desc = when (keyName) {
                "F2" -> "F2 (Date)"
                "F3" -> "F3 (Item Master)"
                "F7" -> "F7 (Cash/Credit)"
                "F8" -> "F8 (Modify Bill)"
                "F9" -> "F9 (Print Bill)"
                "F10" -> "F10 (Save Bill)"
                "Escape" -> "ESC"
                else -> keyName
            }
            Toast.makeText(this, "⚡ Shortcut: $desc", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * LOCKED APK BACK ACTION (DEBOUNCED):
     * Executes exactly once per press. Dismisses open dialogs/control bar or sends
     * a single synthetic Windows Escape event to Marg Cloud.
     */
    private fun handleBackAction() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackActionTime < BACK_DEBOUNCE_MS) {
            return // Ignore bounce/repeat events within 250ms
        }
        lastBackActionTime = now

        if (csvAnalyticsModal.visibility == View.VISIBLE) {
            hideCsvAnalyticsModal()
            return
        }

        if (accountsModal.visibility == View.VISIBLE) {
            hideAccountsModal()
            return
        }

        if (pairingModal.visibility == View.VISIBLE) {
            pairingModal.visibility = View.GONE
            applyFullScreen()
            return
        }

        if (controlBar.visibility == View.VISIBLE) {
            hideControlBar()
            return
        }

        // Inject single Windows Escape key into Marg Cloud web session
        injectWindowsEscapeKey()

        // Inform user that APK is locked and only Home button exits (throttled)
        if (now - lastBackNoticeTime > 3500) {
            lastBackNoticeTime = now
            Toast.makeText(this, "🔒 Marg Cloud is Locked: Press Home button ⭕ to exit", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectWindowsEscapeKey() {
        val jsEsc = """
            (function() {
                try {
                    var target = document.activeElement || document.body || document.documentElement;
                    var down = new KeyboardEvent('keydown', {
                        key: 'Escape',
                        code: 'Escape',
                        keyCode: 27,
                        which: 27,
                        bubbles: true,
                        cancelable: true
                    });
                    var up = new KeyboardEvent('keyup', {
                        key: 'Escape',
                        code: 'Escape',
                        keyCode: 27,
                        which: 27,
                        bubbles: true,
                        cancelable: true
                    });
                    target.dispatchEvent(down);
                    target.dispatchEvent(up);
                } catch(e) { }
            })();
        """.trimIndent()
        webView.evaluateJavascript(jsEsc, null)
    }

    private fun setupVirtualKeyboard() {
        setupDraggableKeyboard()
        setupKeyboardControls()

        // Bind Pharma Ribbon keys
        bindKey(R.id.pharmaEsc, "Escape", "Escape", 27)
        bindKey(R.id.pharmaF2, "F2", "F2", 113)
        bindKey(R.id.pharmaF3, "F3", "F3", 114)
        bindKey(R.id.pharmaF7, "F7", "F7", 118)
        bindKey(R.id.pharmaF8, "F8", "F8", 119)
        bindKey(R.id.pharmaF9, "F9", "F9", 120)
        bindKey(R.id.pharmaF10, "F10", "F10", 121)
        bindKey(R.id.pharmaEnter, "Enter", "Enter", 13)

        setKeyboardMode(0)

        // Bind D-Pad keys
        bindKey(R.id.dpadF1, "F1", "F1", 112)
        bindKey(R.id.dpadF2, "F2", "F2", 113)
        bindKey(R.id.dpadF3, "F3", "F3", 114)
        bindKey(R.id.dpadF4, "F4", "F4", 115)
        bindKey(R.id.dpadF5, "F5", "F5", 116)
        bindKey(R.id.dpadF6, "F6", "F6", 117)
        bindKey(R.id.dpadF7, "F7", "F7", 118)
        bindKey(R.id.dpadF8, "F8", "F8", 119)

        bindKey(R.id.dpadEsc, "Escape", "Escape", 27)
        bindKey(R.id.dpadUp, "ArrowUp", "ArrowUp", 38)
        bindKey(R.id.dpadTab, "Tab", "Tab", 9)
        bindKey(R.id.dpadLeft, "ArrowLeft", "ArrowLeft", 37)
        bindKey(R.id.dpadEnter, "Enter", "Enter", 13)
        bindKey(R.id.dpadRight, "ArrowRight", "ArrowRight", 39)
        bindKey(R.id.dpadBksp, "Backspace", "Backspace", 8)
        bindKey(R.id.dpadDown, "ArrowDown", "ArrowDown", 40)
        bindKey(R.id.dpadDel, "Delete", "Delete", 46)

        bindKey(R.id.dpadN1, "1", "Digit1", 49, "1")
        bindKey(R.id.dpadN2, "2", "Digit2", 50, "2")
        bindKey(R.id.dpadN3, "3", "Digit3", 51, "3")
        bindKey(R.id.dpadN4, "4", "Digit4", 52, "4")
        bindKey(R.id.dpadN5, "5", "Digit5", 53, "5")
        bindKey(R.id.dpadN6, "6", "Digit6", 54, "6")
        bindKey(R.id.dpadN7, "7", "Digit7", 55, "7")
        bindKey(R.id.dpadN8, "8", "Digit8", 56, "8")
        bindKey(R.id.dpadN9, "9", "Digit9", 57, "9")
        bindKey(R.id.dpadN0, "0", "Digit0", 48, "0")
        bindKey(R.id.dpadDot, ".", "Period", 190, ".")
        bindKey(R.id.dpadPlus, "+", "NumpadAdd", 107, "+")

        // Bind Full Keyboard keys
        bindKey(R.id.kEsc, "Escape", "Escape", 27)
        bindKey(R.id.kF1, "F1", "F1", 112)
        bindKey(R.id.kF2, "F2", "F2", 113)
        bindKey(R.id.kF3, "F3", "F3", 114)
        bindKey(R.id.kF4, "F4", "F4", 115)
        bindKey(R.id.kF5, "F5", "F5", 116)
        bindKey(R.id.kF6, "F6", "F6", 117)
        bindKey(R.id.kF7, "F7", "F7", 118)
        bindKey(R.id.kF8, "F8", "F8", 119)
        bindKey(R.id.kF9, "F9", "F9", 120)
        bindKey(R.id.kF10, "F10", "F10", 121)
        bindKey(R.id.kF11, "F11", "F11", 122)
        bindKey(R.id.kF12, "F12", "F12", 123)
        bindKey(R.id.kDel, "Delete", "Delete", 46)

        bindKey(R.id.kBackquote, "`", "Backquote", 192, "`")
        bindKey(R.id.k1, "1", "Digit1", 49, "1")
        bindKey(R.id.k2, "2", "Digit2", 50, "2")
        bindKey(R.id.k3, "3", "Digit3", 51, "3")
        bindKey(R.id.k4, "4", "Digit4", 52, "4")
        bindKey(R.id.k5, "5", "Digit5", 53, "5")
        bindKey(R.id.k6, "6", "Digit6", 54, "6")
        bindKey(R.id.k7, "7", "Digit7", 55, "7")
        bindKey(R.id.k8, "8", "Digit8", 56, "8")
        bindKey(R.id.k9, "9", "Digit9", 57, "9")
        bindKey(R.id.k0, "0", "Digit0", 48, "0")
        bindKey(R.id.kMinus, "-", "Minus", 189, "-")
        bindKey(R.id.kEqual, "=", "Equal", 187, "=")
        bindKey(R.id.kBksp, "Backspace", "Backspace", 8)

        bindKey(R.id.kTab, "Tab", "Tab", 9)
        bindKey(R.id.kQ, "q", "KeyQ", 81, "q")
        bindKey(R.id.kW, "w", "KeyW", 87, "w")
        bindKey(R.id.kE, "e", "KeyE", 69, "e")
        bindKey(R.id.kR, "r", "KeyR", 82, "r")
        bindKey(R.id.kT, "t", "KeyT", 84, "t")
        bindKey(R.id.kY, "y", "KeyY", 89, "y")
        bindKey(R.id.kU, "u", "KeyU", 85, "u")
        bindKey(R.id.kI, "i", "KeyI", 73, "i")
        bindKey(R.id.kO, "o", "KeyO", 79, "o")
        bindKey(R.id.kP, "p", "KeyP", 80, "p")
        bindKey(R.id.kLBracket, "[", "BracketLeft", 219, "[")
        bindKey(R.id.kRBracket, "]", "BracketRight", 221, "]")
        bindKey(R.id.kBackslash, "\\", "Backslash", 220, "\\")

        bindKey(R.id.kA, "a", "KeyA", 65, "a")
        bindKey(R.id.kS, "s", "KeyS", 83, "s")
        bindKey(R.id.kD, "d", "KeyD", 68, "d")
        bindKey(R.id.kF, "f", "KeyF", 70, "f")
        bindKey(R.id.kG, "g", "KeyG", 71, "g")
        bindKey(R.id.kH, "h", "KeyH", 72, "h")
        bindKey(R.id.kJ, "j", "KeyJ", 74, "j")
        bindKey(R.id.kK, "k", "KeyK", 75, "k")
        bindKey(R.id.kL, "l", "KeyL", 76, "l")
        bindKey(R.id.kSemicolon, ";", "Semicolon", 186, ";")
        bindKey(R.id.kQuote, "'", "Quote", 222, "'")
        bindKey(R.id.kEnter, "Enter", "Enter", 13)

        bindKey(R.id.kZ, "z", "KeyZ", 90, "z")
        bindKey(R.id.kX, "x", "KeyX", 88, "x")
        bindKey(R.id.kC, "c", "KeyC", 67, "c")
        bindKey(R.id.kV, "v", "KeyV", 86, "v")
        bindKey(R.id.kB, "b", "KeyB", 66, "b")
        bindKey(R.id.kN, "n", "KeyN", 78, "n")
        bindKey(R.id.kM, "m", "KeyM", 77, "m")
        bindKey(R.id.kComma, ",", "Comma", 188, ",")
        bindKey(R.id.kPeriod, ".", "Period", 190, ".")
        bindKey(R.id.kSlash, "/", "Slash", 191, "/")
        bindKey(R.id.kUp, "ArrowUp", "ArrowUp", 38)
        bindKey(R.id.kPgUp, "PageUp", "PageUp", 33)

        bindKey(R.id.kHome, "Home", "Home", 36)
        bindKey(R.id.kSpace, " ", "Space", 32, " ")
        bindKey(R.id.kEnd, "End", "End", 35)
        bindKey(R.id.kLeft, "ArrowLeft", "ArrowLeft", 37)
        bindKey(R.id.kDown, "ArrowDown", "ArrowDown", 40)
        bindKey(R.id.kRight, "ArrowRight", "ArrowRight", 39)
        bindKey(R.id.kPgDn, "PageDown", "PageDown", 34)

        // Modifiers
        setupModifierKey(R.id.kCtrl)
        setupModifierKey(R.id.kAlt)
        setupModifierKey(R.id.kShift)
        setupModifierKey(R.id.kCaps)
    }

    private fun setupDraggableKeyboard() {
        var initialX = 0f
        var initialY = 0f
        var initialTouchX = 0f
        var initialTouchY = 0f

        kbdHeaderDrag.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingKeyboardContainer.x
                    initialY = floatingKeyboardContainer.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    val parentWidth = rootContainer.width.toFloat()
                    val parentHeight = rootContainer.height.toFloat()
                    val kbdWidth = floatingKeyboardContainer.width.toFloat() * floatingKeyboardContainer.scaleX
                    val kbdHeight = floatingKeyboardContainer.height.toFloat() * floatingKeyboardContainer.scaleY

                    var newX = initialX + dx
                    var newY = initialY + dy

                    newX = newX.coerceIn(0f, maxOf(0f, parentWidth - kbdWidth))
                    newY = newY.coerceIn(0f, maxOf(0f, parentHeight - kbdHeight))

                    floatingKeyboardContainer.x = newX
                    floatingKeyboardContainer.y = newY
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePharmaRibbon() {
        if (floatingKeyboardContainer.visibility == View.VISIBLE && currentKbdMode == 0) {
            floatingKeyboardContainer.visibility = View.GONE
        } else {
            hideControlBar()
            setKeyboardMode(0)
            floatingKeyboardContainer.visibility = View.VISIBLE
            Toast.makeText(this, "⚡ Pharma Quick Bar active (F2, F3, F7, F8, F9, F10, ESC)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleVirtualKeyboard() {
        if (floatingKeyboardContainer.visibility == View.VISIBLE && currentKbdMode != 0) {
            floatingKeyboardContainer.visibility = View.GONE
        } else {
            hideControlBar()
            setKeyboardMode(1) // Open D-Pad mode
            floatingKeyboardContainer.visibility = View.VISIBLE
            Toast.makeText(this, "⌨ Virtual D-Pad active: Drag header to move anywhere", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setKeyboardMode(mode: Int) {
        currentKbdMode = mode
        when (mode) {
            0 -> {
                panelPharmaRibbon.visibility = View.VISIBLE
                panelDpad.visibility = View.GONE
                panelFullKeyboard.visibility = View.GONE
                kbdModeToggleBtn.text = "▲ D-Pad Mode"
            }
            1 -> {
                panelPharmaRibbon.visibility = View.GONE
                panelDpad.visibility = View.VISIBLE
                panelFullKeyboard.visibility = View.GONE
                kbdModeToggleBtn.text = "⌨ Full Keyboard"
            }
            2 -> {
                panelPharmaRibbon.visibility = View.GONE
                panelDpad.visibility = View.GONE
                panelFullKeyboard.visibility = View.VISIBLE
                kbdModeToggleBtn.text = "⚡ Pharma Bar"
            }
        }
    }

    private fun setupKeyboardControls() {
        kbdModeToggleBtn.setOnClickListener {
            val nextMode = (currentKbdMode + 1) % 3
            setKeyboardMode(nextMode)
        }

        kbdOpacityBtn.setOnClickListener {
            isKbdTransparent = !isKbdTransparent
            floatingKeyboardContainer.alpha = if (isKbdTransparent) 0.72f else 1.0f
            kbdOpacityBtn.text = if (isKbdTransparent) "👁 72%" else "👁 100%"
        }

        kbdScaleDownBtn.setOnClickListener {
            changeKbdScale(-0.1f)
        }

        kbdScaleUpBtn.setOnClickListener {
            changeKbdScale(0.1f)
        }

        kbdCloseBtn.setOnClickListener {
            floatingKeyboardContainer.visibility = View.GONE
        }
    }

    private fun changeKbdScale(delta: Float) {
        kbdScale = (kbdScale + delta).coerceIn(0.65f, 1.35f)
        floatingKeyboardContainer.pivotX = 0f
        floatingKeyboardContainer.pivotY = 0f
        floatingKeyboardContainer.scaleX = kbdScale
        floatingKeyboardContainer.scaleY = kbdScale
        Toast.makeText(this, "Scale: ${(kbdScale * 100).toInt()}%", Toast.LENGTH_SHORT).show()
    }

    private fun bindKey(id: Int, key: String, code: String, keyCode: Int, textChar: String? = null) {
        findViewById<Button>(id)?.setOnClickListener {
            sendKeyToWeb(key, code, keyCode, textChar)
        }
    }

    private fun setupModifierKey(id: Int) {
        val btn = findViewById<Button>(id) ?: return
        btn.setOnClickListener {
            when (id) {
                R.id.kCtrl -> isCtrlActive = !isCtrlActive
                R.id.kAlt -> isAltActive = !isAltActive
                R.id.kShift -> isShiftActive = !isShiftActive
                R.id.kCaps -> isCapsActive = !isCapsActive
            }
            updateModifierButtonStyles()
        }
    }

    private fun updateModifierButtonStyles() {
        val btnCtrl = findViewById<Button>(R.id.kCtrl)
        val btnAlt = findViewById<Button>(R.id.kAlt)
        val btnShift = findViewById<Button>(R.id.kShift)
        val btnCaps = findViewById<Button>(R.id.kCaps)

        btnCtrl?.setBackgroundResource(if (isCtrlActive) R.drawable.bg_key_latched else R.drawable.bg_key_normal)
        btnAlt?.setBackgroundResource(if (isAltActive) R.drawable.bg_key_latched else R.drawable.bg_key_normal)
        btnShift?.setBackgroundResource(if (isShiftActive) R.drawable.bg_key_latched else R.drawable.bg_key_normal)
        btnCaps?.setBackgroundResource(if (isCapsActive) R.drawable.bg_key_latched else R.drawable.bg_key_normal)
    }

    private fun sendKeyToWeb(key: String, code: String, keyCode: Int, textChar: String? = null) {
        val ctrl = isCtrlActive
        val alt = isAltActive
        val shift = isShiftActive || isCapsActive

        val finalChar = if (textChar != null) {
            if (shift) textChar.uppercase() else textChar.lowercase()
        } else null

        val finalKey = if (textChar != null) (finalChar ?: key) else key

        val js = """
            (function() {
                try {
                    var target = document.activeElement || document.body || document.documentElement;
                    var evtDown = new KeyboardEvent('keydown', {
                        key: '$finalKey',
                        code: '$code',
                        keyCode: $keyCode,
                        which: $keyCode,
                        ctrlKey: $ctrl,
                        altKey: $alt,
                        shiftKey: $shift,
                        bubbles: true,
                        cancelable: true
                    });
                    var evtPress = new KeyboardEvent('keypress', {
                        key: '$finalKey',
                        code: '$code',
                        keyCode: $keyCode,
                        which: $keyCode,
                        ctrlKey: $ctrl,
                        altKey: $alt,
                        shiftKey: $shift,
                        bubbles: true,
                        cancelable: true
                    });
                    var evtUp = new KeyboardEvent('keyup', {
                        key: '$finalKey',
                        code: '$code',
                        keyCode: $keyCode,
                        which: $keyCode,
                        ctrlKey: $ctrl,
                        altKey: $alt,
                        shiftKey: $shift,
                        bubbles: true,
                        cancelable: true
                    });

                    target.dispatchEvent(evtDown);
                    target.dispatchEvent(evtPress);
                    target.dispatchEvent(evtUp);

                    // If typing into an input/textarea element
                    if (${finalChar != null} && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
                        var ch = '$finalChar';
                        var start = target.selectionStart || 0;
                        var end = target.selectionEnd || 0;
                        var val = target.value || '';
                        target.value = val.substring(0, start) + ch + val.substring(end);
                        target.selectionStart = target.selectionEnd = start + ch.length;
                        target.dispatchEvent(new Event('input', { bubbles: true }));
                        target.dispatchEvent(new Event('change', { bubbles: true }));
                    } else if ('$key' === 'Backspace' && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
                        var start = target.selectionStart || 0;
                        var end = target.selectionEnd || 0;
                        var val = target.value || '';
                        if (start === end && start > 0) {
                            target.value = val.substring(0, start - 1) + val.substring(end);
                            target.selectionStart = target.selectionEnd = start - 1;
                            target.dispatchEvent(new Event('input', { bubbles: true }));
                            target.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        // Native Android key event dispatch
        val androidCode = mapToAndroidKeyCode(keyCode)
        if (androidCode != KeyEvent.KEYCODE_UNKNOWN) {
            val meta = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
                       (if (alt) KeyEvent.META_ALT_ON else 0) or
                       (if (shift) KeyEvent.META_SHIFT_ON else 0)
            webView.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, androidCode, 0, meta))
            webView.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, androidCode, 0, meta))
        }

        // Auto release single-shot modifiers
        if (isShiftActive) {
            isShiftActive = false
            updateModifierButtonStyles()
        }
        if (isAltActive) {
            isAltActive = false
            updateModifierButtonStyles()
        }
        if (isCtrlActive) {
            isCtrlActive = false
            updateModifierButtonStyles()
        }
    }

    private fun mapToAndroidKeyCode(keyCode: Int): Int {
        return when (keyCode) {
            27 -> KeyEvent.KEYCODE_ESCAPE
            13 -> KeyEvent.KEYCODE_ENTER
            9 -> KeyEvent.KEYCODE_TAB
            8 -> KeyEvent.KEYCODE_DEL
            46 -> KeyEvent.KEYCODE_FORWARD_DEL
            38 -> KeyEvent.KEYCODE_DPAD_UP
            40 -> KeyEvent.KEYCODE_DPAD_DOWN
            37 -> KeyEvent.KEYCODE_DPAD_LEFT
            39 -> KeyEvent.KEYCODE_DPAD_RIGHT
            32 -> KeyEvent.KEYCODE_SPACE
            33 -> KeyEvent.KEYCODE_PAGE_UP
            34 -> KeyEvent.KEYCODE_PAGE_DOWN
            36 -> KeyEvent.KEYCODE_MOVE_HOME
            35 -> KeyEvent.KEYCODE_MOVE_END
            112 -> KeyEvent.KEYCODE_F1
            113 -> KeyEvent.KEYCODE_F2
            114 -> KeyEvent.KEYCODE_F3
            115 -> KeyEvent.KEYCODE_F4
            116 -> KeyEvent.KEYCODE_F5
            117 -> KeyEvent.KEYCODE_F6
            118 -> KeyEvent.KEYCODE_F7
            119 -> KeyEvent.KEYCODE_F8
            120 -> KeyEvent.KEYCODE_F9
            121 -> KeyEvent.KEYCODE_F10
            122 -> KeyEvent.KEYCODE_F11
            123 -> KeyEvent.KEYCODE_F12
            in 48..57 -> KeyEvent.KEYCODE_0 + (keyCode - 48)
            in 65..90 -> KeyEvent.KEYCODE_A + (keyCode - 65)
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }
}

