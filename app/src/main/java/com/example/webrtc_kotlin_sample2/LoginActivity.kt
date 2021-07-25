/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package com.example.webrtc_kotlin_sample2

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_login.*

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        btn_login.setOnClickListener {
            /* 로그인 조건 */

            /* 로그인 이후 화면전환 */
            val HomeIntent = Intent(this, HomeActivity::class.java)
            startActivity(HomeIntent)
        }
        btn_signup.setOnClickListener {
            /* 회원가입 화면 전환 */
            val SignupIntent = Intent(this, SignupActivity::class.java)
            startActivity(SignupIntent)
        }
    }
}
