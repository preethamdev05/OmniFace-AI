---
name: ios-liquid-glassmorphism
description: Master guidelines and design tokens for engineering Apple iOS & macOS Liquid Glassmorphic UIs in Jetpack Compose, featuring AGSL RuntimeShader chromatic dispersion, directional specular reflection borders, layered refraction diffusion surfaces, RenderEffect backdrop blur, and spring-damped micro-interactions.
---

# 💎 iOS & macOS Liquid Glassmorphism in Jetpack Compose

Inspired by [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass/tree/kmp/androidApp) and [`philipplackner/LiquidGlassKMP`](https://github.com/philipplackner/LiquidGlassKMP.git).

---

## 🧪 1. AGSL RuntimeShader & Optical Refraction (API 33+ Android 13 Tiramisu)

Real liquid glass refracts the background pixels with sub-pixel chromatic dispersion (wavelength-dependent index of refraction for red, green, and blue):

```agsl
uniform shader composable;
uniform float2 resolution;
uniform float refractionDistortion;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float2 centerOffset = uv - float2(0.5, 0.5);
    float dist = length(centerOffset);
    float2 displacement = centerOffset * (dist * dist * refractionDistortion);
    
    // Chromatic dispersion: evaluate R, G, B at slightly offset refraction angles
    half4 colorR = composable.eval(fragCoord + displacement * 1.02);
    half4 colorG = composable.eval(fragCoord + displacement);
    half4 colorB = composable.eval(fragCoord + displacement * 0.98);
    
    return half4(colorR.r, colorG.g, colorB.b, (colorR.a + colorG.a + colorB.a) / 3.0);
}
```

### Kotlin Compose Chained RenderEffect Integration
```kotlin
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AgslLiquidGlassShaderHolder {
    val runtimeShader: android.graphics.RuntimeShader by lazy {
        android.graphics.RuntimeShader(AGSL_SHADER).apply {
            setFloatUniform("refractionDistortion", 0.06f)
        }
    }
}

fun Modifier.liquidGlassBackdrop(
    blurRadius: Dp = 16.dp,
    shape: Shape = RoundedCornerShape(20.dp),
    enableRefraction: Boolean = false
): Modifier = this.then(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && enableRefraction) {
        Modifier.graphicsLayer {
            val blur = RenderEffect.createBlurEffect(
                blurRadius.toPx(),
                blurRadius.toPx(),
                Shader.TileMode.CLAMP
            )
            try {
                val shader = AgslLiquidGlassShaderHolder.runtimeShader
                shader.setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
                val refractionEffect = RenderEffect.createRuntimeShaderEffect(shader, "composable")
                val chainedEffect = RenderEffect.createChainEffect(refractionEffect, blur)
                renderEffect = chainedEffect.asComposeRenderEffect()
            } catch (_: Throwable) {
                renderEffect = blur.asComposeRenderEffect()
            }
            this.shape = shape
            clip = true
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(
                blurRadius.toPx(),
                blurRadius.toPx(),
                Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
            this.shape = shape
            clip = true
        }
    } else {
        Modifier
    }
)
```

---

## 🎨 2. Directional Specular Reflection Lighting

Real glass exhibits sharp specular highlights along the edges facing the ambient light source (top-left) and subtle ambient shadow borders along opposite edges (bottom-right):

```kotlin
fun omniLiquidSpecularBorder(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.linearGradient(
            0.0f to Color(0x66FFFFFF), // Crisp top-left specular highlight
            0.35f to Color(0x22FFFFFF),
            0.70f to Color(0x0DFFFFFF),
            1.0f to Color(0x1A000000), // Bottom-right refraction shadow
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    } else {
        Brush.linearGradient(
            0.0f to Color(0x99FFFFFF), // Bright sunlight sheen
            0.45f to Color(0x33000000),
            1.0f to Color(0x1A000000),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    }
}
```

---

## 🌊 3. Multi-Layered Refraction Surface Diffusion

Avoid flat opaque backgrounds. Liquid glass requires multi-stop translucent vertical gradients:

```kotlin
fun omniLiquidSurfaceBrush(isDark: Boolean): Brush {
    return if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color(0x381E293B), // Top highlight refraction
                Color(0x2E0F172A),
                Color(0x3D0B0F19)  // Bottom depth tint
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xF0FFFFFF),
                Color(0xD9F8FAFC),
                Color(0xC8F1F5F9)
            )
        )
    }
}
```

---

## 🧲 4. Tactile Spring Elasticity & Interaction Physics

Every interactive card, button, tab bar, and segmented control must incorporate elastic physics:
- **Press Scale**: Animate from `1.0f` down to `0.97f` or `0.92f` on touch down.
- **Spring Specs**: Use `Spring.DampingRatioMediumBouncy` and `Spring.StiffnessLow`.

```kotlin
val interactionSource = remember { MutableInteractionSource() }
val isPressed by interactionSource.collectIsPressedAsState()

val scale by animateFloatAsState(
    targetValue = if (isPressed && enabled) 0.97f else 1.0f,
    animationSpec = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    ),
    label = "liquidSpringScale"
)
```

---

## 🚫 Invariant Rules
- ❌ **No solid opaque borders**: Always use directional linear gradients with specular top-left white sheens.
- ❌ **No hard instant touch state transitions**: Always use spring animations.
- ❌ **Never crash on legacy Android**: Always provide multi-layer translucent gradient fallbacks (`omniLiquidSurfaceBrush`) on API < 31.
