package kr.pcab.cam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 조립 검수 카메라 — 화면/판정 로직은 업무데이터 웹(1차 검수)이 담당하고,
 * 이 앱은 카메라 + 휴대폰 내장 글자/바코드 인식만 맡는다 (웹 수정만으로 대부분 개선 가능).
 *
 * JS 브리지 (window.PCABCam)
 *  start(pct, mode)  카메라를 화면 위쪽 pct% 높이로 켬. mode = "doc"(전표: 글자+바코드) | "cmos"(글자만)
 *  stop()            카메라 끄기
 *  pause(on)         실시간 인식 잠시 멈춤/재개 (카메라 화면은 유지)
 *  shot(save, tag)   고화질 1장 촬영 → save=true면 앨범(Pictures/조립검수)에 저장 → 그 사진을 다시 인식해서 결과 전달
 *  torch(on)         손전등
 *  beep(kind)        짧은 확인음 (ok | bad)
 *  version()         앱 버전
 * 결과는 window.__cam(json) 으로 전달:
 *  {k:"live"|"shot"|"err"|"state", w, h, ms, lines:[[text,l,t,r,b]...], codes:[[value,l,t,r,b]...], uri, tag, msg}
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val START_URL = "https://kic02309.github.io/PCAssemblyBuild/?app=cam"
    }

    private lateinit var root: LinearLayout
    private lateinit var previewView: PreviewView
    private lateinit var web: WebView

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private val camExec: ExecutorService = Executors.newSingleThreadExecutor()

    private val textRec: TextRecognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val barcodeRec: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF, Barcode.FORMAT_EAN_13, Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    @Volatile private var mode = "off"          // off | doc | cmos
    @Volatile private var paused = false
    private val busy = AtomicBoolean(false)
    @Volatile private var lastRun = 0L
    private var pendingStart: Pair<Int, String>? = null
    private var tone: ToneGenerator? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pendingStart
        pendingStart = null
        if (ok && p != null) startCamera(p.first, p.second)
        else if (!ok) emit(JSONObject().put("k", "err").put("msg", "카메라 권한이 필요합니다. 설정 → 앱 → 조립 검수 카메라 → 권한에서 카메라를 허용하세요."))
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F2340"))
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = View.GONE
        }
        web = WebView(this)
        root.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 화면을 누르면 그 자리에 초점
        previewView.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val cam = camera
                if (cam != null) {
                    val pt = previewView.meteringPointFactory.createPoint(ev.x, ev.y)
                    cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                }
                v.performClick()
            }
            true
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString PCABCam/${appVersion()}"
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "PCABCam")
        if (savedInstanceState == null) web.loadUrl(START_URL) else web.restoreState(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__appBack&&window.__appBack())||'exit'") { r ->
                    if (r == null || r.contains("exit")) finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        camExec.shutdown()
        tone?.release()
        super.onDestroy()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun emit(o: JSONObject) {
        val s = o.toString()
        runOnUiThread { web.evaluateJavascript("window.__cam&&window.__cam($s)", null) }
    }

    // ───────────── 카메라 ─────────────
    private fun startCamera(pct: Int, m: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = Pair(pct, m)
            permLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        mode = m
        paused = false
        val h = (root.height * pct.coerceIn(20, 90) / 100f).toInt()
        previewView.layoutParams = (previewView.layoutParams as LinearLayout.LayoutParams).apply { height = h }
        previewView.visibility = View.VISIBLE
        root.requestLayout()

        if (provider != null && camera != null) { emit(JSONObject().put("k", "state").put("msg", "on")); return }
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val p = fut.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(camExec) { proxy -> analyze(proxy) }
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                    .build()
                capture = cap
                p.unbindAll()
                camera = try {
                    p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, cap)
                } catch (e: Exception) {
                    // 일부 기종: 세 가지를 동시에 못 켬 → 미리보기 + 인식만
                    capture = null
                    p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
                emit(JSONObject().put("k", "state").put("msg", "on").put("capture", capture != null))
            } catch (e: Exception) {
                emit(JSONObject().put("k", "err").put("msg", "카메라를 켜지 못했습니다: ${e.message}"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        mode = "off"
        try { provider?.unbindAll() } catch (_: Exception) {}
        camera = null
        capture = null
        previewView.visibility = View.GONE
        previewView.layoutParams = (previewView.layoutParams as LinearLayout.LayoutParams).apply { height = 0 }
        root.requestLayout()
        emit(JSONObject().put("k", "state").put("msg", "off"))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val m = mode
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        if (m == "off" || paused || media == null || now - lastRun < 250 || !busy.compareAndSet(false, true)) {
            proxy.close(); return
        }
        lastRun = now
        val rot = proxy.imageInfo.rotationDegrees
        val img = InputImage.fromMediaImage(media, rot)
        val w = if (rot % 180 == 0) proxy.width else proxy.height
        val h = if (rot % 180 == 0) proxy.height else proxy.width
        runRecognition(img, m == "doc") { out ->
            proxy.close()
            busy.set(false)
            out.put("k", "live").put("w", w).put("h", h).put("ms", SystemClock.elapsedRealtime() - now)
            if (mode != "off" && !paused) emit(out)
        }
    }

    /** 글자(+바코드) 인식 → JSON {lines, codes} */
    private fun runRecognition(img: InputImage, withCodes: Boolean, done: (JSONObject) -> Unit) {
        val tText: Task<Text> = textRec.process(img)
        val tasks = mutableListOf<Task<*>>(tText)
        val tCode: Task<List<Barcode>>? = if (withCodes) barcodeRec.process(img) else null
        if (tCode != null) tasks.add(tCode)
        Tasks.whenAllComplete(tasks).addOnCompleteListener(camExec) {
            val out = JSONObject()
            val lines = JSONArray()
            if (tText.isSuccessful) {
                for (b in tText.result.textBlocks) for (ln in b.lines) {
                    val r = ln.boundingBox ?: Rect()
                    lines.put(JSONArray().put(ln.text).put(r.left).put(r.top).put(r.right).put(r.bottom))
                }
            } else out.put("textErr", tText.exception?.message ?: "?")
            out.put("lines", lines)
            val codes = JSONArray()
            if (tCode != null && tCode.isSuccessful) {
                for (bc in tCode.result) {
                    val r = bc.boundingBox ?: Rect()
                    codes.put(JSONArray().put(bc.rawValue ?: "").put(r.left).put(r.top).put(r.right).put(r.bottom))
                }
            }
            out.put("codes", codes)
            done(out)
        }
    }

    private fun takeShot(save: Boolean, tag: String) {
        val cap = capture
        if (cap == null) { emit(JSONObject().put("k", "err").put("msg", "이 기종은 고화질 촬영을 지원하지 않습니다.").put("tag", tag)); return }
        val t0 = SystemClock.elapsedRealtime()
        if (save) {
            val name = "검수_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date()) + (if (tag.isNotBlank()) "_$tag" else "")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name.replace(Regex("[^0-9A-Za-z가-힣_\\-]"), "_"))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/조립검수")
            }
            val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
            cap.takePicture(opts, camExec, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                    val uri: Uri? = res.savedUri
                    if (uri == null) { emit(JSONObject().put("k", "shot").put("tag", tag).put("saved", false)); return }
                    try {
                        val img = InputImage.fromFilePath(this@MainActivity, uri)
                        runRecognition(img, true) { out ->
                            out.put("k", "shot").put("tag", tag).put("saved", true).put("uri", uri.toString())
                                .put("w", img.width).put("h", img.height).put("ms", SystemClock.elapsedRealtime() - t0)
                            emit(out)
                        }
                    } catch (e: Exception) {
                        emit(JSONObject().put("k", "shot").put("tag", tag).put("saved", true).put("uri", uri.toString()).put("msg", e.message ?: ""))
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    emit(JSONObject().put("k", "err").put("tag", tag).put("msg", "사진 저장 실패: ${e.message}"))
                }
            })
        } else {
            cap.takePicture(camExec, object : ImageCapture.OnImageCapturedCallback() {
                @OptIn(ExperimentalGetImage::class)
                override fun onCaptureSuccess(image: ImageProxy) {
                    val media = image.image
                    if (media == null) { image.close(); emit(JSONObject().put("k", "err").put("tag", tag).put("msg", "촬영 실패")); return }
                    val rot = image.imageInfo.rotationDegrees
                    val img = InputImage.fromMediaImage(media, rot)
                    val w = if (rot % 180 == 0) image.width else image.height
                    val h = if (rot % 180 == 0) image.height else image.width
                    runRecognition(img, true) { out ->
                        image.close()
                        out.put("k", "shot").put("tag", tag).put("saved", false).put("w", w).put("h", h).put("ms", SystemClock.elapsedRealtime() - t0)
                        emit(out)
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    emit(JSONObject().put("k", "err").put("tag", tag).put("msg", "촬영 실패: ${e.message}"))
                }
            })
        }
    }

    // ───────────── JS 브리지 ─────────────
    inner class Bridge {
        @JavascriptInterface fun start(pct: Int, m: String) { runOnUiThread { startCamera(pct, if (m == "cmos") "cmos" else "doc") } }
        @JavascriptInterface fun stop() { runOnUiThread { stopCamera() } }
        @JavascriptInterface fun pause(on: Boolean) { paused = on }
        @JavascriptInterface fun setMode(m: String) { mode = if (m == "cmos") "cmos" else if (m == "off") "off" else "doc" }
        @JavascriptInterface fun shot(save: Boolean, tag: String) { runOnUiThread { takeShot(save, tag) } }
        @JavascriptInterface fun torch(on: Boolean) { runOnUiThread { camera?.cameraControl?.enableTorch(on) } }
        @JavascriptInterface fun beep(kind: String) {
            runOnUiThread {
                try {
                    val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { tone = it }
                    t.startTone(if (kind == "bad") ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK, 200)
                } catch (_: Exception) {}
            }
        }
        @JavascriptInterface fun version(): String = appVersion()
    }
}
