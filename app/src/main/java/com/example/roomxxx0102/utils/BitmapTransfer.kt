package com.example.roomxxx0102.utils

import android.graphics.Bitmap

/**
 * **Bitmap 传输工具 (Bitmap Transfer)**
 *
 * 用于在 Activity 之间传递大图，避免 Intent TransactionTooLargeException。
 * 这是一个简单的单例持有者。
 */
object BitmapTransfer {
    // 临时存储截图，使用完建议设为 null 以释放内存
    var capturedFrame: Bitmap? = null
}
