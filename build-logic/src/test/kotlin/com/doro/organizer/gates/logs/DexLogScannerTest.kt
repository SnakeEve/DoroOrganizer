package com.doro.organizer.gates.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class DexLogScannerTest {
    private val names = mapOf("a.b" to "androidx.core.Foo", "c.d" to "com.doro.organizer.data.Bar")
    private val scanner = DexLogScanner { names[it] ?: it }

    private fun scan(vararg lines: String) = scanner.violations(lines.asSequence())

    @Test
    fun logCallsAnywhere_areReportedUnderTheOriginalClassName() {
        val found = scan(
            "  Class descriptor  : 'La/b;'",
            "0dba16: 7130 e102 5400   |0029: invoke-static {v4, v5, v0}, Landroid/util/Log;.w:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I // method@02e1",
            "0dba7e: 7130 dd02 0405   |005d: invoke-static/range {v0 .. v5}, Landroid/util/Log;.println:(ILjava/lang/String;Ljava/lang/String;)I // method@02dd",
            "  Class descriptor  : 'Lnet/zetetic/database/LogcatTarget;'",
            "0001b4: 7120 2300 1000   |0000: invoke-static {v0, v1}, Landroid/util/Log;->wtf(Ljava/lang/String;Ljava/lang/String;)I // method@0023",
        )
        assertEquals(
            listOf(
                "androidx.core.Foo calls android.util.Log.w",
                "androidx.core.Foo calls android.util.Log.println",
                "net.zetetic.database.LogcatTarget calls android.util.Log.wtf",
            ),
            found,
        )
    }

    @Test
    fun isLoggableAndGetStackTraceString_areAllowed() {
        val found = scan(
            "  Class descriptor  : 'Lcom/doro/organizer/MainActivity;'",
            "0e58f0: 7120 c302 2100   |0000: invoke-static {v1, v2}, Landroid/util/Log;.isLoggable:(Ljava/lang/String;I)Z // method@02c3",
            "0e58f8: 7110 c402 0000   |0004: invoke-static {v0}, Landroid/util/Log;.getStackTraceString:(Ljava/lang/Throwable;)Ljava/lang/String; // method@02c4",
        )
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun printStackTraceAndStandardStreams_areReportedOnlyInAppClasses() {
        val printStackTrace = "|0001: invoke-virtual {v0}, Ljava/lang/Exception;.printStackTrace:()V // method@0101"
        val systemOut = "|0002: sget-object v1, Ljava/lang/System;.out:Ljava/io/PrintStream; // field@078f"
        val systemErr = "|0003: sget-object v2, Ljava/lang/System;->err:Ljava/io/PrintStream; // field@078e"
        val found = scan(
            "  Class descriptor  : 'La/b;'", printStackTrace, systemOut, systemErr,
            "  Class descriptor  : 'Lc/d;'", printStackTrace, systemOut, systemErr, printStackTrace,
        )
        assertEquals(
            listOf(
                "com.doro.organizer.data.Bar calls printStackTrace",
                "com.doro.organizer.data.Bar reads System.out",
                "com.doro.organizer.data.Bar reads System.err",
            ),
            found,
        )
    }

    @Test
    fun otherMembersNamedLikeLevels_areNotLogCalls() {
        val found = scan(
            "  Class descriptor  : 'La/b;'",
            "|0000: invoke-static {v0}, Lu6;.i:(Ljava/lang/String;)V // method@1e6f",
            "|0001: invoke-static {v0}, Landroid/util/LogPrinter;.println:(Ljava/lang/String;)V // method@1e70",
        )
        assertEquals(emptyList<String>(), found)
    }
}
