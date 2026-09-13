package com.example.foldreveal

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * TYPE_HINGE_ANGLE 센서를 감싸는 헬퍼.
 *
 * - 값 범위: 0f(완전히 접힘) ~ 180f(완전히 펼쳐짐)
 * - Android 11(API 30) 이상, 힌지 센서를 탑재한 폴더블 기기(갤럭시 Z 폴드/플립 시리즈 포함)에서 동작합니다.
 * - 센서가 없는 기기에서는 [isAvailable] 이 false 이며, 호출부에서 대체 로직(예: 고정 각도)을 써야 합니다.
 */
class HingeAngleSensor(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val hingeSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    val isAvailable: Boolean get() = hingeSensor != null

    /**
     * [onAngleChanged] 는 센서 스레드가 아닌, 이 함수를 호출한 스레드의 Looper 로 전달됩니다.
     * (아래 MainActivity 에서는 메인 스레드에서 등록하므로 UI 갱신에 바로 써도 안전합니다.)
     */
    fun start(onAngleChanged: (Float) -> Unit) {
        val sensor = hingeSensor ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // event.values[0] 에 0~180 사이의 각도(degree)가 들어옵니다.
                onAngleChanged(event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        currentListener = listener
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        currentListener?.let { sensorManager.unregisterListener(it) }
        currentListener = null
    }

    private var currentListener: SensorEventListener? = null
}
