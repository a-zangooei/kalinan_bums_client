package com.example.data.repository

import com.example.data.model.LoginPageState
import okhttp3.FormBody

/**
 * Constructs ASP.NET WebForms POST payloads for user login.
 * Logout payloads are built dynamically from live Master page DOM via [LogoutFormParser].
 */
internal object AuthFormBuilder {

    fun buildLoginFormBody(
        encryptedUsername: String,
        encryptedPassword: String,
        captchaInput: String,
        pageState: LoginPageState
    ): FormBody = FormBody.Builder()
        .add("__EVENTTARGET", "")
        .add("__EVENTARGUMENT", "")
        .add("__VIEWSTATE", pageState.viewState)
        .add("__VIEWSTATEGENERATOR", pageState.viewStateGenerator)
        .add("__VIEWSTATEENCRYPTED", "")
        .add("txtUsernamePlain", "")
        .add("txtPasswordPlain", "")
        .add("txtUsername", encryptedUsername)
        .add("txtPassword", encryptedPassword)
        .add("txtCaptcha", captchaInput.trim())
        .add("btnLogin", "ورود سمت سرور ")
        .add("CanSaveloginPasstxt", "True")
        .add("loginSecurtxt", "True")
        .add("publicKeytxt", pageState.publicKeyPem)
        .build()
}
