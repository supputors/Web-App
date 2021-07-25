/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package com.example.webrtc_kotlin_sample2

class Constants {
    /* companion object : Java static, 클래스 변수 느낌
    * 다시 말해 Constants 클래스 이용해 변수에 접근 */
    companion object {
        /* app onCreate시 또는 SignallingClient가 "END_CALL"을 수신받았을 경우 true, */
        var isCallEnded: Boolean = false
        /* app onCreate시 true, 수신을 제안받거나 답변받을 경우 : false */
        var isIntiatedNow : Boolean = true
    }
}