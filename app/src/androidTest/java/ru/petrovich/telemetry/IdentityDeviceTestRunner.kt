package ru.petrovich.telemetry

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Проверяет только отдельный тестовый пакет; фоновые задачи обычного приложения не запускаются. */
class IdentityDeviceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == "ru.petrovich.telemetry.p0test") {
            "Инструментальные проверки P0 разрешены только для отдельного пакета .p0test"
        }
        return super.newApplication(cl, Application::class.java.name, context)
    }
}
