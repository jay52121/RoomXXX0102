package com.example.roomxxx_vocie

object CommandMapper {
    fun map(keywordRaw: String): Command? {
        return when {
            keywordRaw.contains("打开这个") -> Command.OPEN
            keywordRaw.contains("关闭这个") -> Command.CLOSE
            else -> null
        }
    }
}
