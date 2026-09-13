package com.example.foldreveal

/**
 * 힌지 각도 원시값을 물리 스프링으로 뒤쫓게 만드는 스무더.
 *
 * 센서 값을 그대로 화면에 반영하면 손떨림과 센서 노이즈가 그대로 드러나서
 * 애니메이션이 싸구려처럼 보입니다. 여기서는 스프링-댐퍼 모델을 써서
 * 목표 각도를 "묵직하게" 뒤쫓게 만듭니다.
 *
 * 동작:
 *  - [target] 을 센서 값으로 갱신하고
 *  - 매 프레임 [update] 를 호출하면 [value] 가 스프링 운동으로 목표를 따라갑니다.
 *
 * [stiffness] 를 올리면 더 빠르고 날카롭게, [damping] 을 올리면 출렁임 없이 차분하게 붙습니다.
 * damping ≈ 2 * sqrt(stiffness) 가 임계 감쇠(오버슈트 없이 가장 빠르게 안착)입니다.
 * 기본값은 임계 감쇠보다 아주 살짝 낮게 잡아, 끝에서 거의 느껴지지 않을 정도의
 * 미세한 "착 붙는" 여운을 남깁니다.
 */
class SpringAngle(
    private val stiffness: Float = 420f,
    private val damping: Float = 38f,
) {
    var value: Float = 0f
        private set

    var target: Float = 0f

    private var velocity: Float = 0f
    private var lastTimeNanos: Long = 0L

    /** 애니메이션 시작 시점에 스프링을 현재 각도로 즉시 맞춥니다. */
    fun snapTo(angle: Float) {
        value = angle
        target = angle
        velocity = 0f
        lastTimeNanos = 0L
    }

    /**
     * 프레임 시각(ns)을 받아 스프링을 한 스텝 전진시킵니다.
     * 반환값은 갱신된 [value].
     */
    fun update(frameTimeNanos: Long): Float {
        if (lastTimeNanos == 0L) {
            lastTimeNanos = frameTimeNanos
            return value
        }

        var dt = (frameTimeNanos - lastTimeNanos) / 1_000_000_000f
        lastTimeNanos = frameTimeNanos

        // 프레임 드랍 시 스프링이 폭발하지 않도록 dt 를 제한하고 서브스텝으로 쪼갭니다.
        dt = dt.coerceIn(0f, 0.064f)
        val steps = if (dt > 0.020f) 4 else 2
        val h = dt / steps

        repeat(steps) {
            val displacement = value - target
            val accel = -stiffness * displacement - damping * velocity
            velocity += accel * h
            value += velocity * h
        }

        // 목표에 충분히 가까우면 미세 떨림을 없애기 위해 스냅
        if (kotlin.math.abs(value - target) < 0.05f && kotlin.math.abs(velocity) < 0.5f) {
            value = target
            velocity = 0f
        }

        return value
    }

    /** 현재 각속도 (deg/s). 모션 블러 세기를 계산하는 데 씁니다. */
    val angularVelocity: Float get() = velocity
}
