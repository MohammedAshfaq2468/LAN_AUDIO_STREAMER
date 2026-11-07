package com.example.wifiaudiospeaker

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

class CustomScannerActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
    }

    override fun initializeContent(): DecoratedBarcodeView {
        val barcodeView = super.initializeContent()

        // 🎯 Enable aggressive decoding for stylized/rounded modules
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )

        // 🎯 FORCE scanType = 2 (MIXED_SCAN):
        // Automatically inverts luminance on every frame so dark-mode / neon QR codes decode instantly!
        barcodeView.decoderFactory = DefaultDecoderFactory(
            listOf(BarcodeFormat.QR_CODE),
            hints,
            null,
            2 // 2 = MIXED_SCAN (Decodes both standard and inverted neon QR codes)
        )

        // Enable continuous camera auto-focus for computer screens
        barcodeView.cameraSettings.isContinuousFocusEnabled = true

        return barcodeView
    }
}