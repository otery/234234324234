package com.example.foldreveal

/**
 * AGSL 셰이더 — v5. 물리적으로 정확한 원근 투영 버전.
 *
 * v4까지는 "힌지에서 먼 지점을 비례적으로 압축"하는 근사치를 썼습니다. 보기엔 그럴듯하지만
 * 각도가 클 때(많이 접혔을 때) 실제 원근과 눈에 띄게 어긋납니다.
 *
 * v5는 진짜로 3D를 풉니다:
 *   1. 화면 픽셀에서 카메라 방향으로 광선(ray)을 쏘고
 *   2. 힌지를 축으로 실제 각도만큼 회전시킨 두 반평면(half-plane)과 교점을 구하고
 *   3. 그 교점의 평면 내 좌표로 텍스처를 읽습니다.
 * 결과적으로 원근 단축, 기울기, 가장자리 수렴이 모두 물리적으로 맞아떨어집니다.
 *
 * 추가로 표현하는 것:
 *   - 속도 기반 모션 블러 (적응형 탭 수)
 *   - 램버트 확산 조명 + 블린-퐁 스펙큘러 (실제 법선 벡터 기반)
 *   - 힌지 주름 + 앰비언트 오클루전
 *   - 디스플레이 둥근 모서리 SDF 마스킹
 *   - 감마 보정 (linear 공간 셰이딩)
 *
 * uniform:
 *   uContent      : 화면 캡처 셰이더
 *   uResolution   : 화면 크기 (px)
 *   uProgress     : 0.0(접힘) ~ 1.0(펼쳐짐)
 *   uVelocity     : 정규화 각속도 0~1 (모션 블러 세기)
 *   uCornerRadius : 디스플레이 모서리 반경 (px)
 *   uHingePos     : 접힘선 정규화 위치 (0~1). 보통 0.5
 *   uIsVertical   : 1.0 = 세로 힌지(폴드), 0.0 = 가로 힌지(플립)
 *   uBlurTaps     : 모션 블러 샘플 수 (적응형, 1~8)
 *   uCameraDist   : 가상 카메라 거리. 클수록 원근이 약해짐(망원), 작을수록 강해짐(광각)
 */
object FoldShader {

    const val SRC: String = """
uniform shader uContent;
uniform float2 uResolution;
uniform float uProgress;
uniform float uVelocity;
uniform float uCornerRadius;
uniform float uHingePos;
uniform float uIsVertical;
uniform float uBlurTaps;
uniform float uCameraDist;

float smoothstepf(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float3 toLinear(float3 c) { return pow(max(c, float3(0.0)), float3(2.2)); }
float3 toSrgb(float3 c)   { return pow(max(c, float3(0.0)), float3(1.0 / 2.2)); }

// 둥근 사각형 SDF
float roundedBoxSdf(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + float2(radius);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

/*
 * 핵심: 화면 좌표 -> 회전한 반평면과의 교점 -> 텍스처 좌표
 *
 * 좌표계 (세로 힌지 기준):
 *   - 힌지를 원점의 세로축(y축)에 둡니다.
 *   - a  : 힌지에서 가로 방향으로 떨어진 거리 (평면 내 좌표, 0 ~ 1)
 *   - b  : 세로 방향 좌표 (-1 ~ 1)
 *   - 반평면은 힌지 축을 중심으로 halfAngle 만큼 회전합니다.
 *     완전히 펼쳐지면 halfAngle = 0 (평평), 완전히 접히면 halfAngle = 90도.
 *
 * 회전 후 3D 위치:
 *   x = side * a * cos(halfAngle)
 *   y = b
 *   z = -a * sin(halfAngle)          (뒤로 물러남 = 멀어짐)
 *
 * 원근 투영:
 *   screenX = x / (1 - z / camDist)
 *   screenY = y / (1 - z / camDist)
 *
 * 우리는 반대로 screenX, screenY 에서 a, b 를 구해야 합니다.
 *   screenX = side * a*cos / (1 + a*sin/camDist)
 *   => a = screenX / (side*cos - screenX*sin/camDist)
 * 그 다음 b = screenY * (1 + a*sin/camDist)
 *
 * 반환: float3(a, b, valid). valid < 0.5 이면 이 픽셀은 면 밖입니다.
 */
float3 unprojectToPlane(float screenU, float screenV, float side, float halfAngle, float camDist) {
    float c = cos(halfAngle);
    float s = sin(halfAngle);

    float denom = side * c - screenU * s / camDist;

    // 분모가 0에 가까우면(면이 시선과 평행) 교점이 없습니다.
    if (abs(denom) < 0.0001) {
        return float3(0.0, 0.0, 0.0);
    }

    float a = screenU / denom;

    // 평면의 자기 쪽 영역이 아니면 버립니다.
    if (a < 0.0 || a > 1.0) {
        return float3(0.0, 0.0, 0.0);
    }

    float perspective = 1.0 + a * s / camDist;
    float b = screenV * perspective;

    if (b < -1.0 || b > 1.0) {
        return float3(0.0, 0.0, 0.0);
    }

    return float3(a, b, 1.0);
}

// 한 번 샘플링: 화면 uv -> 색상. 실패 시 a = 0.
half4 sampleFold(float2 uv, float halfAngle, float camDist, float hingePos, float isVertical) {
    // 힌지 기준 좌표로 변환
    float alongHinge;   // 힌지에 수직인 축 (여기서 접힘이 일어남)
    float acrossHinge;  // 힌지에 나란한 축

    if (isVertical > 0.5) {
        alongHinge = uv.x;
        acrossHinge = uv.y;
    } else {
        alongHinge = uv.y;
        acrossHinge = uv.x;
    }

    // 힌지를 원점으로, 양쪽을 -1 ~ +1 로 정규화
    float side = alongHinge < hingePos ? -1.0 : 1.0;
    float halfExtent = side < 0.0 ? hingePos : (1.0 - hingePos);
    if (halfExtent < 0.0001) return half4(0.0);

    float screenU = (alongHinge - hingePos) / halfExtent; // -1 ~ +1
    float screenV = (acrossHinge - 0.5) * 2.0;            // -1 ~ +1

    float3 hit = unprojectToPlane(abs(screenU) * side, screenV, side, halfAngle, camDist);
    if (hit.z < 0.5) return half4(0.0);

    float a = hit.x; // 0 (힌지) ~ 1 (바깥 끝)
    float b = hit.y; // -1 ~ 1

    // 평면 좌표 -> 원본 텍스처 좌표로 역변환
    float texAlong = hingePos + side * a * halfExtent;
    float texAcross = 0.5 + b * 0.5;

    float2 texUv = isVertical > 0.5 ? float2(texAlong, texAcross) : float2(texAcross, texAlong);

    if (texUv.x < 0.0 || texUv.x > 1.0 || texUv.y < 0.0 || texUv.y > 1.0) {
        return half4(0.0);
    }

    return uContent.eval(texUv * uResolution);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;

    float p = clamp(uProgress, 0.0, 1.0);

    // 듀오 느낌의 이징: 초반에 빠르게 반응, 끝에서 부드럽게 안착
    float base   = p * p * (3.0 - 2.0 * p);
    float settle = 1.0 - pow(1.0 - p, 2.4);
    float eased  = mix(base, settle, 0.65);

    // 반평면이 기울어진 각도. p=1(펼침) -> 0도, p=0(접힘) -> 약 82도.
    float halfAngle = radians(mix(82.0, 0.0, eased));
    float foldAmount = 1.0 - eased;

    float camDist = max(uCameraDist, 0.5);
    float hingePos = clamp(uHingePos, 0.05, 0.95);

    // ---- 모션 블러 (적응형 탭 수) ----
    float velocity = clamp(uVelocity, 0.0, 1.0);
    int taps = int(clamp(uBlurTaps, 1.0, 8.0));

    // 블러는 각도 방향으로 번지게 — 각도를 살짝씩 흔들어 샘플링하면
    // 화면 공간에서 옆으로 미는 것보다 물리적으로 정확한 궤적이 나옵니다.
    float angleSpread = velocity * radians(3.2);

    float3 accum = float3(0.0);
    float alphaAccum = 0.0;
    float weightSum = 0.0;

    for (int i = 0; i < 8; i++) {
        if (i >= taps) break;

        float fi = taps > 1 ? float(i) / float(taps - 1) : 0.5;
        float offset = (fi - 0.5) * 2.0; // -1 ~ +1

        float sampleAngle = halfAngle + offset * angleSpread;
        half4 s = sampleFold(uv, sampleAngle, camDist, hingePos, uIsVertical);

        if (s.a <= 0.0) continue;

        float weight = 1.0 - abs(offset) * 0.55;
        accum += toLinear(float3(s.rgb)) * weight;
        alphaAccum += float(s.a) * weight;
        weightSum += weight;
    }

    if (weightSum <= 0.0) {
        return half4(0.0, 0.0, 0.0, 0.0);
    }

    float3 lin = accum / weightSum;
    float outAlpha = alphaAccum / weightSum;

    // ---- 조명 계산 (실제 법선 벡터 기반) ----
    // 힌지로부터의 정규화 거리 다시 계산 (음영용)
    float alongHinge = uIsVertical > 0.5 ? uv.x : uv.y;
    float side = alongHinge < hingePos ? -1.0 : 1.0;
    float halfExtent = side < 0.0 ? hingePos : (1.0 - hingePos);
    float distFromHinge = clamp(abs(alongHinge - hingePos) / max(halfExtent, 0.0001), 0.0, 1.0);

    // 회전한 면의 법선: 평면이 halfAngle 만큼 기울어졌으므로
    float3 normal = normalize(float3(side * sin(halfAngle), 0.0, cos(halfAngle)));
    // 광원은 화면 위쪽 앞에서 비춘다고 가정
    float3 lightDir = normalize(float3(0.0, 0.35, 1.0));
    float3 viewDir = float3(0.0, 0.0, 1.0);

    // 램버트 확산: 면이 광원을 향할수록 밝음
    float lambert = clamp(dot(normal, lightDir), 0.0, 1.0);
    // 완전히 펼쳐졌을 때(정면) 1.0 이 되도록 정규화하고, 접힐수록 어두워짐
    float diffuse = mix(0.62, 1.0, lambert);

    // 블린-퐁 스펙큘러
    float3 halfVec = normalize(lightDir + viewDir);
    float specAngle = clamp(dot(normal, halfVec), 0.0, 1.0);
    float specular = pow(specAngle, 48.0) * (0.20 + velocity * 0.12);
    // 전환 중일 때만 광택이 보이도록
    specular *= sin(p * 3.14159) * 0.85 + 0.15;

    // ---- 힌지 주름 ----
    float crease = 1.0 - smoothstepf(0.0, 0.09, distFromHinge);
    float creaseShade = 1.0 - crease * 0.40 * foldAmount;

    // ---- 앰비언트 오클루전 (접힌 안쪽 구석) ----
    float ao = 1.0 - smoothstepf(0.0, 0.30, distFromHinge);
    float aoShade = 1.0 - ao * 0.24 * foldAmount;

    // ---- 가장자리 비네팅 ----
    float edge = smoothstepf(0.82, 1.0, distFromHinge);
    float vignette = 1.0 - edge * 0.16 * foldAmount;

    lin = lin * diffuse * creaseShade * aoShade * vignette + float3(specular);

    float3 rgb = toSrgb(clamp(lin, float3(0.0), float3(1.0)));

    // ---- 둥근 모서리 마스킹 ----
    float2 centered = fragCoord - uResolution * 0.5;
    float sdf = roundedBoxSdf(centered, uResolution * 0.5, uCornerRadius);
    float cornerMask = 1.0 - smoothstepf(-1.5, 1.5, sdf);

    return half4(half3(rgb), half(outAlpha * cornerMask));
}
"""
}
