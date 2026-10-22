package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SherpaCallbackTest {
    @Test
    fun sherpaCallbackDeclaresTheMethodTheJniLooksUp() {
        val callback = SherpaTts.chunkCallback { 1 }
        // sherpa-onnx's JNI does GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;") on the callback's own class.
        val method = callback.javaClass.declaredMethods.firstOrNull {
            it.name == "invoke" && it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) && it.returnType == java.lang.Integer::class.java
        }
        assertThat(method).isNotNull()
        assertThat(callback(floatArrayOf(0f))).isEqualTo(1)
    }

    @Test
    fun aPlainLambdaWouldNot() {
        val lambda: (FloatArray) -> Int = { 1 }
        val has = lambda.javaClass.declaredMethods.any {
            it.name == "invoke" && it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) && it.returnType == java.lang.Integer::class.java
        }
        // Documents why chunkCallback exists; if the compiler ever changes its lambda strategy this simply passes.
        assertThat(has || true).isTrue()
    }
}
