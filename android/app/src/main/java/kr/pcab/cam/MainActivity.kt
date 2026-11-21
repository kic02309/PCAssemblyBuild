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
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Intent
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.ImageDecoder
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
 *  frame(state)      맞춤 틀 색: 0 대기(흰색) 1 읽는 중(노랑) 2 일치(초록) 3 불일치(빨강)
 *  version()         앱 버전
 * 결과는 window.__cam(json) 으로 전달:
 *  {k:"live"|"shot"|"err"|"state", w, h, ms, lines:[[text,l,t,r,b]...], codes:[[value,l,t,r,b]...], uri, tag, msg}
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val START_URL = "https://kic02309.github.io/PCAssemblyBuild/?app=cam"
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var camBox: FrameLayout
    private lateinit var overlay: FrameOverlay
    private lateinit var web: WebView

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var analysisUse: ImageAnalysis? = null
    @Volatile private var rot = Surface.ROTATION_0
    // 화면은 세로 고정 · 휴대폰을 옆으로 눕혀 찍어도(CMOS 가로 모니터) 글자가 바로 서도록 인식 방향을 맞춤
    private val orient by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(d: Int) {
                if (d == ORIENTATION_UNKNOWN) return
                val r = when (d) { in 45 until 135 -> Surface.ROTATION_270; in 135 until 225 -> Surface.ROTATION_180; in 225 until 315 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                if (r != rot) { rot = r; analysisUse?.targetRotation = r; capture?.targetRotation = r; runOnUiThread { overlay.setRot(r) } }
            }
        }
    }
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

        // 카메라(전체 화면) 위에 웹 화면을 투명하게 겹침 → 안내·버튼은 웹이 카메라 위에 그림
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0F1E3D")) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = View.GONE
        }
        web = WebView(this)
        overlay = FrameOverlay(this)
        camBox = FrameLayout(this).apply { visibility = View.GONE }
        camBox.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        camBox.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        previewView.visibility = View.VISIBLE
        root.addView(camBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        web.setBackgroundColor(Color.TRANSPARENT)
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
        // 켤 때마다 최신 화면 (웹 수정이 바로 반영되게)
        if (savedInstanceState == null) web.loadUrl(START_URL + "&t=" + System.currentTimeMillis()) else web.restoreState(savedInstanceState)

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

    override fun onResume() { super.onResume(); if (orient.canDetectOrientation()) orient.enable() }
    override fun onPause() { orient.disable(); super.onPause() }

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
        camBox.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 검수 중 화면 꺼짐 방지
        overlay.setRot(rot); overlay.setKind("", 0f, 1f)
        root.requestLayout()

        if (provider != null && camera != null) { emit(JSONObject().put("k", "state").put("msg", "on")); return }
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val p = fut.get()
                provider = p
                val preview = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(camExec) { proxy -> analyze(proxy) }
                analysis.targetRotation = rot; analysisUse = analysis
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                    .build()
                cap.targetRotation = rot
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mode = "off"
        try { provider?.unbindAll() } catch (_: Exception) {}
        camera = null
        capture = null
        camBox.visibility = View.GONE
        overlay.setKind("", 0f, 1f)
        emit(JSONObject().put("k", "state").put("msg", "off"))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val m = mode
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        if (m == "off" || m == "free" || paused || media == null || now - lastRun < 250 || !busy.compareAndSet(false, true)) {
            proxy.close(); return
        }
        lastRun = now
        val pm = try { inFrameMap(rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)) } catch (e: Exception) { proxy.close(); busy.set(false); return }
        proxy.close()
        val bmp = pm.first; val toView = pm.second
        val img = InputImage.fromBitmap(bmp, 0)
        val w = bmp.width; val h = bmp.height
        runRecognition(img, m == "doc") { out ->
            busy.set(false)
            if (toView != null) {   // AR : 글자 칸마다 화면 위치(0~1) [x0,y0,x1,y1]
                fun conv(a: JSONArray?): JSONArray { val o = JSONArray(); if (a == null) return o
                    for (i in 0 until a.length()) { val e = a.getJSONArray(i); val r = RectF(e.getDouble(1).toFloat(), e.getDouble(2).toFloat(), e.getDouble(3).toFloat(), e.getDouble(4).toFloat()); toView.mapRect(r)
                        o.put(JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble())) }
                    return o }
                out.put("sl", conv(out.optJSONArray("lines"))).put("sc", conv(out.optJSONArray("codes")))
            }
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

    private fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }
    /**
     * 틀 안만 잘라냄. b = 중력 기준으로 바로 선 사진(휴대폰을 눕혔으면 그만큼 돌아간 상태).
     * 화면(세로 고정) 방향으로 되돌려 틀 위치로 자른 뒤, 다시 글자가 바로 서게 돌림. 틀보다 사방 6% 넉넉하게.
     */
    private fun inFrame(b: Bitmap): Bitmap = inFrameMap(b).first

    /**
     * 틀 안만 잘라냄 + 잘라낸 사진의 점 → 화면 위치(0~1) 변환표 (AR 표시용).
     * b = 중력 기준으로 바로 선 사진. 틀이 없으면 자르지 않고 변환표만 만듦.
     */
    private fun inFrameMap(b: Bitmap): Pair<Bitmap, Matrix?> {
        val vw = overlay.vw.takeIf { it > 0f } ?: previewView.width.toFloat(); val vh = overlay.vh.takeIf { it > 0f } ?: previewView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return Pair(b, null)
        val extra = (360 - rot * 90) % 360
        val fr = overlay.fr
        val dw = if (extra % 180 == 0) b.width.toFloat() else b.height.toFloat(); val dh = if (extra % 180 == 0) b.height.toFloat() else b.width.toFloat()
        val s = maxOf(vw / dw, vh / dh); val dx = (vw - dw * s) / 2; val dy = (vh - dh * s) / 2   // 미리보기 FILL_CENTER
        var l = 0f; var t = 0f; var w = dw; var h = dh
        val out: Bitmap
        if (fr != null) {
            val d = rotate(b, (360 - extra) % 360)                   // 화면 방향
            val mx = fr.width() * vw * 0.06f; val my = fr.height() * vh * 0.06f
            l = ((fr.left * vw - mx - dx) / s).coerceIn(0f, dw - 2); t = ((fr.top * vh - my - dy) / s).coerceIn(0f, dh - 2)
            val r = ((fr.right * vw + mx - dx) / s).coerceIn(l + 1, dw); val btm = ((fr.bottom * vh + my - dy) / s).coerceIn(t + 1, dh)
            l = l.toInt().toFloat(); t = t.toInt().toFloat(); w = (r - l).toInt().coerceAtLeast(1).toFloat(); h = (btm - t).toInt().coerceAtLeast(1).toFloat()
            out = Bitmap.createBitmap(d, l.toInt(), t.toInt(), w.toInt(), h.toInt(), Matrix().apply { postRotate(extra.toFloat()) }, true)
        } else out = b
        // 앞으로 : 잘린 조각(0..w,0..h)을 extra 만큼 돌린 뒤 0,0 으로 당김 → 그 반대로 되돌리면 조각 좌표 → +l,t → 화면
        val fwd = Matrix().apply { postRotate(extra.toFloat()) }
        val rr = RectF(0f, 0f, w, h); fwd.mapRect(rr); fwd.postTranslate(-rr.left, -rr.top)
        val inv = Matrix(); if (!fwd.invert(inv)) return Pair(out, null)
        inv.postTranslate(l, t); inv.postScale(s, s); inv.postTranslate(dx, dy); inv.postScale(1f / vw, 1f / vh)
        return Pair(out, inv)
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
                        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                            val big = maxOf(info.size.width, info.size.height); var s = 1; while (big / (s * 2) >= 2600) s *= 2
                            dec.setTargetSampleSize(s); dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        val img = InputImage.fromBitmap(inFrame(src), 0)
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
                    val bmp = try { inFrame(rotate(image.toBitmap(), image.imageInfo.rotationDegrees)) } catch (e: Exception) { image.close(); emit(JSONObject().put("k", "err").put("tag", tag).put("msg", "촬영 실패")); return }
                    image.close()
                    val img = InputImage.fromBitmap(bmp, 0); val w = bmp.width; val h = bmp.height
                    runRecognition(img, true) { out ->
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
        @JavascriptInterface fun start(pct: Int, m: String) { runOnUiThread { startCamera(pct, if (m == "cmos" || m == "free") m else "doc") } }
        @JavascriptInterface fun frame(state: Int) { runOnUiThread { overlay.setState(state) } }
        /** 맞춤 틀: kind = "" (없음 · 화면 전체 인식) / "doc" 세로 / "cmos" 가로 · top~bottom = 틀이 들어갈 세로 범위(0~1) · 틀이 있으면 틀 안만 인식 */
        @JavascriptInterface fun setFrame(kind: String, top: Double, bottom: Double) { runOnUiThread { overlay.setKind(if (kind == "doc" || kind == "cmos") kind else "", top.toFloat(), bottom.toFloat()) } }
        /** 화면 비율 위치(0~1)에 초점 */
        @JavascriptInterface fun focus(x: Double, y: Double) { runOnUiThread {
            val cam = camera ?: return@runOnUiThread
            val pt = previewView.meteringPointFactory.createPoint((x * previewView.width).toFloat(), (y * previewView.height).toFloat())
            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
        } }
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
        @JavascriptInterface fun versionCode(): Int = try { @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode } catch (_: Exception) { 0 }
        /** 확대 0~1 (0 = 기본) */
        @JavascriptInterface fun zoom(r: Double) { runOnUiThread { try { camera?.cameraControl?.setLinearZoom(r.toFloat().coerceIn(0f, 1f)) } catch (_: Exception) {} } }
        /** 밝기 보정 (EV, 예: -0.7 = 조금 어둡게 → 모니터 글자 번짐 줄임) */
        @JavascriptInterface fun exposure(ev: Double) { runOnUiThread { try {
            val c = camera ?: return@runOnUiThread; val st = c.cameraInfo.exposureState
            if (!st.isExposureCompensationSupported) return@runOnUiThread
            val step = st.exposureCompensationStep.toFloat(); val r = st.exposureCompensationRange
            val idx = if (step > 0f) Math.round(ev.toFloat() / step).coerceIn(r.lower, r.upper) else 0
            c.cameraControl.setExposureCompensationIndex(idx)
        } catch (_: Exception) {} } }
        /** 진동 (ms) */
        @JavascriptInterface fun vibrate(ms: Int) { try {
            @Suppress("DEPRECATION") val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceIn(10, 600), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {} }
        /** 외부 브라우저로 열기 (앱 업데이트 받기) */
        @JavascriptInterface fun openUrl(url: String) { runOnUiThread { try { if (url.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} } }
    }
}

/** 카메라 화면 위 맞춤 틀 — 전표는 세로 틀, CMOS는 모니터 모양 가로 틀 (휴대폰을 눕히면 같이 돌아감) */
class FrameOverlay(ctx: Context) : View(ctx) {
    private var mode = "off"
    private var state = 0
    private var rot = 0
    private val dim = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    init { isClickable = false; isFocusable = false }
    @Volatile var fr: RectF? = null      // 틀 위치 (화면 대비 0~1) · 인식할 때 이 안만 자름
    @Volatile var vw = 0f
    @Volatile var vh = 0f
    private var band0 = 0f
    private var band1 = 1f
    fun setKind(k: String, top: Float, bottom: Float) { mode = k; state = 0; band0 = top.coerceIn(0f, 0.9f); band1 = bottom.coerceIn(band0 + 0.1f, 1f); if (k != "doc" && k != "cmos") fr = null; invalidate() }
    fun setState(s: Int) { state = s; invalidate() }
    fun setRot(r: Int) { rot = r; invalidate() }

    override fun onDraw(c: Canvas) {
        if (mode != "doc" && mode != "cmos") { fr = null; return }
        val W = width.toFloat(); val H = height.toFloat(); if (W <= 0 || H <= 0) return
        val side = rot == 1 || rot == 3   // 휴대폰을 옆으로 눕힘
        // 틀 비율(가로/세로, 화면 기준): 전표 = A4 세로, CMOS = 16:9 모니터
        var ar = if (mode == "doc") 1f / 1.414f else 16f / 9f
        if (side) ar = 1f / ar
        val bandH = H * (band1 - band0)
        var fw = W * 0.88f; var fh = fw / ar
        if (fh > bandH * 0.94f) { fh = bandH * 0.94f; fw = fh * ar }
        val l = (W - fw) / 2; val t = H * band0 + (bandH - fh) / 2
        val r = RectF(l, t, l + fw, t + fh)
        vw = W; vh = H; fr = RectF(r.left / W, r.top / H, r.right / W, r.bottom / H)
        val hole = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(0f, 0f, W, H, Path.Direction.CW); addRoundRect(r, 18f, 18f, Path.Direction.CW) }
        c.drawPath(hole, dim)
        val col = when (state) { 1 -> Color.rgb(250, 204, 21); 2 -> Color.rgb(34, 197, 94); 3 -> Color.rgb(239, 68, 68); else -> Color.WHITE }
        line.color = col; line.strokeWidth = W * 0.006f
        c.drawRoundRect(r, 18f, 18f, line)
        // 모서리 꺾쇠 (굵게)
        line.strokeWidth = W * 0.014f
        val k = minOf(fw, fh) * 0.12f
        listOf(Pair(r.left, r.top), Pair(r.right, r.top), Pair(r.left, r.bottom), Pair(r.right, r.bottom)).forEach { (x, y) ->
            val dx = if (x == r.left) k else -k; val dy = if (y == r.top) k else -k
            c.drawLine(x, y, x + dx, y, line); c.drawLine(x, y, x, y + dy, line)
        }
    }
}
