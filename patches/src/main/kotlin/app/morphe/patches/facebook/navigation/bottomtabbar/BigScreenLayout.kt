/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Big screen layout and device spoofing (#149). Read from 582, 2026-10-14; the obfuscated names are
 * for reviewers, the code never writes one down.
 *
 * The place that decides whether the tab bar goes to the bottom (582 LX/22j;->A08, one of the two
 * reads of the override Tab bar at the bottom answers) first asks a static check taking the
 * Activity (LX/1We;->A07) and, when it says yes, puts the bar at the top without reading the
 * override at all. That check is Facebook's big screen test: it asks a gate of MobileConfig flags
 * (LX/1We;->A09) and, when that allows the big screen layout, the window size class (wide and tall
 * enough), and stores the answer in a static flag the rest of the app reads (LX/1We;->A05). The
 * main screen asks the same check in a dozen places of its own (FbChromeFragment's navigation bar,
 * the insets the bottom bar makes room for, the main screen's theme and layout mode), so answering
 * only the tab bar's place would leave the rest laid out for a big screen. The gate is asked by
 * the check and by the class's other writer of the flag, so answering no gives the whole app the
 * phone layout, while answering yes forces the tablet/foldable big screen layout on tablets like
 * Xiaomi Pad 6 that Facebook otherwise serves stretched phone feeds.
 */

internal const val BIG_SCREEN_GATE = "$BOTTOM_TAB_BAR->bigScreenGate()I"
internal const val SPOOF_MODEL = "$BOTTOM_TAB_BAR->spoofModel(Ljava/lang/String;)Ljava/lang/String;"
internal const val SPOOF_MANUFACTURER = "$BOTTOM_TAB_BAR->spoofManufacturer(Ljava/lang/String;)Ljava/lang/String;"
internal const val SPOOF_BRAND = "$BOTTOM_TAB_BAR->spoofBrand(Ljava/lang/String;)Ljava/lang/String;"

private const val BUILD = "Landroid/os/Build;"

// ACTIVITY is TabBarScrollAwayPatch.kt's.
private const val CONTEXT = "Landroid/content/Context;"

private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)

/**
 * The static `(Activity)Z` checks [read]'s method asks before it loads the override's key, whose
 * yes jumps away at once (`move-result` then `if-nez` on it), the way the tab bar's place skips
 * the override on a big screen.
 */
internal fun bigScreenChecks(read: OverrideRead): List<MethodReference> {
    val code = read.method.implementation?.instructions?.toList() ?: return emptyList()
    val load = read.resultIndex - 4
    return (0 until load).mapNotNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        if (code[at].opcode != Opcode.INVOKE_STATIC || call == null || call.returnType != "Z" || call.params() != listOf(ACTIVITY)) {
            return@mapNotNull null
        }
        val result = code.getOrNull(at + 1)
        val branch = code.getOrNull(at + 2)
        if (result?.opcode != Opcode.MOVE_RESULT || branch?.opcode != Opcode.IF_NEZ ||
            (branch as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA
        ) {
            return@mapNotNull null
        }
        call
    }
}

/**
 * The gate [check] asks: the first static `(Context)Z` call on [check]'s own class, when [check]
 * also stores a static boolean of that class, the flag the app reads. Null otherwise.
 */
internal fun bigScreenGate(check: Method): MethodReference? {
    val code = check.implementation?.instructions?.toList() ?: return null
    val storesFlag = code.any {
        it.opcode == Opcode.SPUT_BOOLEAN && ((it as ReferenceInstruction).reference as FieldReference).definingClass == check.definingClass
    }
    if (!storesFlag) return null
    return code.firstNotNullOfOrNull { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        call?.takeIf {
            instruction.opcode == Opcode.INVOKE_STATIC && it.definingClass == check.definingClass &&
                it.returnType == "Z" && it.params() == listOf(CONTEXT)
        }
    }
}

/**
 * Puts the extension at the top of the big screen gate, found from the big screen check the tab
 * bar's place asks before its override [reads]. A build where either isn't found keeps
 * Facebook's own gate, says so in the patch log, and the switch does nothing there; Tab bar at
 * the bottom goes on without it.
 */
internal fun BytecodePatchContext.hookBigScreenLayout(reads: List<OverrideRead>) {
    val checks = reads.flatMap(::bigScreenChecks).distinctBy { it.toString() }
    val check = checks.singleOrNull() ?: return skip("expected one big screen check before the override, found ${checks.size}")
    val checkMethod = classDefByOrNull(check.definingClass)?.methods?.singleOrNull {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY)
    } ?: return skip("$check isn't in this build")
    val gate = bigScreenGate(checkMethod) ?: return skip("$check stores no flag or asks no gate")
    val gateMethod = mutableClassDefBy(gate.definingClass).methods.singleOrNull {
        it.name == gate.name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT)
    } ?: return skip("$gate isn't in this build")
    if (gateMethod.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference)?.toString() == BIG_SCREEN_GATE } == true) return
    if (gateMethod.implementation!!.registerCount < 2) return skip("$gate has no local register to borrow")
    gateMethod.answerBigScreenGate()
}

private fun skip(why: String) {
    patchLog.warning("$PATCH: $why, so Phone layout on big screens does nothing on this build")
}

/**
 * [hookBigScreenLayout] for the gate itself: answers no (0) when Phone layout on big screens is on,
 * answers yes (1) when Tablet and foldable layout is on, and otherwise runs Facebook's own first instruction.
 * v0 is a local the gate sets before it reads it, and the label binds to that real first instruction.
 */
internal fun MutableMethod.answerBigScreenGate() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $BIG_SCREEN_GATE
            move-result v0
            if-ltz v0, :facebook
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * Whether [method] reads [BUILD]'s static fields MODEL, MANUFACTURER, or BRAND.
 */
internal fun readsDeviceBuild(method: Method): Boolean {
    val code = method.implementation?.instructions ?: return false
    return code.any {
        if (it.opcode != Opcode.SGET_OBJECT) return@any false
        val ref = (it as? ReferenceInstruction)?.reference as? FieldReference ?: return@any false
        ref.definingClass == BUILD && (ref.name == "MODEL" || ref.name == "MANUFACTURER" || ref.name == "BRAND")
    }
}

/**
 * Injects device spoofing after every static read of [BUILD]'s MODEL, MANUFACTURER, or BRAND.
 */
internal fun MutableMethod.hookDeviceSpoof() {
    val code = implementation?.instructions ?: return
    val sites = mutableListOf<Pair<Int, String>>()
    for (i in code.indices) {
        val insn = code[i]
        if (insn.opcode == Opcode.SGET_OBJECT) {
            val ref = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: continue
            if (ref.definingClass == BUILD) {
                when (ref.name) {
                    "MODEL" -> sites += (i to SPOOF_MODEL)
                    "MANUFACTURER" -> sites += (i to SPOOF_MANUFACTURER)
                    "BRAND" -> sites += (i to SPOOF_BRAND)
                }
            }
        }
    }
    if (sites.isEmpty()) return
    for ((idx, hookMethod) in sites.sortedByDescending { it.first }) {
        val reg = (code[idx] as OneRegisterInstruction).registerA
        if (reg > 255) {
            throw PatchException("Register v$reg exceeds 8-bit limit for move-result-object in $definingClass->$name")
        }
        val invoke = if (reg < 16) {
            "invoke-static { v$reg }, $hookMethod"
        } else {
            "invoke-static/range { v$reg .. v$reg }, $hookMethod"
        }
        addInstructions(
            idx + 1,
            """
                $invoke
                move-result-object v$reg
            """,
        )
        val afterCode = implementation?.instructions?.toList()
        val insertedInvoke = afterCode?.getOrNull(idx + 1)
        val insertedMoveResult = afterCode?.getOrNull(idx + 2)
        val expectedInvokeOpcode = if (reg < 16) Opcode.INVOKE_STATIC else Opcode.INVOKE_STATIC_RANGE
        if (insertedInvoke?.opcode != expectedInvokeOpcode ||
            insertedMoveResult?.opcode != Opcode.MOVE_RESULT_OBJECT
        ) {
            throw PatchException(
                "Failed to inject device spoofing at index ${idx + 1} in $definingClass->$name: " +
                    "expected $expectedInvokeOpcode followed by MOVE_RESULT_OBJECT, found " +
                    "${insertedInvoke?.opcode} and ${insertedMoveResult?.opcode}"
            )
        }
    }
}
