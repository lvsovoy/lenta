package me.lesovoy.lenta.ui.scanner

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.camera.CameraSettings

class QrScannerActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val barcodeScannerView = findViewById<DecoratedBarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_scanner)
        barcodeScannerView?.cameraSettings = CameraSettings().apply {
            isAutoFocusEnabled = true
            isContinuousFocusEnabled = true
            isMeteringEnabled = true
            isBarcodeSceneModeEnabled = true
        }
    }

    companion object {
        fun createScanOptions(prompt: String): ScanOptions {
            return ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(prompt)
                setCameraId(0)
                setBeepEnabled(false)
                setBarcodeImageEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(QrScannerActivity::class.java)
            }
        }
    }
}
