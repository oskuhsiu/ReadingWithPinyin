package me.osku.readingwithpinyin

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import me.osku.readingwithpinyin.ui.theme.ReadingWithPinyinTheme
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import android.content.res.Resources
import android.util.Size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.camera.core.ExperimentalGetImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 權限檢查與請求
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        setContent {
            ReadingWithPinyinTheme {
                FullscreenCameraAR()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // 權限通過後重新啟動內容
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
            setContent {
                ReadingWithPinyinTheme {
                    FullscreenCameraAR()
                }
            }
        }
    }


    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    @OptIn(ExperimentalGetImage::class)
    @Composable
    fun FullscreenCameraAR() {
        val context = LocalContext.current
        val ocrResults = remember { mutableStateOf<List<Pair<String, Rect>>>(emptyList()) }
        val imageSize = remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val rotation = remember { mutableStateOf(0) }
        val previewSize = remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val scope = rememberCoroutineScope()

        // 載入注音表
        LaunchedEffect(Unit) {
            scope.launch(Dispatchers.IO) {
                ZhuyinDict.load(context.resources)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // CameraX 預覽
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        // 新增 ImageAnalysis 用於 OCR
                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setTargetResolution(Size(previewView.width, previewView.height))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                    // 記錄原始影像尺寸與旋轉
                                    imageSize.value = Pair(imageProxy.width, imageProxy.height)
                                    //無視旋轉角度
                                    rotation.value = 0//imageProxy.imageInfo.rotationDegrees

                                    // 記錄 PreviewView 的實際尺寸
                                    previewSize.value = Pair(previewView.width, previewView.height)

                                    @OptIn(ExperimentalGetImage::class)
                                    processImageProxyForOCR(imageProxy, ocrResults)
                                }
                            }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            ctx as LifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalyzer
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // 疊加 OCR 結果
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val imgSize = imageSize.value
                val rot = rotation.value

                if (imgSize != null && imgSize.first > 0 && imgSize.second > 0) {
                    val (imageWidth, imageHeight) = imgSize

                    // 根據旋轉角度調整圖像尺寸
                    val (adjustedImageWidth, adjustedImageHeight) = when (rot) {
                        90, 270 -> Pair(imageHeight, imageWidth)
                        else -> Pair(imageHeight, imageWidth)
                    }

                    // 計算正確的縮放比例
                    val scaleX = canvasWidth / adjustedImageWidth.toFloat()
                    val scaleY = canvasHeight / adjustedImageHeight.toFloat()
                    val scale = minOf(scaleX, scaleY)

                    // 計算實際顯示區域的偏移
                    val scaledWidth = adjustedImageWidth * scale
                    val scaledHeight = adjustedImageHeight * scale
                    val offsetX = (canvasWidth - scaledWidth) / 2f
                    val offsetY = (canvasHeight - scaledHeight) / 2f

                    ocrResults.value.forEach { (text, rect) ->
                        // 轉換座標，考慮旋轉
                        val transformedRect = transformCoordinates(
                            rect,
                            imageWidth,
                            imageHeight,
                            rot,
                            scale,
                            offsetX,
                            offsetY
                        )

                        if(transformedRect.width() < 40 || transformedRect.height() < 40)
                            return@forEach // 忽略過小的框選區域

                        // 畫文字框
                        drawRect(
                            color = Color.Red,
                            topLeft = androidx.compose.ui.geometry.Offset(
                                transformedRect.left,
                                transformedRect.top
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                transformedRect.width(),
                                transformedRect.height()
                            ),
                            style = Stroke(width = 3f)
                        )

                        // 顯示注音
                        if (text.isNotEmpty()) {
                            // 生成注音
                            val zhuyin = generateZhuyin(text)

                            if (zhuyin.isNotEmpty()) {
                                // 根據框選區域大小動態調整字體大小
                                val rectWidth = transformedRect.width()
                                val rectHeight = transformedRect.height()

                                val fontAdjust = minOf(rectWidth, rectHeight)

                                // 計算合適的字體大小，確保所有注音字符都能在框內垂直顯示
                                val maxFontSize = fontAdjust / 3 * .8f
                                val fontSize = maxOf(maxFontSize, 20f) // 最小字體大小為20

                                // 計算垂直間距，確保字符不重疊
                                val lineHeight = fontSize * 1.4f // 行高比字體大小稍大，避免重疊
                                val totalHeight = zhuyin.size * lineHeight

                                // 計算起始Y位置，讓注音在框內垂直居中
                                val startY = transformedRect.top /*+ (rectHeight - totalHeight) / 2f*/ + lineHeight * 0.8f

                                // 水平位置置中
                                val centerX = transformedRect.centerX()

                                // 垂直繪製每個注音字符
                                val firstZhuyin = zhuyin.first()
                                firstZhuyin.forEachIndexed { index, zhuyinChar ->
                                    val yPosition = startY + index * lineHeight

                                    // 確保字符在框內
//                                    if (yPosition > transformedRect.top && yPosition < transformedRect.bottom) {
                                        drawIntoCanvas { canvas ->
                                            val paint = android.graphics.Paint().apply {
                                                color = Color.Yellow.toArgb()
                                                textSize = fontSize
                                                isFakeBoldText = true
                                                textAlign = android.graphics.Paint.Align.CENTER
                                                setShadowLayer(3f, 1f, 1f, Color.Black.toArgb())
                                                isAntiAlias = true
                                            }

                                            canvas.nativeCanvas.drawText(
                                                zhuyinChar.toString(),
                                                centerX,
                                                yPosition,
                                                paint
                                            )
                                        }
//                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 顯示 OCR 結果
    }

    // 注音查詢模組（動態載入 word4k.tsv）
    object ZhuyinDict {
        val dict: MutableMap<String, MutableList<String>> = mutableMapOf()
        private var loaded = false

        fun load(resources: Resources) {
            if (loaded) return
            val inputStream = resources.openRawResource(R.raw.word4k)
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            reader.forEachLine { line ->
                val parts = line.trim().split('\t')
                if (parts.size >= 2) {
                    val word = parts[0]
                    val zhuyin = parts[1]
                    dict.getOrPut(word) { mutableListOf() }.add(zhuyin)
                }
            }
            loaded = true
        }

        fun getZhuyin(char: Char): String {
            val list = dict[char.toString()]
            return list?.firstOrNull() ?: char.toString()
        }
    }

    // 分詞模組（暫以單字切分）
    fun segment(text: String): List<String> {
        return text.toCharArray().map { it.toString() }
    }

    // 注音生成：對每個分詞查詢注音
    fun generateZhuyin(text: String): List<String> {
        return segment(text).map { ZhuyinDict.getZhuyin(it[0]) }
    }

    @ExperimentalGetImage
    fun processImageProxyForOCR(
        imageProxy: ImageProxy,
        ocrResults: androidx.compose.runtime.MutableState<List<Pair<String, Rect>>>
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val recognizer =
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val results = mutableListOf<Pair<String, Rect>>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            // 只保留 word4k.tsv（ZhuyinDict.dict）中有的中文字
                            val filteredChars = line.text.filter { ZhuyinDict.dict.containsKey(it.toString()) }
                            // 計算每個字在行內的位置，為每個字創建獨立的 Rect
                            filteredChars.forEachIndexed { idx, c ->
                                val charRect = line.boundingBox ?: Rect()
                                val charWidth = charRect.width() / filteredChars.length.toFloat()
                                val charLeft = charRect.left + (idx * charWidth).toInt()
                                val charRight = charRect.left + ((idx + 1) * charWidth).toInt()

                                val individualCharRect = Rect(
                                    charLeft,
                                    charRect.top,
                                    charRight,
                                    charRect.bottom
                                )

                                results.add(Pair(c.toString(), individualCharRect))
                            }
                        }
                    }
                    ocrResults.value = results
                }
                .addOnFailureListener {
                    ocrResults.value = emptyList()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    // 座標轉換函數
    fun transformCoordinates(
        rect: Rect,
        imageWidth: Int,
        imageHeight: Int,
        rotation: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): RectF {
        // 先處理旋轉
        val (transformedLeft, transformedTop, transformedRight, transformedBottom) = when (rotation) {
            90 -> {
                // 順時針90度：x' = y, y' = imageWidth - x
                val left = rect.top.toFloat()
                val top = imageWidth - rect.right.toFloat()
                val right = rect.bottom.toFloat()
                val bottom = imageWidth - rect.left.toFloat()
                listOf(left, top, right, bottom)
            }

            180 -> {
                // 180度：x' = imageWidth - x, y' = imageHeight - y
                val left = imageWidth - rect.right.toFloat()
                val top = imageHeight - rect.bottom.toFloat()
                val right = imageWidth - rect.left.toFloat()
                val bottom = imageHeight - rect.top.toFloat()
                listOf(left, top, right, bottom)
            }

            270 -> {
                // 逆時針90度：x' = imageHeight - y, y' = x
                val left = imageHeight - rect.bottom.toFloat()
                val top = rect.left.toFloat()
                val right = imageHeight - rect.top.toFloat()
                val bottom = rect.right.toFloat()
                listOf(left, top, right, bottom)
            }

            else -> {
                // 0度，不旋轉
                listOf(
                    rect.left.toFloat(),
                    rect.top.toFloat(),
                    rect.right.toFloat(),
                    rect.bottom.toFloat()
                )
            }
        }

        // 應用縮放和偏移
        return RectF(
            transformedLeft * scale + offsetX,
            transformedTop * scale + offsetY,
            transformedRight * scale + offsetX,
            transformedBottom * scale + offsetY
        )
    }
}
