package com.myapp

import android.app.Activity

class MyActivity: Activity() {
    inner class Helper {
        fun a(): String = "q"

        inner class HelperOfHelper {
            fun b(): String = getString(R.string.some_string)
        }
    }
}